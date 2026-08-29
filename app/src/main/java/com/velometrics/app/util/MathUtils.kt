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
