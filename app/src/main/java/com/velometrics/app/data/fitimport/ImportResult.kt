package com.velometrics.app.data.fitimport

import java.time.Instant

sealed class ImportResult {
    data class Success(val sessionId: Long, val summary: String, val sessionStart: Instant) : ImportResult()
    data class AlreadyImported(val fileName: String) : ImportResult()
    data class Error(val message: String) : ImportResult()
    data class SmallFile(val fileName: String, val dataPointCount: Int) : ImportResult()
}
