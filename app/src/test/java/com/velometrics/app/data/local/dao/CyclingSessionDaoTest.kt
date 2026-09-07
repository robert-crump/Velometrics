package com.velometrics.app.data.local.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.velometrics.app.data.local.VelometricsDatabase
import com.velometrics.app.data.local.entity.CyclingSessionEntity
import com.velometrics.app.data.local.entity.IntervalSessionEntity
import com.velometrics.app.data.local.entity.SessionBestEffortEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Covers #194's DAO acceptance criterion: [CyclingSessionDao.deleteSessions] must cascade each
 * deleted session's `interval_sessions`/`session_best_efforts` rows (same FK cascade the existing
 * single-session [CyclingSessionDao.delete] already relies on — see #193), and the batch must be
 * atomic — a failure partway through must not leave a partial deletion.
 *
 * Uses a real Room-backed [VelometricsDatabase] via Robolectric (native SQLite), same setup as
 * [com.velometrics.app.data.local.CyclingAssetDatabaseFixtureTest] and
 * [com.velometrics.app.data.local.VelometricsMigrationTest], so FK enforcement and transaction
 * rollback are the genuine SQLite behavior rather than something a fake would have to assert about
 * itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CyclingSessionDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dbNames = mutableListOf<String>()

    private fun freshDb(name: String): VelometricsDatabase {
        context.deleteDatabase(name)
        dbNames += name
        return Room.databaseBuilder(context, VelometricsDatabase::class.java, name)
            .fallbackToDestructiveMigration()
            .build()
    }

    @After
    fun tearDown() {
        dbNames.forEach { context.deleteDatabase(it) }
    }

    private fun buildSession(sha1: String) = CyclingSessionEntity(
        fileName = "$sha1.fit",
        fileSha1 = sha1,
        sessionStart = 0L,
        sessionEnd = 3_600_000L,
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = 30.0,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = "{}",
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = false
    )

    private fun buildInterval(sessionId: Long) = IntervalSessionEntity(
        cyclingSessionId = sessionId,
        startTimestamp = 0L,
        durationSec = 60,
        durationNormalizedSec = 60,
        distanceM = 500.0,
        avgPower = 200,
        avgSpeedKmh = 30.0,
        avgSpeedNormalizedKmh = 30.0,
        direction = "N",
        startLat = 0.0,
        startLon = 0.0,
        endLat = 0.0,
        endLon = 0.0,
        gpsTrack = "[]"
    )

    private fun buildBestEffort(sessionId: Long) = SessionBestEffortEntity(sessionId = sessionId)

    @Test
    fun `deleteSessions cascades interval_sessions and session_best_efforts for every deleted id`() = runBlocking {
        val db = freshDb("delete_sessions_cascade_test.db")
        try {
            val sessionDao = db.cyclingSessionDao()
            val intervalDao = db.intervalSessionDao()
            val bestEffortDao = db.sessionBestEffortDao()

            val id1 = sessionDao.insert(buildSession("sha-1"))
            val id2 = sessionDao.insert(buildSession("sha-2"))
            val id3 = sessionDao.insert(buildSession("sha-3"))
            intervalDao.insert(buildInterval(id1))
            intervalDao.insert(buildInterval(id2))
            bestEffortDao.insert(buildBestEffort(id1))
            bestEffortDao.insert(buildBestEffort(id2))

            sessionDao.deleteSessions(listOf(id1, id2))

            assertEquals(1, sessionDao.getSessionCount())
            assertNull("session $id1 should be deleted", sessionDao.getById(id1))
            assertNull("session $id2 should be deleted", sessionDao.getById(id2))
            assertNotNull("session $id3 was not selected for deletion", sessionDao.getById(id3))
            assertTrue(
                "interval_sessions must cascade for id1",
                intervalDao.getBySessionId(id1).first().isEmpty()
            )
            assertTrue(
                "interval_sessions must cascade for id2",
                intervalDao.getBySessionId(id2).first().isEmpty()
            )
            assertNull("session_best_efforts must cascade for id1", bestEffortDao.getBySessionId(id1))
            assertNull("session_best_efforts must cascade for id2", bestEffortDao.getBySessionId(id2))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a thrown exception mid-batch rolls back every delete already issued`() = runBlocking {
        // deleteSessions is a @Transaction default method on the Dao interface, so there is no
        // seam to inject a failure between two of its own internal deleteById calls without
        // faking the whole Dao (which would only prove the fake's own bookkeeping, not real
        // rollback). Instead this exercises the exact mechanism @Transaction relies on — Room's
        // withTransaction wrapping calls to the same generated deleteById — and confirms that an
        // exception thrown after some deletes have already been issued rolls all of them back.
        val db = freshDb("delete_sessions_atomic_test.db")
        try {
            val sessionDao = db.cyclingSessionDao()
            val id1 = sessionDao.insert(buildSession("sha-a"))
            val id2 = sessionDao.insert(buildSession("sha-b"))

            var caught = false
            try {
                db.withTransaction {
                    sessionDao.deleteById(id1)
                    sessionDao.deleteById(id2)
                    throw RuntimeException("simulated failure")
                }
            } catch (e: RuntimeException) {
                caught = true
            }

            assertTrue("expected the simulated failure to propagate out of the transaction", caught)
            assertEquals(
                "both deletes must be rolled back, not left partially applied",
                2,
                sessionDao.getSessionCount()
            )
        } finally {
            db.close()
        }
    }
}
