package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.SessionMetricSample
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeIntervalRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.fakes.FakeRepeatedRoutesCache
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SessionNarrativeAssemblerTest {

    private val start = Instant.parse("2026-03-10T08:00:00Z")

    private fun session(id: Long, daysBefore: Long, tag: String?, fatEfficiency: Int = 70) = CyclingSession(
        id = id,
        fileName = "ride-$id.fit",
        fileSha1 = "sha-$id",
        sessionStart = start.minusSeconds(daysBefore * 86_400),
        sessionEnd = start.minusSeconds(daysBefore * 86_400).plusSeconds(3600),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = 30.0,
        averagePower = 150,
        normalizedPower = 160,
        fatBurnedGrams = 20.0,
        carbsBurnedGrams = 50.0,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = 90.0,
        hasPower = true,
        fatEfficiencyScore = fatEfficiency,
        cardiacDriftPercent = 4.0,
        tag = tag
    )

    private fun assembler(
        sessions: CyclingSessionRepository,
        intervals: IntervalRepository = FakeIntervalRepository()
    ) = SessionNarrativeAssembler(sessions, intervals, SessionComparator(sessions), FakeRepeatedRoutesCache())

    @Test
    fun `tagged session with history gets the headline and an all-time recap`() = runTest {
        val repo = FakeCyclingSessionRepository()
        val current = session(1, 0, "Zone 2", fatEfficiency = 80)
        // Six earlier rides -- beyond the old "last 5" window -- with fat efficiency 60..., median 70.
        val history = (2L..7L).map { session(it, it, "Zone 2", fatEfficiency = 60 + (it * 2).toInt()) }
        repo.sessions.addAll(listOf(current) + history)

        val narrative = assembler(repo).build(current)!!

        assertEquals("Vs. other Zone 2 rides", narrative.headline)
        // Pool medians over all 6: fat efficiency of [64,66,68,70,72,74] = 69.
        assertEquals(
            "Your fat efficiency score was 80 (vs. 69 in a typical Zone 2 ride) and you burned 20g of fat (vs. 20g). " +
                "You rode 1h0min (vs. 1h0min) at 150 W (vs. 150 W). Your cardiac drift was 4.0% (vs. 4.0%).",
            narrative.text
        )
    }

    @Test
    fun `tagged session with thin history shows not-enough-history`() = runTest {
        val repo = FakeCyclingSessionRepository()
        val current = session(1, 0, "Zone 2")
        repo.sessions.addAll(listOf(current, session(2, 1, "Zone 2")))

        val narrative = assembler(repo).build(current)!!

        assertEquals("Vs. other Zone 2 rides", narrative.headline)
        assertEquals("Not enough history for Zone 2 rides yet.", narrative.text)
    }

    @Test
    fun `tagged session with no history shows not-enough-history`() = runTest {
        val repo = FakeCyclingSessionRepository()
        val current = session(1, 0, "Zone 2")
        repo.sessions.add(current)

        assertEquals("Not enough history for Zone 2 rides yet.", assembler(repo).build(current)!!.text)
    }

    @Test
    fun `untagged session has no narrative`() = runTest {
        val repo = FakeCyclingSessionRepository()
        val current = session(1, 0, tag = null)
        repo.sessions.addAll(listOf(current, session(2, 1, "Zone 2"), session(3, 2, "Zone 2")))

        assertNull(assembler(repo).build(current))
    }

    @Test
    fun `repository error yields no narrative instead of throwing`() = runTest {
        val repo = FailingHistoryRepository()
        val current = session(1, 0, "Zone 2")

        assertNull(assembler(repo).build(current))
    }

    @Test
    fun `intervals are fetched exactly once per build`() = runTest {
        val repo = FakeCyclingSessionRepository()
        val current = session(1, 0, "Zone 2")
        repo.sessions.addAll(listOf(current, session(2, 1, "Zone 2"), session(3, 2, "Zone 2")))
        val intervals = CountingIntervalRepository()

        assembler(repo, intervals).build(current)

        assertEquals(1, intervals.fetches)
    }

    @Test
    fun `a tag backfilled later refreshes the recap`() = runTest(UnconfinedTestDispatcher()) {
        val repo = ReactiveSessionRepository()
        val untagged = session(1, 0, tag = null)
        repo.sessions.addAll(listOf(untagged, session(2, 1, "Zone 2"), session(3, 2, "Zone 2")))
        repo.publish()

        val emissions = mutableListOf<SessionNarrative?>()
        val job = launch { assembler(repo).observe(1).toList(emissions) }

        // Reclassification writes the tag onto the same row.
        repo.sessions[0] = untagged.copy(tag = "Zone 2")
        repo.publish()

        job.cancel()
        assertEquals(2, emissions.size)
        assertNull(emissions[0])
        assertEquals("Vs. other Zone 2 rides", emissions[1]!!.headline)
    }
}

/** A history query that fails, standing in for a database error. */
private class FailingHistoryRepository(
    private val delegate: FakeCyclingSessionRepository = FakeCyclingSessionRepository()
) : CyclingSessionRepository by delegate {
    override suspend fun getAllSessionMetricSamplesBeforeDateForTag(tag: String, epochMs: Long): List<SessionMetricSample> =
        throw RuntimeException("db error")
}

/** Emits from [getSessionsByIds] whenever [publish] is called, like Room's invalidation-driven flows. */
private class ReactiveSessionRepository(
    private val delegate: FakeCyclingSessionRepository = FakeCyclingSessionRepository()
) : CyclingSessionRepository by delegate {
    val sessions get() = delegate.sessions
    private val changes = MutableStateFlow(0)

    fun publish() {
        changes.value++
    }

    override fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSession>> =
        kotlinx.coroutines.flow.flow {
            changes.collect { emit(delegate.sessions.filter { it.id in ids }) }
        }
}

private class CountingIntervalRepository(
    private val delegate: FakeIntervalRepository = FakeIntervalRepository()
) : IntervalRepository by delegate {
    var fetches = 0
    override fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>> {
        fetches++
        return delegate.getIntervalsForSession(sessionId)
    }
}
