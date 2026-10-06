package com.velometrics.app.util

/**
 * Median of this list, or null if it's empty. Odd-sized lists return the middle element after
 * sorting; even-sized lists return the average of the two middle elements.
 */
fun List<Double>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}

/** The [p]-th percentile (0..1) with linear interpolation between ranks, or null if the list is empty. */
fun List<Double>.percentile(p: Double): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val rank = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = minOf(lo + 1, sorted.lastIndex)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
}
