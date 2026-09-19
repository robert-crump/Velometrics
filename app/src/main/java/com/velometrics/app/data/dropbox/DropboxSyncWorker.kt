package com.velometrics.app.data.dropbox

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.domain.service.RideRevealEvaluator
import com.velometrics.app.domain.service.RouteClusteringService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

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
    private val rideRevealEvaluator: RideRevealEvaluator,
    private val routeClusteringService: RouteClusteringService,
    private val intervalClusteringService: IntervalClusteringService,
    private val outcomeStore: DropboxSyncOutcomeStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isUserInitiated = inputData.getBoolean(KEY_IS_USER_INITIATED, true)
        return try {
            sync(isUserInitiated)
        } finally {
            recluster()
        }
    }

    private suspend fun sync(isUserInitiated: Boolean): Result {
        if (!dropboxAuthRepository.isConnected.value) {
            if (isUserInitiated) {
                outcomeStore.publish(DropboxSyncOutcome.Message("Connect Dropbox in Settings to sync rides"))
            }
            return Result.success()
        }
        if (dropboxAuthRepository.needsReauth.value) return Result.success()

        val revealBaseline = rideRevealEvaluator.captureBaseline()
        return when (val result = dropboxSyncService.sync()) {
            is DropboxSyncResult.Completed -> {
                val reveal = rideRevealEvaluator.evaluate(result.importResults, revealBaseline)
                if (reveal != null) {
                    outcomeStore.publish(DropboxSyncOutcome.Reveal(reveal))
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

    private suspend fun recluster() {
        try {
            routeClusteringService.runClustering()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Route clustering failed", e)
        }
        try {
            intervalClusteringService.runClustering()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Interval clustering failed", e)
        }
    }

    companion object {
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
