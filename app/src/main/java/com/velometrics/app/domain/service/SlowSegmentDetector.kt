package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.median
import com.velometrics.app.util.percentile
import java.time.Instant

/**
 * Speed IQ slow segments (#225): stretches ridden below the speed the power before them would hold,
 * without braking hard enough for a braking event (a gate, a crowded path, gravel). The loss is
 * clock seconds against the expected speed, `Σ (Δt − Δs / v_exp)`, so it also works at 0 W.
 *
 * Expected speed `v_exp = k · v_physics(P_exp, slope)` from [RidePhysics]:
 * - P_exp is the trailing [CyclingConstants.SPEED_IQ_SLOW_POWER_WINDOW_SEC] mean of pedalling
 *   power, capped at the ride's [CyclingConstants.SPEED_IQ_SLOW_POWER_CAP_PERCENTILE] pedalling
 *   power. When speed drops below [CyclingConstants.SPEED_IQ_SLOW_SPEED_FRACTION] of v_exp it
 *   freezes at the value just before, and thaws after [CyclingConstants.SPEED_IQ_SLOW_THAW_SEC] back
 *   above it or [CyclingConstants.SPEED_IQ_SLOW_MAX_FREEZE_SEC] at most (which also ends the segment).
 * - The slope is Δh/Δs over a centred [CyclingConstants.SPEED_IQ_SLOW_SLOPE_WINDOW_M] of the
 *   ~10 s smoothed altitude, so slow riding doesn't make it noisy. On a descent v_exp is the flat
 *   speed: easing off downhill is pacing, not being held up (the #231 rides flagged dozens of
 *   gentle descents at 26–30 instead of 36–47 km/h otherwise).
 * - k is the median of `v / v_physics` over every evaluable second, clamped to
 *   [CyclingConstants.SPEED_IQ_SLOW_SPEED_FACTOR_MIN]..[CyclingConstants.SPEED_IQ_SLOW_SPEED_FACTOR_MAX]:
 *   it scales the physics to the day's wind, tyres and form (computed first, without freezes).
 *
 * A second is evaluable when the rider isn't standing, isn't inside an [excluded] span (braking
 * events and stops, already counted), the slope is at least [CyclingConstants.SPEED_IQ_SLOW_MIN_SLOPE]
 * (descents stay with braking) and P_exp is above 0. Slow seconds less than
 * [CyclingConstants.SPEED_IQ_JOIN_GAP_SEC] apart are one segment, which counts from
 * [CyclingConstants.SPEED_IQ_SLOW_MIN_LOSS_SEC] lost.
 */
object SlowSegmentDetector {

    class SlowSegment(
        val start: Instant,
        val end: Instant,
        val lostSec: Double,
        /** Σ Δs, Σ Δt and Σ Δs / v_exp over the slow seconds. */
        val distanceM: Double,
        val sec: Double,
        val expectedSec: Double,
        /** Record index of the slowest record, where the event is placed. */
        val slowestIndex: Int,
        /** Time-weighted mean P_exp and slope over the slow seconds, for the calibration report. */
        val expectedPowerW: Double,
        val slope: Double
    )

    class Result(
        /** k; null when the ride had no evaluable second to judge it by. */
        val speedFactor: Double?,
        val segments: List<SlowSegment>
    )

    /** Per-record series of one gap-free stretch of the ride. */
    private class Stretch(
        val first: Int,
        val t: DoubleArray,
        val rawSpeed: DoubleArray,
        val speed: DoubleArray,
        val slope: DoubleArray,
        val trailingPowerW: DoubleArray,
        val excluded: BooleanArray
    ) {
        val size get() = t.size
        fun judged(k: Int) = !excluded[k] && slope[k] >= CyclingConstants.SPEED_IQ_SLOW_MIN_SLOPE
    }

    /** One slow record-to-record step, from record k − 1 to k of its stretch. */
    private class Step(
        val k: Int, val startSec: Double, val endSec: Double, val loss: Double, val ds: Double, val dt: Double,
        val expectedSec: Double, val powerW: Double, val afterForcedThaw: Boolean
    )

