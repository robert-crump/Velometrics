package com.velometrics.app.data.dropbox

import android.util.Log
import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
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
    companion object {
        private const val TAG = "DropboxSyncService"
    }

    /**
     * Lists new/changed `.fit` files since the last sync (or the whole folder on
     * first sync), downloads each one, and imports it via [FitImportService].
     * Returns an empty list if Dropbox is not connected.
     */
    suspend fun sync(): List<ImportResult> = withContext(Dispatchers.IO) {
        val credential = credentialStore.getCredential() ?: return@withContext emptyList()
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
            Log.e(TAG, "Dropbox sync failed", e)
            results.add(ImportResult.Error("Dropbox sync failed: ${e.message}"))
        }

        results
    }

    private suspend fun downloadAndImport(client: DbxClientV2, entry: FileMetadata): ImportResult {
        return try {
            val output = ByteArrayOutputStream()
            client.files().download(entry.pathLower).download(output)
            fitImportService.importFile(entry.name, output.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ${entry.name}", e)
            ImportResult.Error("Sync error for ${entry.name}: ${e.message}")
        }
    }
}
