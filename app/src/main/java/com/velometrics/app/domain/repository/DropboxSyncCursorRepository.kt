package com.velometrics.app.domain.repository

/**
 * Narrow seam over the saved Dropbox delta-sync cursor, just for the "a ride delete should make
 * Dropbox reconsider that file" concern (#193/#194). Kept separate from
 * `DropboxSyncService`/`DropboxAuthRepository` themselves — both carry hard Android/Dropbox-SDK
 * dependencies that can't be constructed in a local JVM unit test — so a ViewModel that only
 * needs to invalidate the cursor on delete can depend on something fakeable instead.
 */
interface DropboxSyncCursorRepository {
    /**
     * Clears the saved delta-sync cursor so the next `DropboxSyncService.sync()` performs a full
     * `list_folder` instead of a delta continue. Dropbox's delta cursor has no visibility into
     * local deletions — once a `.fit` file has been synced, the cursor moves past it permanently,
     * so a ride deleted locally would otherwise never be reconsidered for import again.
     */
    fun invalidateSyncCursor()
}
