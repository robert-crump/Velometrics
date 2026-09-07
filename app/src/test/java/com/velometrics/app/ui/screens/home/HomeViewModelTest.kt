package com.velometrics.app.ui.screens.home

import android.content.Context
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncResult
import com.velometrics.app.data.dropbox.DropboxSyncService
import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.domain.service.RideRevealEvaluator
import com.velometrics.app.domain.service.RouteClusteringService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
        intervalClusteringService: IntervalClusteringService
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
            sessionRepository = FakeCyclingSessionRepository(),
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
}
