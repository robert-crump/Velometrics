package com.velometrics.app.data.dropbox

import android.util.Log
import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.oauth.DbxOAuthError
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.data.preferences.UserSettingsRepository
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of a [DropboxSyncService.sync] attempt. */
sealed class DropboxSyncResult {
    /** The sync ran to completion (possibly with per-file errors in [importResults]). */
    data class Completed(val importResults: List<ImportResult>) : DropboxSyncResult()

    /** A transient failure (no network, rate limiting, server error) - retry next time. */
    data object TransientFailure : DropboxSyncResult()

    /** A persistent auth failure (refresh token revoked/invalid) - user must reconnect. */
    data object NeedsReauth : DropboxSyncResult()
}

/**
 * Syncs new `.fit` files from the user's configured Dropbox sync folder into the
 * local database, via the same import pipeline used by the manual file picker.
 */
@Singleton
class DropboxSyncService @Inject constructor(
    private val credentialStore: DropboxCredentialStore,
    private val requestConfig: DbxRequestConfig,
    private val fitImportService: FitImportService,
    private val userSettingsRepository: UserSettingsRepository
) {
    /**
     * Lists new/changed `.fit` files since the last sync (or the whole folder on
     * first sync), downloads each one, and imports it via [FitImportService].
     * Returns [DropboxSyncResult.Completed] with an empty list if Dropbox is not connected.
     */
    suspend fun sync(): DropboxSyncResult = withContext(Dispatchers.IO) {
        val credential = credentialStore.getCredential()
            ?: return@withContext DropboxSyncResult.Completed(emptyList())
        val client = DbxClientV2(requestConfig, credential)
        val syncFolder = userSettingsRepository.dropboxSyncFolder.first()

        val results = mutableListOf<ImportResult>()
        val savedCursor = credentialStore.getSyncCursor(syncFolder)

        try {
            var listing = if (savedCursor == null) {
                client.files().listFolder(syncFolder)
            } else {
                client.files().listFolderContinue(savedCursor)
            }

            while (true) {
                for (entry in listing.entries) {
                    if (entry is FileMetadata && entry.name.endsWith(".fit", ignoreCase = true)) {
                        results.add(downloadAndImport(client, entry))
                    }
                }

                credentialStore.saveSyncCursor(syncFolder, listing.cursor)

                if (!listing.hasMore) break
                listing = client.files().listFolderContinue(listing.cursor)
            }
        } catch (e: DbxException) {
            if (isPersistentAuthFailure(e)) {
                Log.w(TAG, "Dropbox sync failed: reauthorization required", e)
                return@withContext DropboxSyncResult.NeedsReauth
            }
            Log.w(TAG, "Dropbox sync failed (transient, will retry next sync)", e)
            if (results.isEmpty()) return@withContext DropboxSyncResult.TransientFailure
        }

        DropboxSyncResult.Completed(results)
    }

    private suspend fun downloadAndImport(client: DbxClientV2, entry: FileMetadata): ImportResult {
        return try {
            val output = ByteArrayOutputStream()
            client.files().download(entry.pathLower).download(output)
            fitImportService.importFile(entry.name, output.toByteArray())
        } catch (e: DbxException) {
            if (isPersistentAuthFailure(e)) throw e
            Log.e(TAG, "Failed to sync ${entry.name}", e)
            ImportResult.Error("Sync error for ${entry.name}: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ${entry.name}", e)
            ImportResult.Error("Sync error for ${entry.name}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DropboxSyncService"

        /**
         * True if [e] indicates the stored Dropbox credentials are revoked/invalid and
         * cannot be fixed by retrying - the user must reconnect their account.
         */
        internal fun isPersistentAuthFailure(e: DbxException): Boolean {
            return e is InvalidAccessTokenException ||
                (e is DbxOAuthException && e.dbxOAuthError.error == DbxOAuthError.INVALID_GRANT)
        }
    }
}
