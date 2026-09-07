package com.velometrics.app.ui.screens.home

import android.content.Context
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncResult
import com.velometrics.app.data.dropbox.DropboxSyncService
import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.domain.service.RideRevealEvaluator
import com.velometrics.app.domain.service.RouteClusteringService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers #192: pull-to-refresh (`syncDropbox`) must recluster unconditionally, so Repeated
 * Routes/Intervals badges stay correct after a ride delete even when Dropbox is disconnected or
 * finds nothing new.
 *
 * `syncDropbox`'s work runs on the real `Dispatchers.IO` (not a swappable test dispatcher), so
 * these tests wait for it via a real, short-timeout [CountDownLatch] rather than virtual time.
 */
class HomeViewModelTest {

    // Real, immediate-on-caller-thread dispatcher: just needs to exist for viewModelScope's
    // lazy initialization; syncDropbox's actual work runs on the real Dispatchers.IO regardless.
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        isConnected: MutableStateFlow<Boolean>,
        needsReauth: MutableStateFlow<Boolean> = MutableStateFlow(false),
        syncResult: DropboxSyncResult = DropboxSyncResult.Completed(emptyList()),
        routeClusteringService: RouteClusteringService,
        intervalClusteringService: IntervalClusteringService,
        sessionRepository: CyclingSessionRepository = FakeCyclingSessionRepository()
    ): HomeViewModel {
        val dropboxAuthRepository = mockk<DropboxAuthRepository>()
        every { dropboxAuthRepository.isConnected } returns isConnected
        every { dropboxAuthRepository.needsReauth } returns needsReauth
        every { dropboxAuthRepository.markNeedsReauth() } returns Unit

        val dropboxSyncService = mockk<DropboxSyncService>()
        coEvery { dropboxSyncService.sync() } returns syncResult

        val rideRevealEvaluator = mockk<RideRevealEvaluator>()
        coEvery { rideRevealEvaluator.captureBaseline() } returns null
        coEvery { rideRevealEvaluator.evaluate(any(), any()) } returns null

        return HomeViewModel(
            sessionRepository = sessionRepository,
            fitImportService = mockk<FitImportService>(relaxed = true),
            dropboxSyncService = dropboxSyncService,
            dropboxAuthRepository = dropboxAuthRepository,
            routeClusteringService = routeClusteringService,
            intervalClusteringService = intervalClusteringService,
            rideRevealEvaluator = rideRevealEvaluator,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            context = mockk<Context>(relaxed = true)
        )
    }

    /** Builds clustering-service mocks whose `runClustering()` counts down [latch] when invoked. */
    private fun clusteringMocks(latch: CountDownLatch): Pair<RouteClusteringService, IntervalClusteringService> {
        val routeClusteringService = mockk<RouteClusteringService>()
        coEvery { routeClusteringService.runClustering() } answers { latch.countDown() }
        val intervalClusteringService = mockk<IntervalClusteringService>()
        coEvery { intervalClusteringService.runClustering() } answers { latch.countDown() }
        return routeClusteringService to intervalClusteringService
    }

    @Test
    fun `syncDropbox reclusters even when Dropbox is disconnected`() {
        val latch = CountDownLatch(2)
        val (routeClusteringService, intervalClusteringService) = clusteringMocks(latch)
        // Starts disconnected so init's auto-sync no-ops and doesn't consume the latch itself.
        val vm = buildViewModel(
            isConnected = MutableStateFlow(false),
            routeClusteringService = routeClusteringService,
            intervalClusteringService = intervalClusteringService
        )

        vm.syncDropbox(isUserInitiated = true)

        assertTrue("recluster was not called on both services", latch.await(5, TimeUnit.SECONDS))
        assertEquals("Connect Dropbox in Settings to sync rides", vm.dropboxSyncMessage.value)
    }

    @Test
    fun `syncDropbox reclusters when connected but no new files were found`() {
        val latch = CountDownLatch(2)
        val (routeClusteringService, intervalClusteringService) = clusteringMocks(latch)
        val isConnected = MutableStateFlow(false)
        val vm = buildViewModel(
            isConnected = isConnected,
            syncResult = DropboxSyncResult.Completed(emptyList()),
            routeClusteringService = routeClusteringService,
            intervalClusteringService = intervalClusteringService
        )
        // Flip to connected only after construction, so init's auto-sync (which read isConnected
        // while still false) doesn't consume the latch on top of the call under test.
        isConnected.value = true

        vm.syncDropbox(isUserInitiated = true)

        assertTrue("recluster was not called on both services", latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `syncDropbox reclusters when Dropbox needs reauth`() {
        val latch = CountDownLatch(2)
        val (routeClusteringService, intervalClusteringService) = clusteringMocks(latch)
        val isConnected = MutableStateFlow(false)
        val needsReauth = MutableStateFlow(false)
        val vm = buildViewModel(
            isConnected = isConnected,
            needsReauth = needsReauth,
            routeClusteringService = routeClusteringService,
            intervalClusteringService = intervalClusteringService
        )
        isConnected.value = true
        needsReauth.value = true

        vm.syncDropbox(isUserInitiated = true)

        assertTrue("recluster was not called on both services", latch.await(5, TimeUnit.SECONDS))
    }

    // ------------------------------------------------------------------------------------------
    // #194: Home multiselect bulk delete
    // ------------------------------------------------------------------------------------------

    /** Builds a [HomeViewModel] with relaxed Dropbox/clustering collaborators, disconnected so
     * init's auto-sync no-ops — these tests only exercise selection state and delete wiring. */
    private fun buildViewModelForSelection(
        sessionRepository: CyclingSessionRepository = FakeCyclingSessionRepository()
    ): HomeViewModel = buildViewModel(
        isConnected = MutableStateFlow(false),
        routeClusteringService = mockk<RouteClusteringService>(relaxed = true),
        intervalClusteringService = mockk<IntervalClusteringService>(relaxed = true),
        sessionRepository = sessionRepository
    )

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

    /** Wraps a [FakeCyclingSessionRepository]'s `deleteSessions`, counting down [latch] once the
     * call completes (or is about to throw), since `deleteSelectedSessions` runs on the real
     * `Dispatchers.IO`, same as `syncDropbox` above. */
    private class LatchedDeleteSessionsRepository(
        val delegate: FakeCyclingSessionRepository = FakeCyclingSessionRepository(),
        private val latch: CountDownLatch,
        private val shouldFail: Boolean = false
    ) : CyclingSessionRepository by delegate {
        override suspend fun deleteSessions(ids: List<Long>) {
            if (shouldFail) {
                latch.countDown()
                throw RuntimeException("simulated bulk-delete failure")
            }
            delegate.deleteSessions(ids)
            latch.countDown()
        }
    }

    @Test
    fun `enterSelectionMode turns on selection mode with only the long-pressed session selected`() {
        val vm = buildViewModelForSelection()

        vm.enterSelectionMode(sessionId = 1L)

        assertTrue(vm.selectionMode.value)
        assertEquals(setOf(1L), vm.selectedSessionIds.value)
    }

    @Test
    fun `toggleSessionSelected adds and removes ids, and no-ops outside selection mode`() {
        val vm = buildViewModelForSelection()

        // Outside selection mode: no-op.
        vm.toggleSessionSelected(1L)
        assertTrue(vm.selectedSessionIds.value.isEmpty())

        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)
        assertEquals(setOf(1L, 2L), vm.selectedSessionIds.value)

        // Toggling an already-selected id deselects it (multiselect, not single-select).
        vm.toggleSessionSelected(1L)
        assertEquals(setOf(2L), vm.selectedSessionIds.value)
    }

    @Test
    fun `exitSelectionMode clears the selection mode flag and every selected id`() {
        val vm = buildViewModelForSelection()
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.exitSelectionMode()

        assertFalse(vm.selectionMode.value)
        assertTrue(vm.selectedSessionIds.value.isEmpty())
    }

    @Test
    fun `deleteSelectedSessions deletes every selected session and exits selection mode on success`() {
        val latch = CountDownLatch(1)
        val repo = LatchedDeleteSessionsRepository(latch = latch)
        repo.delegate.sessions.add(buildSession(1L))
        repo.delegate.sessions.add(buildSession(2L))
        repo.delegate.sessions.add(buildSession(3L))
        val vm = buildViewModelForSelection(sessionRepository = repo)
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.deleteSelectedSessions()

        assertTrue("deleteSessions was not invoked", latch.await(5, TimeUnit.SECONDS))
        // exitSelectionMode() itself is synchronous but runs right after the suspend delete
        // completes on the IO dispatcher, so wait briefly for that state update to land.
        val exited = waitFor(5, TimeUnit.SECONDS) { !vm.selectionMode.value }
        assertTrue("selection mode was not exited after a successful delete", exited)
        assertTrue(vm.selectedSessionIds.value.isEmpty())
        assertTrue(repo.delegate.sessions.none { it.id == 1L || it.id == 2L })
        assertTrue(repo.delegate.sessions.any { it.id == 3L })
        assertEquals(null, vm.deleteError.value)
    }

    @Test
    fun `deleteSelectedSessions surfaces an error and keeps selection mode active on failure`() {
        val latch = CountDownLatch(1)
        val repo = LatchedDeleteSessionsRepository(latch = latch, shouldFail = true)
        repo.delegate.sessions.add(buildSession(1L))
        repo.delegate.sessions.add(buildSession(2L))
        val vm = buildViewModelForSelection(sessionRepository = repo)
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.deleteSelectedSessions()

        assertTrue("deleteSessions was not invoked", latch.await(5, TimeUnit.SECONDS))
        val errorShown = waitFor(5, TimeUnit.SECONDS) { vm.deleteError.value != null }
        assertTrue("expected an error message after a failed delete", errorShown)
        assertEquals("Couldn't delete rides", vm.deleteError.value)
        assertTrue("selection mode must stay active so the user can retry", vm.selectionMode.value)
        assertEquals(setOf(1L, 2L), vm.selectedSessionIds.value)
        assertTrue(repo.delegate.sessions.any { it.id == 1L } && repo.delegate.sessions.any { it.id == 2L })
    }

    /** Polls [condition] on the test thread until it's true or [timeout] elapses. */
    private fun waitFor(timeout: Long, unit: TimeUnit, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
