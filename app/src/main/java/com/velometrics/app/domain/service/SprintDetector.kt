package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.CyclingConstants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SprintDetector @Inject constructor() {

    data class Sprint(val peakPower: Int, val durationSec: Int)

    fun detect(datapoints: List<Datapoint>, ftp: Int): List<Sprint> {
        val threshold = ftp * CyclingConstants.SPRINT_THRESHOLD_FACTOR
        val candidates = mutableListOf<Sprint>()

        var sprintStart: Int? = null
        var lastAboveIdx: Int = -1
        var consecutiveBelow = 0

        for (i in datapoints.indices) {
            val power = datapoints[i].power?.toDouble() ?: 0.0

            if (power > threshold) {
                if (sprintStart == null) sprintStart = i
                lastAboveIdx = i
                consecutiveBelow = 0
            } else {
                if (sprintStart != null) {
                    consecutiveBelow++
                    if (consecutiveBelow >= 2) {
                        // Sprint ended; last valid point is lastAboveIdx.
                        // Duration counts all points from sprintStart through lastAboveIdx
                        // (including any 1-point gaps that were followed by above-threshold).
                        val durationSec = lastAboveIdx - sprintStart + 1
                        val peakPower = datapoints.subList(sprintStart, lastAboveIdx + 1)
                            .mapNotNull { it.power }.maxOrNull() ?: 0
                        candidates.add(Sprint(peakPower, durationSec))
                        sprintStart = null
                        lastAboveIdx = -1
                        consecutiveBelow = 0
                    }
                }
            }
        }

        // Handle sprint that reaches the end of datapoints
        if (sprintStart != null && lastAboveIdx >= sprintStart) {
            val durationSec = lastAboveIdx - sprintStart + 1
            val peakPower = datapoints.subList(sprintStart, lastAboveIdx + 1)
                .mapNotNull { it.power }.maxOrNull() ?: 0
            candidates.add(Sprint(peakPower, durationSec))
        }

        // Discard sprints outside 3–30s range
        return candidates.filter { it.durationSec in 3..30 }
    }

    fun buildHistogram(sprints: List<Sprint>): Map<String, Int> {
        val histogram = mutableMapOf("3-5s" to 0, "5-15s" to 0, "15-30s" to 0)
        for (sprint in sprints) {
            when {
                sprint.durationSec in 3..5 -> histogram["3-5s"] = histogram.getOrDefault("3-5s", 0) + 1
                sprint.durationSec in 6..15 -> histogram["5-15s"] = histogram.getOrDefault("5-15s", 0) + 1
                sprint.durationSec in 16..30 -> histogram["15-30s"] = histogram.getOrDefault("15-30s", 0) + 1
            }
        }
        return histogram
    }
}
