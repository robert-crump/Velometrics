package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class RideLifecycleImpl @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val dropboxSyncCursorRepository: DropboxSyncCursorRepository,
    private val fitImportService: FitImportService,
    private val rideRevealEvaluator: RideRevealEvaluator,
    private val clusterers: Set<@JvmSuppressWildcards RideClusterer>,
    @ApplicationScope private val appScope: CoroutineScope,
) : RideLifecycle {

    private val importMutex = Mutex()

    override fun import(
        sources: List<ImportSource>,
        onSmallFile: suspend (ImportResult.SmallFile) -> Boolean
    ): Flow<ImportProgress> = flow {
        val batch = trackImports(ReclusterMode.Background) {
            val results = mutableListOf<ImportResult>()
            sources.forEachIndexed { index, source ->
                emit(ImportProgress.Importing(index + 1, sources.size, source.fileName))
                val bytes = source.read()
                if (bytes == null) {
                    results += ImportResult.Error("Could not read file: ${source.fileName}")
                    return@forEachIndexed
                }
                var result = fitImportService.importFile(source.fileName, bytes)
                if (result is ImportResult.SmallFile) {
                    if (!onSmallFile(result)) return@forEachIndexed
                    result = fitImportService.importFile(source.fileName, bytes, forceImport = true)
                }
                results += result
            }
            results
        }
        emit(ImportProgress.Finished(batch.results, batch.reveal))
    }

    override suspend fun trackImports(
        reclusterMode: ReclusterMode,
        block: suspend () -> List<ImportResult>
    ): ImportBatch {
        try {
            // One batch at a time across Home and the Dropbox worker: overlapping batches would
            // corrupt each other's reveal baseline. Released before reclustering below.
            return importMutex.withLock {
                val baseline = rideRevealEvaluator.captureBaseline()
                val results = block()
                ImportBatch(results, rideRevealEvaluator.evaluate(results, baseline))
            }
        } finally {
            when (reclusterMode) {
                ReclusterMode.Await -> recluster()
                ReclusterMode.Background -> appScope.launch { recluster() }
            }
        }
    }

    override suspend fun recluster() {
        for (clusterer in clusterers) {
            try {
                clusterer.recluster()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "${clusterer.name} clustering failed", e)
            }
        }
    }

    override suspend fun delete(sessionIds: List<Long>): DeleteResult {
        try {
            sessionRepository.deleteSessions(sessionIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed", e)
            return DeleteResult.Failed
        }
        // The rides are gone at this point; a stale cursor is a minor problem, not a failed delete.
        try {
            dropboxSyncCursorRepository.invalidateSyncCursor()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't invalidate the Dropbox sync cursor after delete", e)
        }
        return DeleteResult.Deleted
    }

    private companion object {
        const val TAG = "RideLifecycle"
    }
}
