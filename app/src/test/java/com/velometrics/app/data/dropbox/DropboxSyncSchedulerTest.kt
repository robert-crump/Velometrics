package com.velometrics.app.data.dropbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DropboxSyncSchedulerTest {

    private fun auth(isConnected: Boolean) = mockk<DropboxAuthRepository>().also {
        every { it.isConnected } returns MutableStateFlow(isConnected)
    }

    private fun mockWorkManager(requests: MutableList<OneTimeWorkRequest>) = mockk<WorkManager>().also {
        every { it.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        every {
            it.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), capture(requests))
        } returns mockk<Operation>()
    }

    @Test
    fun `user-initiated sync enqueues expedited unique KEEP work requiring network`() {
        val requests = mutableListOf<OneTimeWorkRequest>()
        val workManager = mockWorkManager(requests)

        DropboxSyncScheduler(workManager, auth(isConnected = false)).sync(isUserInitiated = true)

        val spec = requests.single().workSpec
        assertTrue(spec.expedited)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.input.getBoolean(DropboxSyncWorker.KEY_IS_USER_INITIATED, false))
        verify {
            workManager.enqueueUniqueWork(
                DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `auto-sync enqueues non-expedited work only when connected`() {
        val requests = mutableListOf<OneTimeWorkRequest>()
        val workManager = mockWorkManager(requests)

        DropboxSyncScheduler(workManager, auth(isConnected = false)).autoSync()
        assertTrue(requests.isEmpty())

        DropboxSyncScheduler(workManager, auth(isConnected = true)).autoSync()
        val spec = requests.single().workSpec
        assertFalse(spec.expedited)
        assertFalse(spec.input.getBoolean(DropboxSyncWorker.KEY_IS_USER_INITIATED, true))
    }

    /** Runs against a real (test-initialized) WorkManager to check the KEEP policy. */
    @Test
    fun `user sync during an in-flight auto-sync does not enqueue a second sync`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Default test WorkManager never meets the network constraint, so the first sync stays in flight.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        val scheduler = DropboxSyncScheduler(workManager, auth(isConnected = true))

        scheduler.autoSync()
        val first = workManager.getWorkInfosForUniqueWork(DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME).get()
        assertEquals(1, first.size)

        scheduler.sync(isUserInitiated = true)

        val after = workManager.getWorkInfosForUniqueWork(DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME).get()
        assertEquals(listOf(first.single().id), after.map { it.id })
    }
}
