package com.velometrics.app.fakes

import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.fitimport.ImportResult
import java.time.Instant

/**
 * Scripted [FitImportService]. A file imports as a ride that starts at [startsByFileName] (default:
 * one hour after the previous one), is a [ImportResult.SmallFile] when named in [smallFiles] (unless
 * forced), or fails when named in [errors]. Successful imports are inserted into [sessions], so
 * reveal evaluation sees them exactly as it would against the real database.
 */
class FakeFitImportService(private val sessions: FakeCyclingSessionRepository) : FitImportService {
    val startsByFileName = mutableMapOf<String, Instant>()
    val smallFiles = mutableSetOf<String>()
    val errors = mutableSetOf<String>()

    /** Every call, in order: file name and whether it was forced. */
    val calls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun importFile(fileName: String, bytes: ByteArray, forceImport: Boolean): ImportResult {
        calls += fileName to forceImport
        if (fileName in errors) return ImportResult.Error("bad file $fileName")
        if (fileName in smallFiles && !forceImport) return ImportResult.SmallFile(fileName, 3)
        val start = startsByFileName[fileName] ?: Instant.parse("2026-03-01T08:00:00Z")
        val id = sessions.insertSession(testSession(0, start))
        return ImportResult.Success(id, "summary $fileName", start)
    }
}
