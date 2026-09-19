package com.velometrics.app.data.dropbox

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.model.RideRevealContent
import com.velometrics.app.domain.service.IntervalClusterer
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.domain.service.RideLifecycle
import com.velometrics.app.domain.service.RideLifecycleImpl
import com.velometrics.app.domain.service.RouteClusterer
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeDropboxSyncCursorRepository
import com.velometrics.app.fakes.FakeFitImportService
import com.velometrics.app.domain.service.RideRevealEvaluator
import com.velometrics.app.domain.service.RouteClusteringService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DropboxSyncWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DropboxSyncOutcomeStore
    private lateinit var auth: DropboxAuthRepository
    private lateinit var syncService: DropboxSyncService
    private lateinit var evaluator: RideRevealEvaluator
    private lateinit var routeClustering: RouteClusteringService
    private lateinit var intervalClustering: IntervalClusteringService
    private val isConnected = MutableStateFlow(true)
    private val needsReauth = MutableStateFlow(false)

    private val reveal = RideRevealContent(
        sessionId = 7L, headline = "New PR", distanceKm = 40.0, netDurationSec = 3600, elevationGainM = null
    )

    @Before
    fun setUp() {
        store = DropboxSyncOutcomeStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("outcome.preferences_pb").also { it.delete() }
            }
        )
        auth = mockk()
        every { auth.isConnected } returns isConnected
        every { auth.needsReauth } returns needsReauth
        every { auth.markNeedsReauth() } returns Unit
        syncService = mockk()
        coEvery { syncService.sync() } returns DropboxSyncResult.Completed(emptyList())
        evaluator = mockk()
        coEvery { evaluator.captureBaseline() } returns null
        coEvery { evaluator.evaluate(any(), any()) } returns null
        routeClustering = mockk()
        coEvery { routeClustering.runClustering() } returns Unit
        intervalClustering = mockk()
        coEvery { intervalClustering.runClustering() } returns Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun rideLifecycle(): RideLifecycle {
        val sessions = FakeCyclingSessionRepository()
        return RideLifecycleImpl(
            sessions, FakeDropboxSyncCursorRepository(), FakeFitImportService(sessions), evaluator,
            setOf(RouteClusterer(routeClustering), IntervalClusterer(intervalClustering)), scope
        )
    }

    private fun run(isUserInitiated: Boolean = true): ListenableWorker.Result {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<DropboxSyncWorker>(
            context,
            inputData = workDataOf(DropboxSyncWorker.KEY_IS_USER_INITIATED to isUserInitiated)
        ).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context, workerClassName: String, workerParameters: WorkerParameters
            ) = DropboxSyncWorker(
                appContext, workerParameters, syncService, auth, rideLifecycle(), store
            )
        }).build()
        return runBlocking { worker.doWork() }
    }

    private fun outcome() = runBlocking { store.outcome.first() }

    private fun imported() = listOf(mockk<ImportResult.Success>())

    @Test
    fun completedWithRevealPublishesReveal() {
        coEvery { syncService.sync() } returns DropboxSyncResult.Completed(imported())
        coEvery { evaluator.evaluate(any(), any()) } returns reveal

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(DropboxSyncOutcome.Reveal(reveal), outcome())
    }

    @Test
    fun completedWithNewRidesPublishesMessageEvenWhenNotUserInitiated() {
        coEvery { syncService.sync() } returns DropboxSyncResult.Completed(imported())

        assertEquals(ListenableWorker.Result.success(), run(isUserInitiated = false))
        assertEquals(DropboxSyncOutcome.Message("Imported 1 new ride"), outcome())
    }

    @Test
    fun noNewRidesIsMessageWhenUserInitiated() {
        assertEquals(ListenableWorker.Result.success(), run(isUserInitiated = true))
        assertEquals(DropboxSyncOutcome.Message("No new rides found in Dropbox"), outcome())
    }

    @Test
    fun noNewRidesIsSuppressedWhenNotUserInitiated() {
        assertEquals(ListenableWorker.Result.success(), run(isUserInitiated = false))
        assertNull(outcome())
    }

    @Test
    fun transientFailureSucceedsSilently() {
        coEvery { syncService.sync() } returns DropboxSyncResult.TransientFailure

        assertEquals(ListenableWorker.Result.success(), run())
        assertNull(outcome())
    }

    @Test
    fun needsReauthMarksAndFails() {
        coEvery { syncService.sync() } returns DropboxSyncResult.NeedsReauth

        assertEquals(ListenableWorker.Result.failure(), run())
        verify { auth.markNeedsReauth() }
        assertNull(outcome())
    }

    @Test
    fun disconnectedSkipsSyncButReclustersAndMessagesUserInitiated() {
        isConnected.value = false

        assertEquals(ListenableWorker.Result.success(), run(isUserInitiated = true))
        assertEquals(DropboxSyncOutcome.Message("Connect Dropbox in Settings to sync rides"), outcome())
        coVerify(exactly = 0) { syncService.sync() }
        coVerify(exactly = 1) { routeClustering.runClustering() }
        coVerify(exactly = 1) { intervalClustering.runClustering() }
    }

    @Test
    fun alreadyNeedingReauthSkipsSyncSilently() {
        needsReauth.value = true

        assertEquals(ListenableWorker.Result.success(), run())
        assertNull(outcome())
        coVerify(exactly = 0) { syncService.sync() }
    }

    @Test
    fun reclustersAfterCompletedSync() {
        run()
        coVerify(exactly = 1) { routeClustering.runClustering() }
        coVerify(exactly = 1) { intervalClustering.runClustering() }
    }

    @Test
    fun clusteringFailureDoesNotFailWorkOrSkipOtherClustering() {
        coEvery { routeClustering.runClustering() } throws IllegalStateException("boom")

        assertEquals(ListenableWorker.Result.success(), run())
        coVerify(exactly = 1) { intervalClustering.runClustering() }
    }
}
