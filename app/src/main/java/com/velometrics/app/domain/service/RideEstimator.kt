package com.velometrics.app.domain.service

data class ScoredSegment(
    val lengthM: Double,
    val speedP25: Double?, val speedP50: Double?, val speedP75: Double?,
    val powerP25: Double?, val powerP50: Double?, val powerP75: Double?
)

enum class Percentile { SLOW, AVG, FAST }

data class RideEstimate(
    val percentile: Percentile,
    val durationSec: Double,
    val avgPowerW: Double
)

/**
 * Graph-agnostic ride scoring: turns a sequence of route segments with percentile speed/power
 * statistics into a single ride-time and power estimate for a chosen percentile band.
 */
object RideEstimator {

    /**
     * Returns null when none of the segments carry speed data for [percentile] — there is
     * nothing to base an estimate on.
     */
    fun estimateRide(segments: List<ScoredSegment>, percentile: Percentile): RideEstimate? {
        val withSpeed = segments.filter { speedFor(it, percentile) != null }
        if (withSpeed.isEmpty()) return null

        val coveredLengthM = withSpeed.sumOf { it.lengthM }
        val weightedSpeedSum = withSpeed.sumOf { speedFor(it, percentile)!! * it.lengthM }
        val avgSpeedKmh = if (coveredLengthM > 0) weightedSpeedSum / coveredLengthM else 0.0

        val totalLengthM = segments.sumOf { it.lengthM }
        val durationSec = if (avgSpeedKmh > 0) (totalLengthM / 1000.0) / avgSpeedKmh * 3600.0 else 0.0

        var weightedPowerSum = 0.0
        var totalPowerDurationSec = 0.0
        for (segment in segments) {
            val speed = speedFor(segment, percentile) ?: continue
            val power = powerFor(segment, percentile) ?: continue
            if (speed <= 0.0) continue
            val segmentDurationSec = (segment.lengthM / 1000.0) / speed * 3600.0
            weightedPowerSum += segmentDurationSec * power
            totalPowerDurationSec += segmentDurationSec
        }
        val avgPowerW = if (totalPowerDurationSec > 0) weightedPowerSum / totalPowerDurationSec else 0.0

        return RideEstimate(
            percentile = percentile,
            durationSec = durationSec,
            avgPowerW = avgPowerW
        )
    }

    private fun speedFor(segment: ScoredSegment, percentile: Percentile): Double? = when (percentile) {
        Percentile.SLOW -> segment.speedP25
        Percentile.AVG -> segment.speedP50
        Percentile.FAST -> segment.speedP75
    }

    private fun powerFor(segment: ScoredSegment, percentile: Percentile): Double? = when (percentile) {
        Percentile.SLOW -> segment.powerP25
        Percentile.AVG -> segment.powerP50
        Percentile.FAST -> segment.powerP75
    }
}
