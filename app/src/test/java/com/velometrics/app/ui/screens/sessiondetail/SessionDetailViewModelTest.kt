package com.velometrics.app.ui.screens.sessiondetail

import io.mockk.mockk
import io.mockk.every
import androidx.lifecycle.SavedStateHandle
import com.velometrics.app.data.cache.GlobalAverageCacheImpl
import com.velometrics.app.data.cache.RepeatedIntervalsCacheImpl
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import com.velometrics.app.domain.service.SessionComparator
import com.velometrics.app.domain.service.SessionNarrativeAssembler
import com.velometrics.app.fakes.FakeBestEffortRepository
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeDropboxSyncCursorRepository
import com.velometrics.app.fakes.RideLifecycleFixture
import com.velometrics.app.fakes.FakeIntervalRepository
import com.velometrics.app.fakes.FakeRepeatedIntervalRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildSession(id: Long) = CyclingSession(
        id = id,
        fileName = "ride-$id.fit",
        fileSha1 = "sha-$id",
        sessionStart = Instant.parse("2026-01-01T00:00:00Z"),
        sessionEnd = Instant.parse("2026-01-01T01:00:00Z"),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = 30.0,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = false
    )

    private fun buildViewModel(
        sessionRepository: CyclingSessionRepository,
        sessionId: Long,
        scope: CoroutineScope,
        dropboxSyncCursorRepository: DropboxSyncCursorRepository = FakeDropboxSyncCursorRepository()
    ) = SessionDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId)),
        sessionRepository = sessionRepository,
        intervalRepository = FakeIntervalRepository(),
        bestEffortRepository = FakeBestEffortRepository(),
        sessionComparator = SessionComparator(sessionRepository),
        sessionNarrativeAssembler = SessionNarrativeAssembler(
            sessionRepository, FakeIntervalRepository(), SessionComparator(sessionRepository),
            com.velometrics.app.fakes.FakeRepeatedRoutesCache()
        ),
        rideLifecycle = RideLifecycleFixture().lifecycle(sessionRepository, dropboxSyncCursorRepository),
        userSettingsRepository = mockk<com.velometrics.app.data.preferences.UserSettingsRepository> {
            every { maxHr } returns kotlinx.coroutines.flow.flowOf(190)
        },
        globalAverageCache = GlobalAverageCacheImpl(sessionRepository, scope),
        repeatedIntervalsCache = RepeatedIntervalsCacheImpl(FakeRepeatedIntervalRepository(), scope)
    )

    @Test
    fun `narrative exposes the Zone 2 recap headline and stat lines`() = runTest(testDispatcher) {
        val repo = FakeCyclingSessionRepository()
        fun zone2(id: Long, start: String, fatEfficiency: Int, fatG: Double, netSec: Int, power: Int, drift: Double) =
            buildSession(id).copy(
                sessionStart = Instant.parse(start),
                sessionEnd = Instant.parse(start).plusSeconds(netSec.toLong()),
                netDurationSec = netSec,
                hasPower = true,
                averagePower = power,
                fatBurnedGrams = fatG,
                carbsBurnedGrams = 50.0,
                fatEfficiencyScore = fatEfficiency,
                cardiacDriftPercent = drift,
                tag = "Zone 2"
            )
        repo.sessions.add(zone2(10L, "2026-02-01T08:00:00Z", 80, 20.0, 3600, 150, 4.0))
        repo.sessions.add(zone2(11L, "2026-01-01T08:00:00Z", 70, 10.0, 1800, 120, 3.0))
        repo.sessions.add(zone2(12L, "2026-01-05T08:00:00Z", 70, 10.0, 1800, 120, 3.0))
        val vm = buildViewModel(repo, sessionId = 10L, scope = backgroundScope)
        val collector = launch { vm.narrative.collect { } }
        advanceUntilIdle()

        val narrative = vm.narrative.value!!
        assertEquals("vs. Zone 2", narrative.headline)
        assertEquals(
            listOf("80 fat efficiency (vs. 70)", "20 g fat (vs. 10 g)", "1h0min (vs. 30min)", "150 W (vs. 120 W)", "4.0% (vs. 3.0%)"),
            narrative.lines
        )
        collector.cancel()
    }

    @Test
    fun `narrative is null for an untagged ride`() = runTest(testDispatcher) {
        val repo = FakeCyclingSessionRepository()
        repo.sessions.add(buildSession(id = 20L))
        val vm = buildViewModel(repo, sessionId = 20L, scope = backgroundScope)
        val collector = launch { vm.narrative.collect { } }
        advanceUntilIdle()

        assertNull(vm.narrative.value)
        collector.cancel()
    }

    @Test
    fun `deleteRide calls the repository and triggers navigate-back on success`() = runTest(testDispatcher) {
        val repo = FakeCyclingSessionRepository()
        repo.sessions.add(buildSession(id = 1L))
        val vm = buildViewModel(repo, sessionId = 1L, scope = backgroundScope)
        advanceUntilIdle()

        var navigated = false
        val collector = launch { vm.navigateBack.collect { navigated = true } }

        vm.deleteRide()
        advanceUntilIdle()

        assertTrue(navigated)
        assertTrue(repo.sessions.none { it.id == 1L })
        assertNull(vm.deleteError.value)
        collector.cancel()
    }

    @Test
    fun `deleteRide emits an error and does not navigate on repository failure`() = runTest(testDispatcher) {
        val repo = FailingDeleteCyclingSessionRepository()
        repo.delegate.sessions.add(buildSession(id = 2L))
        val vm = buildViewModel(repo, sessionId = 2L, scope = backgroundScope)
        advanceUntilIdle()

        var navigated = false
        val collector = launch { vm.navigateBack.collect { navigated = true } }

        vm.deleteRide()
        advanceUntilIdle()

        assertFalse(navigated)
        assertEquals("Couldn't delete ride", vm.deleteError.value)
        assertTrue(repo.delegate.sessions.any { it.id == 2L })
        collector.cancel()
    }

    @Test
    fun `deleteRide invalidates the Dropbox sync cursor on success so the ride can be re-synced`() =
        runTest(testDispatcher) {
            val repo = FakeCyclingSessionRepository()
            repo.sessions.add(buildSession(id = 3L))
            val cursorRepository = FakeDropboxSyncCursorRepository()
            val vm = buildViewModel(repo, sessionId = 3L, scope = backgroundScope, dropboxSyncCursorRepository = cursorRepository)
            advanceUntilIdle()

            vm.deleteRide()
            advanceUntilIdle()

            assertTrue(cursorRepository.invalidated)
        }

    @Test
    fun `deleteRide does not touch the Dropbox sync cursor on repository failure`() = runTest(testDispatcher) {
        val repo = FailingDeleteCyclingSessionRepository()
        repo.delegate.sessions.add(buildSession(id = 4L))
        val cursorRepository = FakeDropboxSyncCursorRepository()
        val vm = buildViewModel(repo, sessionId = 4L, scope = backgroundScope, dropboxSyncCursorRepository = cursorRepository)
        advanceUntilIdle()

        vm.deleteRide()
        advanceUntilIdle()

        assertFalse(cursorRepository.invalidated)
    }
}

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

/** Delegates everything to a real [FakeCyclingSessionRepository] except a failing delete. */
private class FailingDeleteCyclingSessionRepository(
    val delegate: FakeCyclingSessionRepository = FakeCyclingSessionRepository()
) : CyclingSessionRepository by delegate {
    override suspend fun deleteSessions(ids: List<Long>) {
        throw RuntimeException("delete failed")
    }
}