    /** Null for a ride without pedalling samples, the only reference slow segments have. */
    fun detect(
        datapoints: List<Datapoint>,
        cumulativeM: DoubleArray,
        massKg: Double,
        excluded: List<ClosedRange<Instant>>
    ): Result? {
        val capW = datapoints
            .filter { (it.power ?: 0) > 0 && (it.speedKmh ?: 0.0) >= CyclingConstants.SPEED_IQ_MOVING_KMH }
            .map { it.power!!.toDouble() }
            .percentile(CyclingConstants.SPEED_IQ_SLOW_POWER_CAP_PERCENTILE) ?: return null
        val stretches = BrakingDetector.segments(datapoints).map { stretch(datapoints, it, cumulativeM, excluded) }

        val ratios = stretches.flatMap { s ->
            (0 until s.size).mapNotNull { k ->
                val p = minOf(s.trailingPowerW[k], capW)
                if (!s.judged(k) || p <= 0) return@mapNotNull null
                val vPhysics = RidePhysics.steadySpeedMps(p, s.slope[k], massKg)
                if (vPhysics > 0) s.speed[k] / vPhysics else null
            }
        }
        val speedFactor = ratios.median()?.coerceIn(
            CyclingConstants.SPEED_IQ_SLOW_SPEED_FACTOR_MIN, CyclingConstants.SPEED_IQ_SLOW_SPEED_FACTOR_MAX
        ) ?: return Result(null, emptyList())

        val segments = stretches.flatMap { s ->
            group(slowSteps(s, capW, speedFactor, massKg)).mapNotNull { steps -> segment(datapoints, s, steps) }
        }
        return Result(speedFactor, segments)
    }

    private fun stretch(datapoints: List<Datapoint>, range: IntRange, cumulativeM: DoubleArray, excluded: List<ClosedRange<Instant>>): Stretch {
        val first = range.first
        val n = range.last - first + 1
        val t = DoubleArray(n) { BrakingDetector.seconds(datapoints[first].timestamp, datapoints[first + it].timestamp) }
        val rawSpeed = DoubleArray(n) { k ->
            val i = first + k
            datapoints[i].speedKmh?.div(CyclingConstants.MTS_PER_SEC_TO_KMH)
                ?: if (k == 0) 0.0 else (cumulativeM[i] - cumulativeM[i - 1]) / (t[k] - t[k - 1])
        }
        val h = BrakingDetector.smooth(t, BrakingDetector.filledAltitudes(datapoints, range), CyclingConstants.SPEED_IQ_ALTITUDE_SMOOTH_HALF_SEC)
        val power = DoubleArray(n) { (datapoints[first + it].power ?: 0).toDouble() }
        val excludedRecord = BooleanArray(n) { k ->
            val at = datapoints[first + k].timestamp
            rawSpeed[k] * CyclingConstants.MTS_PER_SEC_TO_KMH < CyclingConstants.SPEED_IQ_STANDING_KMH || excluded.any { at in it }
        }
        return Stretch(
            first, t, rawSpeed,
            BrakingDetector.smooth(t, rawSpeed, CyclingConstants.SPEED_IQ_SPEED_SMOOTH_HALF_SEC),
            slopes(DoubleArray(n) { cumulativeM[first + it] }, h),
            trailingPedallingMean(t, power),
            excludedRecord
        )
    }

    /**
     * Δh/Δs over the records within half of [CyclingConstants.SPEED_IQ_SLOW_SLOPE_WINDOW_M] either side;
     * under [CyclingConstants.SPEED_IQ_SLOW_MIN_SLOPE_RUN_M] of run (standing, the stretch's ends)
     * the previous slope holds.
     */
    private fun slopes(distanceM: DoubleArray, altitudeM: DoubleArray): DoubleArray {
        val half = CyclingConstants.SPEED_IQ_SLOW_SLOPE_WINDOW_M / 2
        val result = DoubleArray(distanceM.size)
        var lo = 0
        var hi = 0
        var last = 0.0
        for (k in distanceM.indices) {
            while (distanceM[k] - distanceM[lo] > half) lo++
            while (hi + 1 < distanceM.size && distanceM[hi + 1] - distanceM[k] <= half) hi++
            val run = distanceM[hi] - distanceM[lo]
            if (run >= CyclingConstants.SPEED_IQ_SLOW_MIN_SLOPE_RUN_M) last = (altitudeM[hi] - altitudeM[lo]) / run
            result[k] = last
        }
        return result
    }

