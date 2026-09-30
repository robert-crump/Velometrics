package com.velometrics.app.demo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.velometrics.app.data.fitimport.FitImportServiceImpl
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.data.fitimport.SessionMetricsCalculator
import com.velometrics.app.data.local.VelometricsDatabase
import com.velometrics.app.data.local.entity.SessionBestEffortEntity
import com.velometrics.app.data.preferences.FtpHistoryRepository
import com.velometrics.app.data.preferences.LegacyFtpStore
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.data.repository.BestEffortRepositoryImpl
import com.velometrics.app.data.repository.CyclingSessionRepositoryImpl
import com.velometrics.app.data.repository.IntervalRepositoryImpl
import com.velometrics.app.data.repository.RepeatedRouteRepositoryImpl
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.service.IntervalDetector
import com.velometrics.app.domain.service.IntervalMatcher
import com.velometrics.app.domain.service.IntervalSimilarity
import com.velometrics.app.domain.service.RouteClusteringService
import com.velometrics.app.domain.service.SprintDetector
import com.velometrics.app.util.Json
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * #219: every generated demo ride goes through the real FIT import (Room via Robolectric) and
 * produces what the README screenshots need — intervals, sprints, a repeated route, repeated
 * intervals, and a hero ride with cardiac drift and stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DemoRideImportTest {

    private class Imported(
        val ride: DemoRide,
        val session: CyclingSession,
        val intervals: List<IntervalSession>,
        val bestEfforts: SessionBestEffortEntity
    )

    /** Snapshot of the whole import + route clustering, taken once and then read by every test. */
    private class Fixture(val imported: List<Imported>, val routes: List<Set<Long>>)

    companion object {
        private const val DB_NAME = "demo-ride-import-test"

        // Built lazily inside the first test (Robolectric's sandbox isn't up in @BeforeClass).
        private var cached: Fixture? = null

        private fun importAll(): Fixture = runBlocking {
            val context: Context = ApplicationProvider.getApplicationContext()
            context.deleteDatabase(DB_NAME)
            val db = Room.databaseBuilder(context, VelometricsDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
            try {
                val sessions = CyclingSessionRepositoryImpl(db.cyclingSessionDao())
                val settings = mockk<UserSettingsRepository>()
                every { settings.maxHr } returns flowOf(190)
                val intervalRepository = IntervalRepositoryImpl(db.intervalSessionDao())
                val service = FitImportServiceImpl(
                    sessionRepository = sessions,
                    metricsCalculator = SessionMetricsCalculator(),
                    intervalDetector = IntervalDetector(),
                    intervalMatcher = mockk<IntervalMatcher>(relaxed = true),
                    intervalRepository = intervalRepository,
                    sprintDetector = SprintDetector(),
                    userSettingsRepository = settings,
                    ftpHistoryRepository = FtpHistoryRepository(db.ftpHistoryDao(), object : LegacyFtpStore {
                        override suspend fun takeLegacyFtp(): Int = DemoRideGenerator.FTP
                    }),
                    bestEffortRepository = BestEffortRepositoryImpl(db.sessionBestEffortDao()),
                    velometricsDatabase = db
                )
                val imported = DemoRideTestSupport.rides.map { ride ->
                    val result = service.importFile(ride.fileName, ride.fitBytes)
                    check(result is ImportResult.Success) { "${ride.fileName}: $result" }
                    val session = sessions.getSessionById(result.sessionId)!!
                    val intervals = intervalRepository.getIntervalsForSession(session.id).first()
                        .sortedBy { it.startTimestamp }
                    Imported(ride, session, intervals, db.sessionBestEffortDao().getBySessionId(session.id)!!)
                }

                val routeRepository = RepeatedRouteRepositoryImpl(db.repeatedRouteDao(), sessions)
                RouteClusteringService(sessions, routeRepository).runClustering()
                val routes = routeRepository.getAllRoutesList().map { route -> route.sessions.map { it.id }.toSet() }

                imported.forEach {
                    println(
                        "%2d %-13s %s %5.1fkm %4dmin pause=%4ds avgP=%3d NP=%3d HR=%3d int=%d %s sprints=%d/%d drift=%s gps=%.1f%%".format(
                            it.ride.index, it.ride.loop.id, it.ride.start, it.session.distanceKm,
                            it.session.netDurationSec / 60, it.session.pauseDurationSec, it.session.averagePower,
                            it.session.normalizedPower, it.session.avgHeartRate, it.intervals.size,
                            it.intervals.joinToString(",", "[", "]") { i -> "${i.durationSec}s/${i.distanceM.toInt()}m/${i.avgPower}W" },
                            it.session.sprintCount, it.ride.plan.sprints.size, it.session.cardiacDriftPercent,
                            it.session.gpsQualityPercent
                        )
                    )
                }
                println("routes: $routes")
                Fixture(imported, routes)
            } finally {
                db.close()
                context.deleteDatabase(DB_NAME)
            }
        }
    }

    private val fixture: Fixture get() = cached ?: importAll().also { cached = it }
    private val imported get() = fixture.imported
    private val rides get() = DemoRideTestSupport.rides

    private fun ridesOf(loop: DemoLoop) = imported.filter { it.ride.loop == loop }

    @Test
    fun `every ride imports with power, heart rate and no GPS points discarded`() {
        assertEquals(rides.size, imported.size)
        imported.forEach {
            assertTrue(it.ride.fileName, it.session.hasPower)
            assertTrue(it.ride.fileName, it.session.hasHR)
            assertEquals(it.ride.fileName, 100.0, it.session.gpsQualityPercent, 0.0)
            assertEquals(it.ride.fileName, 100.0, it.session.powerQualityPercent!!, 0.0)
        }
    }

    @Test
    fun `imported distance matches the loop length`() {
        val expectedKm = mapOf(
            DemoLoop.AACHEN to 31.2, DemoLoop.HERZOGENRATH to 40.2,
            DemoLoop.MECHELEN to 52.5, DemoLoop.SUED_LIMBURG to 84.7
        )
        imported.forEach {
            // GPS jitter adds a little path length on top of the resampled loop.
            assertEquals(it.ride.fileName, expectedKm.getValue(it.ride.loop), it.session.distanceKm, 1.5)
        }
    }

    @Test
    fun `aachen interval rides detect one interval per pinned effort`() {
        ridesOf(DemoLoop.AACHEN).forEach {
            assertEquals(it.ride.fileName, DemoLoop.AACHEN.efforts.size, it.intervals.size)
            it.intervals.forEach { interval ->
                assertTrue("${it.ride.fileName}: ${interval.durationSec}s", interval.durationSec in 240..420)
            }
        }
    }

    @Test
    fun `mechelen climbs are detected as intervals`() {
        ridesOf(DemoLoop.MECHELEN).forEach {
            assertEquals(it.ride.fileName, DemoLoop.MECHELEN.efforts.size, it.intervals.size)
        }
    }

    @Test
    fun `every ride detects its sprints`() {
        imported.forEach {
            assertEquals(it.ride.fileName, it.ride.plan.sprints.size, it.session.sprintCount)
        }
    }

    @Test
    fun `each loop clusters into its own repeated route`() {
        assertEquals(DemoLoop.entries.size, fixture.routes.size)
        DemoLoop.entries.forEach { loop ->
            assertTrue(loop.id, ridesOf(loop).map { it.session.id }.toSet() in fixture.routes)
        }
    }

    @Test
    fun `efforts at pinned positions match each other as repeated intervals`() {
        listOf(DemoLoop.AACHEN, DemoLoop.MECHELEN, DemoLoop.HERZOGENRATH).forEach { loop ->
            val loopRides = ridesOf(loop)
            loop.efforts.indices.forEach { k ->
                val reps = loopRides.map { prepared(it.intervals[k]) }
                val reference = reps.first()
                reps.drop(1).forEachIndexed { n, rep ->
                    assertTrue("${loop.id} effort $k ride ${n + 1}", IntervalSimilarity.qualifies(reference, rep))
                }
            }
        }
    }

    @Test
    fun `hero ride has cardiac drift, stops, sprints and full coverage`() {
        val hero = imported.last()
        assertEquals(DemoLoop.SUED_LIMBURG, hero.ride.loop)
        assertTrue(hero.session.netDurationSec >= 3600)
        assertNotNull(hero.session.cardiacDriftPercent)
        assertTrue(hero.session.pauseDurationSec > 0)
        assertTrue(hero.session.sprintCount > 0)
    }

    @Test
    fun `power curve lands near the target profile`() {
        val efforts = imported.map { it.bestEfforts }
        val best5s = efforts.maxOf { it.power5s ?: 0 }
        val best20m = efforts.maxOf { it.power20m ?: 0 }
        val best2h = rides.maxOf { DemoRideTestSupport.bestAvgPower(it, 7200) ?: 0 }
        println("power curve: 5s=$best5s 20m=$best20m 2h=$best2h")
        assertEquals(950.0, best5s.toDouble(), 95.0)
        assertEquals(280.0, best20m.toDouble(), 28.0)
        assertEquals(200.0, best2h.toDouble(), 20.0)
    }

    private fun prepared(interval: IntervalSession) = IntervalSimilarity.PreparedTrack(
        Json.parseOrDefault<List<List<Double>>>(interval.gpsTrack, "test", "bad track", emptyList()),
        interval.distanceM
    )
}
