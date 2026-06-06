package com.velometrics.app.data.fitimport

sealed class ImportResult {
    data class Success(val sessionId: Long, val summary: String) : ImportResult()
    data class AlreadyImported(val fileName: String) : ImportResult()
    data class Error(val message: String) : ImportResult()
    data class SmallFile(val fileName: String, val dataPointCount: Int) : ImportResult()
}
