package com.velometrics.app.data.dropbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.service.ReclusterMode
import com.velometrics.app.domain.service.RideLifecycle
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the Dropbox sync as a WorkManager job so it no longer depends on a ViewModel scope.
 *
 * Mirrors the former `HomeViewModel.syncDropbox()` call sequence. The outcome goes to
 * [DropboxSyncOutcomeStore] (Worker `Data` can't carry reveal content). Transient failures return
 * success on purpose: no `Result.retry()`, the next sync trigger is the retry.
 *
 * Reclusters unconditionally at the end (#192), on every path, so cluster badges also reflect a
 * ride deleted since the last run.
 */
@HiltWorker
class DropboxSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dropboxSyncService: DropboxSyncService,
    private val dropboxAuthRepository: DropboxAuthRepository,
    private val rideLifecycle: RideLifecycle,
    private val outcomeStore: DropboxSyncOutcomeStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isUserInitiated = inputData.getBoolean(KEY_IS_USER_INITIATED, true)

        if (!dropboxAuthRepository.isConnected.value) {
            if (isUserInitiated) {
                outcomeStore.publish(DropboxSyncOutcome.Message("Connect Dropbox in Settings to sync rides"))
            }
            return skipSync()
        }
        if (dropboxAuthRepository.needsReauth.value) return skipSync()

        var syncResult: DropboxSyncResult = DropboxSyncResult.TransientFailure
        val batch = rideLifecycle.trackImports(ReclusterMode.Await) {
            syncResult = dropboxSyncService.sync()
            (syncResult as? DropboxSyncResult.Completed)?.importResults.orEmpty()
        }
        return when (val result = syncResult) {
            is DropboxSyncResult.Completed -> {
                if (batch.reveal != null) {
                    outcomeStore.publish(DropboxSyncOutcome.Reveal(batch.reveal))
                } else if (isUserInitiated || result.importResults.isNotEmpty()) {
                    outcomeStore.publish(DropboxSyncOutcome.Message(buildDropboxSyncMessage(result.importResults)))
                }
                Result.success()
            }
            DropboxSyncResult.TransientFailure -> Result.success()
            DropboxSyncResult.NeedsReauth -> {
                dropboxAuthRepository.markNeedsReauth()
                Result.failure()
            }
        }
    }

    /** Nothing to sync, but still recluster so cluster badges reflect a ride deleted since the last run (#192). */
    private suspend fun skipSync(): Result {
        rideLifecycle.recluster()
        return Result.success()
    }

    companion object {
        const val DROPBOX_SYNC_WORK_NAME = "dropbox_sync"
        const val KEY_IS_USER_INITIATED = "is_user_initiated"
        private const val TAG = "DropboxSyncWorker"
    }
}

internal fun buildDropboxSyncMessage(results: List<ImportResult>): String {
    val successCount = results.count { it is ImportResult.Success }
    val errors = results.filterIsInstance<ImportResult.Error>()
    val smallFileCount = results.count { it is ImportResult.SmallFile }

    val parts = mutableListOf<String>()
    if (successCount > 0) {
        parts.add("Imported $successCount new ride${if (successCount == 1) "" else "s"}")
    }
    if (errors.isNotEmpty()) {
        parts.add("${errors.size} failed: ${errors.first().message}")
    }
    if (smallFileCount > 0) {
        parts.add("$smallFileCount skipped (too short)")
    }

    return if (parts.isEmpty()) "No new rides found in Dropbox" else parts.joinToString(", ")
}
