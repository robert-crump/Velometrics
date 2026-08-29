package com.velometrics.app.domain.service

import com.velometrics.app.data.repository.FakeBestEffortRepository
import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealScope
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PowerCurveAchievementEvaluatorTest {

    private lateinit var repository: FakeBestEffortRepository
    private lateinit var evaluator: PowerCurveAchievementEvaluator

    @Before
    fun setup() {
        repository = FakeBestEffortRepository()
        evaluator = PowerCurveAchievementEvaluator(repository)
    }

    private fun record(
        sessionId: Long,
        start: Instant,
        power5s: Int? = 900,
        power1m: Int? = 400,
        power5m: Int? = 300,
        power20m: Int? = 250
    ): BestEffortRecord = BestEffortRecord(
        sessionId = sessionId,
        sessionStart = start.toEpochMilli(),
        split25kSec = null,
        split50kSec = null,
        split100kSec = null,
        power1s = null,
        power3s = null,
        power5s = power5s,
        power20s = null,
        power30s = null,
        power1m = power1m,
        power5m = power5m,
        power20m = power20m,
        power30m = null
    )

    @Test
    fun `first-ever ride ranks 1st all-time on every power duration`() = runBlocking {
        val start = Instant.parse("2026-06-01T00:00:00Z")
        repository.records.add(record(sessionId = 1, start = start))

        val candidates = evaluator.candidates(sessionId = 1, sessionStart = start)

        val allTime = candidates.filter { it.priority.scope == RideRevealScope.ALL_TIME }
        assertEquals(4, allTime.size) // 5s, 1m, 5m, 20m
        assertTrue(allTime.all { it.priority.rank == 1 && it.priority.family == RideRevealFamily.POWER_CURVE_BEST_EFFORT })
    }

    @Test
    fun `ride beaten by three others on a duration does not qualify on it`() = runBlocking {
        repository.records.add(record(sessionId = 1, start = Instant.parse("2026-01-01T00:00:00Z"), power5m = 320))
        repository.records.add(record(sessionId = 2, start = Instant.parse("2026-01-02T00:00:00Z"), power5m = 330))
        repository.records.add(record(sessionId = 3, start = Instant.parse("2026-01-03T00:00:00Z"), power5m = 340))
        val start = Instant.parse("2026-01-04T00:00:00Z")
        repository.records.add(record(sessionId = 4, start = start, power5m = 300))

        val candidates = evaluator.candidates(sessionId = 4, sessionStart = start)

        assertTrue(candidates.none { it.headline.contains("5-minute") })
    }

    @Test
    fun `ride beaten by only two others still qualifies at rank 3`() = runBlocking {
        repository.records.add(record(sessionId = 1, start = Instant.parse("2026-01-01T00:00:00Z"), power5m = 320))
        repository.records.add(record(sessionId = 2, start = Instant.parse("2026-01-02T00:00:00Z"), power5m = 330))
        val start = Instant.parse("2026-01-04T00:00:00Z")
        repository.records.add(record(sessionId = 3, start = start, power5m = 300))

        val candidates = evaluator.candidates(sessionId = 3, sessionStart = start)

        val fiveMin = candidates.first { it.headline.contains("5-minute") && it.priority.scope == RideRevealScope.ALL_TIME }
        assertEquals(3, fiveMin.priority.rank)
    }

    @Test
    fun `this-year scope only counts rides in the ride's own calendar year`() = runBlocking {
        repository.records.add(record(sessionId = 1, start = Instant.parse("2025-06-01T00:00:00Z"), power20m = 500))
        val start = Instant.parse("2026-01-04T00:00:00Z")
        repository.records.add(record(sessionId = 2, start = start, power20m = 250))

        val candidates = evaluator.candidates(sessionId = 2, sessionStart = start)

        val thisYear = candidates.first { it.headline.contains("20-minute") && it.priority.scope == RideRevealScope.THIS_YEAR }
        assertEquals(1, thisYear.priority.rank) // last year's bigger effort doesn't count

        val allTime = candidates.first { it.headline.contains("20-minute") && it.priority.scope == RideRevealScope.ALL_TIME }
        assertEquals(2, allTime.priority.rank) // but does count all-time
    }

    @Test
    fun `a ride with no best-effort row registers no candidates`() = runBlocking {
        val candidates = evaluator.candidates(sessionId = 99, sessionStart = Instant.parse("2026-01-01T00:00:00Z"))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a null power value for a duration registers no candidate for that duration`() = runBlocking {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        repository.records.add(record(sessionId = 1, start = start, power20m = null))

        val candidates = evaluator.candidates(sessionId = 1, sessionStart = start)

        assertTrue(candidates.none { it.headline.contains("20-minute") })
    }

    @Test
    fun `headline for a rank-1 all-time 5-second power PR reads naturally`() = runBlocking {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        repository.records.add(record(sessionId = 1, start = start))

        val candidates = evaluator.candidates(sessionId = 1, sessionStart = start)

        val fiveSecond = candidates.first { it.headline.contains("5-second") && it.priority.scope == RideRevealScope.ALL_TIME }
        assertEquals("Your best 5-second power ever!", fiveSecond.headline)
    }
}
