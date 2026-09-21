package com.velometrics.app.data.dropbox

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues [DropboxSyncWorker] runs and reports whether one is in flight, so callers never deal
 * with WorkManager directly.
 */
@Singleton
class DropboxSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val dropboxAuthRepository: DropboxAuthRepository
) {
    /** True while a sync work request is enqueued/running; sourced from WorkManager's persisted store. */
    val isSyncing: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME)
        .map { infos -> infos.any { !it.state.isFinished } }

    /**
     * Enqueues a sync so it survives backgrounding and process death. [ExistingWorkPolicy.KEEP]
     * drops the request if a sync is already in flight. The worker also reclusters
     * unconditionally (#192).
     */
    fun sync(isUserInitiated: Boolean) {
        val request = OneTimeWorkRequestBuilder<DropboxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(DropboxSyncWorker.KEY_IS_USER_INITIATED to isUserInitiated))
            .apply {
                if (isUserInitiated) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        workManager.enqueueUniqueWork(
            DropboxSyncWorker.DROPBOX_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Silent background sync (e.g. on app open); no-op unless Dropbox is connected. */
    fun autoSync() {
        if (!dropboxAuthRepository.isConnected.value) return
        sync(isUserInitiated = false)
    }
}
