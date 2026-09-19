package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.model.RideRevealContent
import kotlinx.coroutines.flow.Flow

/** One file to import. [read] is lazy so a batch holds only one file's bytes at a time; null means unreadable. */
class ImportSource(val fileName: String, val read: suspend () -> ByteArray?)

sealed interface ImportProgress {
    data class Importing(val current: Int, val total: Int, val fileName: String) : ImportProgress
    data class Finished(val results: List<ImportResult>, val reveal: RideRevealContent?) : ImportProgress
}

/** What a tracked batch produced: every file's result and, if its newest ride is genuinely new, the reveal. */
data class ImportBatch(val results: List<ImportResult>, val reveal: RideRevealContent?)

/** Whether [RideLifecycle.trackImports] waits for reclustering (a background worker) or launches it and moves on (UI). */
enum class ReclusterMode { Background, Await }

/**
 * Owns the obligations that surround adding or removing rides, so callers (Home, Session Detail,
 * the Dropbox worker) don't each re-implement them. See #203.
 */
interface RideLifecycle {
    /**
     * Imports [sources] one at a time as a single batch: captures the reveal baseline, imports each
     * file, evaluates the reveal, and reclusters. A file too short to be a ride is offered to
     * [onSmallFile]; returning true imports it anyway, false skips it. An unreadable file is
     * recorded as an error and the batch continues.
     */
    fun import(
        sources: List<ImportSource>,
        onSmallFile: suspend (ImportResult.SmallFile) -> Boolean
    ): Flow<ImportProgress>

    /**
     * The one place the import post-conditions live. Captures the reveal baseline, runs [block]
     * (which imports rides and returns their results), then evaluates the reveal. Both the file
     * picker ([import]) and the Dropbox worker go through this. Reclusters in a `finally`, so rides
     * imported before a failure or cancellation are still clustered.
     */
    suspend fun trackImports(
        reclusterMode: ReclusterMode,
        block: suspend () -> List<ImportResult>
    ): ImportBatch

    /** Refreshes every clustering. Failures are logged per clusterer and never propagate. */
    suspend fun recluster()

    /**
     * Deletes [sessionIds] atomically, then invalidates the saved Dropbox sync cursor so a
     * previously synced file is reconsidered on the next sync (the delta cursor has no visibility
     * into local deletions). Deliberately does NOT recluster: that stays on the lazy
     * pull-to-refresh path (#187/#192).
     */
    suspend fun delete(sessionIds: List<Long>): DeleteResult
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object Failed : DeleteResult
}
