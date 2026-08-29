package com.velometrics.app.data.repository

/**
 * Preserves `createdAt` across updates: for an existing row ([id] != 0), looks it up via
 * [lookupExisting]; for a new row, or a lookup that finds nothing, stamps the current time.
 */
suspend fun resolveCreatedAt(id: Long, lookupExisting: suspend () -> Long?): Long {
    return if (id != 0L) {
        lookupExisting() ?: System.currentTimeMillis()
    } else {
        System.currentTimeMillis()
    }
}
