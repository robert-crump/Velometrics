package com.velometrics.app.data.fitimport

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.RecordMesg
import com.velometrics.app.data.local.VelometricsDatabase
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.data.repository.BestEffortRepositoryImpl
import com.velometrics.app.data.repository.CyclingSessionRepositoryImpl
import com.velometrics.app.data.repository.IntervalRepositoryImpl
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.IntervalDetector
import com.velometrics.app.domain.service.IntervalMatcher
import com.velometrics.app.domain.service.RideClassifier
import com.velometrics.app.domain.service.SprintDetector
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * #206: every DB write in [FitImportService.importFile] commits as one transaction, so a failure
 * partway through leaves nothing behind (and dedup checks keep saying "not imported"). Runs the real
 * Room stack via Robolectric so rollback is genuine SQLite behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FitImportServiceTransactionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dbName = "fit-import-transaction-test"
    private lateinit var db: VelometricsDatabase
    private lateinit var realSessions: CyclingSessionRepositoryImpl

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, VelometricsDatabase::class.java, dbName)
            .fallbackToDestructiveMigration()
            .build()
        realSessions = CyclingSessionRepositoryImpl(db.cyclingSessionDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun service(sessions: CyclingSessionRepository): FitImportService {
        val settings = mockk<UserSettingsRepository>()
        every { settings.ftp } returns flowOf(250)
        every { settings.maxHr } returns flowOf(190)
        return FitImportService(
            sessionRepository = sessions,
            metricsCalculator = SessionMetricsCalculator(),
            intervalDetector = IntervalDetector(),
            intervalMatcher = mockk<IntervalMatcher>(relaxed = true),
            intervalRepository = IntervalRepositoryImpl(db.intervalSessionDao()),
            sprintDetector = SprintDetector(),
            userSettingsRepository = settings,
            bestEffortRepository = BestEffortRepositoryImpl(db.sessionBestEffortDao()),
            velometricsDatabase = db
        )
    }

    /** 30 min of 1 Hz records heading north at ~36 km/h and a steady 130 W. */
    private fun buildFitBytes(): ByteArray {
        val file = File.createTempFile("import-tx", ".fit")
        try {
            val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
            encoder.write(FileIdMesg().apply {
                type = com.garmin.fit.File.ACTIVITY
                manufacturer = 1
                product = 1
                serialNumber = 1L
                timeCreated = DateTime(1_000_000_000L)
            })
            val semicirclesPerDeg = (1L shl 31) / 180.0
            repeat(1800) { i ->
                encoder.write(RecordMesg().apply {
                    timestamp = DateTime(1_000_000_000L + i)
                    positionLat = ((45.0 + i * 0.00009) * semicirclesPerDeg).toInt()
                    positionLong = (10.0 * semicirclesPerDeg).toInt()
                    power = 130
                    speed = 10.0f
                })
            }
            encoder.close()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `successful import commits session, best efforts and tag together`() = runBlocking {
        val result = service(realSessions).importFile("ride.fit", buildFitBytes())

        assertTrue("expected Success but was $result", result is ImportResult.Success)
        val id = (result as ImportResult.Success).sessionId
        val stored = realSessions.getSessionById(id)
        assertNotNull(stored)
        assertNotNull(db.sessionBestEffortDao().getBySessionId(id))
        assertEquals(RideClassifier.classify(stored!!, 250)?.label, stored.tag)
        assertTrue(realSessions.existsByFileName("ride.fit"))
    }

    @Test
    fun `failure after insertSession rolls back every write for that import`() = runBlocking {
        val failingTag = object : CyclingSessionRepository by realSessions {
            override suspend fun updateTag(sessionId: Long, tag: String?) {
                throw IllegalStateException("simulated failure between insertSession and updateTag")
            }
        }
        val bytes = buildFitBytes()

        val result = service(failingTag).importFile("ride.fit", bytes)

        assertTrue("expected Error but was $result", result is ImportResult.Error)
        assertFalse(realSessions.existsByFileName("ride.fit"))
        assertEquals(0, realSessions.getSessionCount())
        assertNotNull(service(realSessions).importFile("ride.fit", bytes).takeIf { it is ImportResult.Success })
    }
}
