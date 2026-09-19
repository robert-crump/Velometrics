package com.velometrics.app.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Trivial worker verifying the WorkManager + Hilt-Work wiring; also a template for real workers. */
@HiltWorker
class SmokeTestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d(TAG, "SmokeTestWorker ran")
        return Result.success()
    }

    private companion object {
        const val TAG = "SmokeTestWorker"
    }
}
