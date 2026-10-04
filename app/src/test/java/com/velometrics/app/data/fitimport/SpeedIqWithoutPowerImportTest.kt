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
import com.velometrics.app.data.preferences.FtpHistoryRepository
import com.velometrics.app.data.preferences.LegacyFtpStore
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.data.repository.BestEffortRepositoryImpl
import com.velometrics.app.data.repository.CyclingSessionRepositoryImpl
import com.velometrics.app.data.repository.IntervalRepositoryImpl
import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.domain.service.CardiacDriftAdviceService
import com.velometrics.app.domain.service.IntervalDetector
import com.velometrics.app.domain.service.IntervalMatcher
import com.velometrics.app.domain.service.SprintDetector
import com.velometrics.app.fakes.testSession
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * #229: a ride without power gets Speed IQ with an estimated P -- the median of the per-ride P of
 * the last 10 power rides before it, else 60 % of FTP as of the ride. Runs the real import and Room
 * stack so the "last 10 before this ride" query is the one the app uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SpeedIqWithoutPowerImportTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dbName = "speed-iq-without-power-test"
    private lateinit var db: VelometricsDatabase
    private lateinit var sessions: CyclingSessionRepositoryImpl

    /** FIT time 1_000_000_000 s after the FIT epoch (1989-12-31T00:00:00Z). */
    private val rideStart = Instant.parse("1989-12-31T00:00:00Z").plusSeconds(1_000_000_000L)

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, VelometricsDatabase::class.java, dbName)
            .fallbackToDestructiveMigration()
            .build()
        sessions = CyclingSessionRepositoryImpl(db.cyclingSessionDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun service(retest: Pair<LocalDate, Int>? = null): FitImportServiceImpl {
        val settings = mockk<UserSettingsRepository>()
        every { settings.maxHr } returns flowOf(190)
        every { settings.systemWeightKg } returns flowOf(null)
        val ftpHistory = FtpHistoryRepository(db.ftpHistoryDao(), object : LegacyFtpStore {
            override suspend fun takeLegacyFtp(): Int = 250
        })
        retest?.let { (date, ftp) -> runBlocking { ftpHistory.save(date, ftp) } }
        return FitImportServiceImpl(
            sessionRepository = sessions,
            metricsCalculator = SessionMetricsCalculator(),
            intervalDetector = IntervalDetector(),
            intervalMatcher = mockk<IntervalMatcher>(relaxed = true),
            intervalRepository = IntervalRepositoryImpl(db.intervalSessionDao()),
            sprintDetector = SprintDetector(),
            userSettingsRepository = settings,
            ftpHistoryRepository = ftpHistory,
            bestEffortRepository = BestEffortRepositoryImpl(db.sessionBestEffortDao()),
            velometricsDatabase = db,
            cardiacDriftAdviceService = CardiacDriftAdviceService(sessions)
        )
    }

    /**
     * 10 min at 36 km/h on the flat with altitude, then a stop: braking evenly to 0 over 10 s,
     * 20 s standing, and 2 min pedalling on. [power] null leaves the records without power.
     */
    private fun buildFitBytes(power: Int?): ByteArray {
        val speeds = List(600) { 10.0 } + (1..10).map { 10.0 * (10 - it) / 10 } + List(20) { 0.0 } + List(120) { 6.0 }
        val file = File.createTempFile("speed-iq", ".fit")
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
            var northM = 0.0
            speeds.forEachIndexed { i, v ->
                if (i > 0) northM += (speeds[i - 1] + v) / 2
                encoder.write(RecordMesg().apply {
                    timestamp = DateTime(1_000_000_000L + i)
                    positionLat = ((45.0 + northM / 111_320.0) * semicirclesPerDeg).toInt()
                    positionLong = (10.0 * semicirclesPerDeg).toInt()
                    if (power != null) this.power = if (v > 0 && i !in 600..629) power else 0
                    speed = v.toFloat()
                    altitude = 100f
                })
            }
            encoder.close()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** A stored power ride whose Speed IQ P is [referencePowerW]. */
    private suspend fun storePowerRide(id: Long, start: Instant, referencePowerW: Int) {
        sessions.insertSession(
            testSession(id, start).copy(
                hasPower = true,
                speedIq = SpeedIq(true, 0.0, 0.0, 0.0, 0, referencePowerW, 85.0, topEvents = emptyList())
            )
        )
    }

    private suspend fun importedSpeedIq(bytes: ByteArray, retest: Pair<LocalDate, Int>? = null): SpeedIq {
        val result = service(retest).importFile("ride.fit", bytes)
        assertTrue("expected Success but was $result", result is ImportResult.Success)
        return sessions.getSessionById((result as ImportResult.Success).sessionId)!!.speedIq!!
    }

    @Test
    fun `a ride without power uses the median P of the last 10 power rides before it`() = runBlocking {
        // 12 power rides before (P 100..210 W, oldest first), one after, and a non-power ride before.
        (0 until 12).forEach { k ->
            storePowerRide(k + 1L, rideStart.minusSeconds((12 - k) * 86_400L), referencePowerW = 100 + k * 10)
        }
        storePowerRide(50, rideStart.plusSeconds(86_400), referencePowerW = 400)
        sessions.insertSession(testSession(60, rideStart.minusSeconds(3600)))

        val speedIq = importedSpeedIq(buildFitBytes(power = null))

        // The last 10 before it are 120..210 W: median 165 W. The 100/110 W and the later 400 W are out.
        assertTrue(speedIq.referencePowerEstimated)
        assertEquals(165, speedIq.referencePowerW)
        assertTrue(speedIq.hasElevation)
        assertTrue("events: ${speedIq.eventCount}", speedIq.eventCount >= 1)
        assertTrue(speedIq.brakingPenaltySec > 0)
    }

    @Test
    fun `with no power rides before it, P falls back to 60 percent of FTP as of the ride`() = runBlocking {
        // A retest the day before the ride takes FTP from the legacy 250 W to 300 W.
        val rideDate = rideStart.atZone(ZoneId.systemDefault()).toLocalDate()
        storePowerRide(1, rideStart.plusSeconds(86_400), referencePowerW = 400)

        val speedIq = importedSpeedIq(buildFitBytes(power = null), retest = rideDate.minusDays(1) to 300)

        assertTrue(speedIq.referencePowerEstimated)
        assertEquals(180, speedIq.referencePowerW)
        assertTrue(speedIq.eventCount >= 1)
    }

    @Test
    fun `a ride with power keeps its own measured P`() = runBlocking {
        storePowerRide(1, rideStart.minusSeconds(86_400), referencePowerW = 300)

        val speedIq = importedSpeedIq(buildFitBytes(power = 130))

        assertFalse(speedIq.referencePowerEstimated)
        assertEquals(130, speedIq.referencePowerW)
        assertTrue(speedIq.eventCount >= 1)
    }
}
