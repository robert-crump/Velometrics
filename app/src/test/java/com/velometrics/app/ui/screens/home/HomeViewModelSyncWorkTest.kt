package com.velometrics.app.ui.screens.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncOutcomeStore
import com.velometrics.app.data.dropbox.DropboxSyncWorker
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.RideLifecycleFixture
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs [HomeViewModel] against a real (test-initialized) WorkManager to check the KEEP policy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HomeViewModelSyncWorkTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pull-to-refresh during an in-flight auto-sync does not enqueue a second sync`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Default test WorkManager never meets the network constraint, so the first sync stays in flight.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)

        val auth = mockk<DropboxAuthRepository>()
        every { auth.isConnected } returns MutableStateFlow(true)
        val outcomeStore = mockk<DropboxSyncOutcomeStore>()
        every { outcomeStore.outcome } returns emptyFlow()

        val fixture = RideLifecycleFixture()
        val vm = HomeViewModel(
            sessionRepository = FakeCyclingSessionRepository(),
            rideLifecycle = fixture.lifecycle(),
            importSourceReader = mockk(relaxed = true),
            workManager = workManager,
            dropboxSyncOutcomeStore = outcomeStore,
            dropboxAuthRepository = auth
        ) // init auto-sync enqueues the first request

        val first = workManager.getWorkInfosForUniqueWork(DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME).get()
        assertEquals(1, first.size)

        vm.syncDropbox(isUserInitiated = true)

        val after = workManager.getWorkInfosForUniqueWork(DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME).get()
        assertEquals(listOf(first.single().id), after.map { it.id })
    }
}
