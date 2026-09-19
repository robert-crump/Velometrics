package com.velometrics.app.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SmokeTestWorkerTest {
    @Test
    fun enqueuedWorkerRunsAndSucceeds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        val request = OneTimeWorkRequestBuilder<SmokeTestWorker>().build()
        val wm = WorkManager.getInstance(context)
        wm.enqueue(request).result.get()

        assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(request.id).get()?.state)
    }
}
