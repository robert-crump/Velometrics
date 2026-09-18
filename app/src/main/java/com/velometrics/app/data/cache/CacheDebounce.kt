package com.velometrics.app.data.cache

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/**
 * Debounce window shared by every derived cache in this package (`AllTimeStatsCache`,
 * `TrainingLoadCache`, `GlobalAverageCache`, and — via [HotFlowCache] — `RepeatedRoutesCache`/
 * `RepeatedIntervalsCache`).
 *
 * A bulk import inserts one session (and best-effort row) per file, and a clustering run deletes
 * then reinserts one row per cluster, so without debouncing, either a large import batch or a
 * single clustering pass would re-run a cache's aggregation once per intermediate write.
 * Debouncing lets each cache settle once, shortly after the last write in the batch/run, instead
 * of on every one.
 */
internal const val CACHE_DEBOUNCE_MS = 300L

@OptIn(FlowPreview::class)
internal fun <T> Flow<T>.debounced(): Flow<T> = debounce(CACHE_DEBOUNCE_MS)
