package com.velometrics.app.domain.service

import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealScope
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RideMilestoneEvaluatorTest {

    private lateinit var repository: FakeCyclingSessionRepository
    private lateinit var evaluator: RideMilestoneEvaluator

    @Before
    fun setup() {
        repository = FakeCyclingSessionRepository()
        evaluator = RideMilestoneEvaluator(repository)
    }

    private fun session(
        id: Long,
        start: Instant,
        distanceKm: Double = 30.0,
        elevationGainM: Double? = 250.0,
        netDurationSec: Int = 3600
    ): CyclingSession = CyclingSession(
        id = id,
        fileName = "ride_$id.fit",
        fileSha1 = "sha1_$id",
        sessionStart = start,
        sessionEnd = start.plusSeconds(netDurationSec.toLong()),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = distanceKm,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 95.0,
        powerQualityPercent = null,
        hasPower = false,
        gpsTrack = null,
        elevationGainM = elevationGainM
    )

    private suspend fun insert(session: CyclingSession): Long = repository.insertSession(session)

    @Test
    fun `first-ever ride ranks 1st all-time on every metric`() = runBlocking {
        val ride = session(id = 0, start = Instant.parse("2026-06-01T00:00:00Z"))
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        val allTime = candidates.filter { it.priority.scope == RideRevealScope.ALL_TIME }
        assertEquals(3, allTime.size) // distance, elevation, average speed
        assertTrue(allTime.all { it.priority.rank == 1 && it.priority.family == RideRevealFamily.RIDE_MILESTONE })
    }

    @Test
    fun `ride beaten by three others on a metric does not qualify on that metric`() = runBlocking {
        insert(session(id = 0, start = Instant.parse("2026-01-01T00:00:00Z"), distanceKm = 40.0))
        insert(session(id = 0, start = Instant.parse("2026-01-02T00:00:00Z"), distanceKm = 50.0))
        insert(session(id = 0, start = Instant.parse("2026-01-03T00:00:00Z"), distanceKm = 60.0))
        val ride = session(id = 0, start = Instant.parse("2026-01-04T00:00:00Z"), distanceKm = 30.0)
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        assertTrue(candidates.none { it.headline.contains("ride") })
    }

    @Test
    fun `rank 4th-place ride still qualifies on a metric it hasn't been beaten on 3 times`() = runBlocking {
        insert(session(id = 0, start = Instant.parse("2026-01-01T00:00:00Z"), distanceKm = 40.0))
        insert(session(id = 0, start = Instant.parse("2026-01-02T00:00:00Z"), distanceKm = 50.0))
        val ride = session(id = 0, start = Instant.parse("2026-01-04T00:00:00Z"), distanceKm = 30.0)
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        val distanceCandidate = candidates.first { it.headline.contains("ride") }
        assertEquals(3, distanceCandidate.priority.rank)
    }

    @Test
    fun `this-year scope only counts rides in the ride's own calendar year`() = runBlocking {
        insert(session(id = 0, start = Instant.parse("2025-06-01T00:00:00Z"), distanceKm = 500.0))
        val ride = session(id = 0, start = Instant.parse("2026-01-04T00:00:00Z"), distanceKm = 30.0)
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        val distanceThisYear = candidates.first {
            it.headline.contains("ride") && it.priority.scope == RideRevealScope.THIS_YEAR
        }
        assertEquals(1, distanceThisYear.priority.rank) // last year's 500km ride doesn't count

        val distanceAllTime = candidates.first {
            it.headline.contains("ride") && it.priority.scope == RideRevealScope.ALL_TIME
        }
        assertEquals(2, distanceAllTime.priority.rank) // but does count all-time
    }

    @Test
    fun `a ride with no elevation data registers no elevation candidate`() = runBlocking {
        val ride = session(id = 0, start = Instant.parse("2026-01-01T00:00:00Z"), elevationGainM = null)
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        assertTrue(candidates.none { it.headline.contains("elevation") })
    }

    @Test
    fun `a zero-duration ride registers no average-speed candidate`() = runBlocking {
        val ride = session(id = 0, start = Instant.parse("2026-01-01T00:00:00Z"), netDurationSec = 0)
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        assertTrue(candidates.none { it.headline.contains("speed") })
    }

    @Test
    fun `headline for a rank-1 all-time distance PR reads naturally`() = runBlocking {
        val ride = session(id = 0, start = Instant.parse("2026-01-01T00:00:00Z"), distanceKm = 100.0)
        val id = insert(ride)

        val candidates = evaluator.candidates(ride.copy(id = id))

        val distanceCandidate = candidates.first { it.headline.contains("ride") }
        assertEquals("Your longest ride ever!", distanceCandidate.headline)
    }
}