    /** Mean of the pedalling (> 0 W) records in the trailing window up to each record; 0 if none. */
    private fun trailingPedallingMean(t: DoubleArray, power: DoubleArray): DoubleArray {
        val result = DoubleArray(t.size)
        var lo = 0
        var sum = 0.0
        var count = 0
        for (k in t.indices) {
            if (power[k] > 0) { sum += power[k]; count++ }
            while (t[k] - t[lo] >= CyclingConstants.SPEED_IQ_SLOW_POWER_WINDOW_SEC) {
                if (power[lo] > 0) { sum -= power[lo]; count-- }
                lo++
            }
            result[k] = if (count > 0) sum / count else 0.0
        }
        return result
    }

    /** The stretch's slow steps, walking the freeze state record by record. */
    private fun slowSteps(s: Stretch, capW: Double, speedFactor: Double, massKg: Double): List<Step> {
        val steps = mutableListOf<Step>()
        var frozenW: Double? = null
        var frozenSince = 0.0
        var aboveSince: Double? = null
        var forcedThaw = false
        fun pExp(k: Int) = minOf(s.trailingPowerW[k], capW)
        // A gentle descent expects no more than the flat: easing off downhill is pacing, not being held up
        fun vExp(powerW: Double, k: Int) = speedFactor * RidePhysics.steadySpeedMps(powerW, maxOf(s.slope[k], 0.0), massKg)

        for (k in 1 until s.size) {
            if (frozenW != null && s.t[k] - frozenSince >= CyclingConstants.SPEED_IQ_SLOW_MAX_FREEZE_SEC) {
                frozenW = null; aboveSince = null; forcedThaw = true
            }
            val powerW = frozenW ?: pExp(k)
            if (!s.judged(k) || powerW <= 0) continue
            var expected = vExp(powerW, k)
            var slow = s.speed[k] < CyclingConstants.SPEED_IQ_SLOW_SPEED_FRACTION * expected
            if (frozenW == null) {
                if (slow) {
                    // Freeze at the power from just before the slowdown
                    frozenW = pExp(k - 1).takeIf { it > 0 } ?: powerW
                    frozenSince = s.t[k]
                    expected = vExp(frozenW, k)
                    slow = s.speed[k] < CyclingConstants.SPEED_IQ_SLOW_SPEED_FRACTION * expected
                }
            } else if (slow) {
                aboveSince = null
            } else {
                if (aboveSince == null) aboveSince = s.t[k - 1]
                if (s.t[k] - aboveSince >= CyclingConstants.SPEED_IQ_SLOW_THAW_SEC) { frozenW = null; aboveSince = null }
            }
            if (!slow || expected <= 0) continue
            val dt = s.t[k] - s.t[k - 1]
            val ds = (s.rawSpeed[k - 1] + s.rawSpeed[k]) / 2 * dt
            steps += Step(k, s.t[k - 1], s.t[k], dt - ds / expected, ds, dt, ds / expected, frozenW ?: powerW, forcedThaw)
            forcedThaw = false
        }
        return steps
    }

    /** Steps less than [CyclingConstants.SPEED_IQ_JOIN_GAP_SEC] apart, split where a freeze hit its limit. */
    private fun group(steps: List<Step>): List<List<Step>> {
        val groups = mutableListOf<MutableList<Step>>()
        for (step in steps) {
            val last = groups.lastOrNull()?.last()
            if (last != null && !step.afterForcedThaw && step.startSec - last.endSec < CyclingConstants.SPEED_IQ_JOIN_GAP_SEC) {
                groups.last() += step
            } else {
                groups += mutableListOf(step)
            }
        }
        return groups
    }

    private fun segment(datapoints: List<Datapoint>, s: Stretch, steps: List<Step>): SlowSegment? {
        val lost = steps.sumOf { it.loss }
        if (lost < CyclingConstants.SPEED_IQ_SLOW_MIN_LOSS_SEC) return null
        val slowest = steps.minBy { s.rawSpeed[it.k] }.k
        val sec = steps.sumOf { it.dt }
        return SlowSegment(
            start = datapoints[s.first + steps.first().k - 1].timestamp,
            end = datapoints[s.first + steps.last().k].timestamp,
            lostSec = lost,
            distanceM = steps.sumOf { it.ds },
            sec = sec,
            expectedSec = steps.sumOf { it.expectedSec },
            slowestIndex = s.first + slowest,
            expectedPowerW = steps.sumOf { it.powerW * it.dt } / sec,
            slope = steps.sumOf { s.slope[it.k] * it.dt } / sec
        )
    }
}
