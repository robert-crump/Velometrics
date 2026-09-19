package com.velometrics.app.data.fitimport

/** Imports one FIT file's bytes as a ride. The seam `RideLifecycle` and `DropboxSyncService` fake in tests. */
interface FitImportService {
    /**
     * Parses and persists [bytes] as one ride, atomically. A file under the minimum GPS-point
     * count returns [ImportResult.SmallFile] instead of importing, unless [forceImport] is set.
     */
    suspend fun importFile(fileName: String, bytes: ByteArray, forceImport: Boolean = false): ImportResult
}
