package com.velometrics.app.ui.screens.sessiondetail

import androidx.lifecycle.SavedStateHandle
import com.velometrics.app.data.cache.GlobalAverageCacheImpl
import com.velometrics.app.data.cache.RepeatedIntervalsCacheImpl
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import com.velometrics.app.domain.service.SessionComparator
import com.velometrics.app.fakes.FakeBestEffortRepository
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeDropboxSyncCursorRepository
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
        dropboxSyncCursorRepository = dropboxSyncCursorRepository,
        globalAverageCache = GlobalAverageCacheImpl(sessionRepository, scope),
        repeatedIntervalsCache = RepeatedIntervalsCacheImpl(FakeRepeatedIntervalRepository(), scope)
    )

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
    override suspend fun deleteSession(session: CyclingSession) {
        throw RuntimeException("delete failed")
    }
}
