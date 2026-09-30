package com.velometrics.app.demo

import java.time.Instant
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One 1 Hz record of a synthetic ride. */
data class DemoSample(
    val epochSec: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val speedMps: Double,
    val power: Int,
    val heartRate: Int,
    val distanceM: Double
)

enum class DemoTimerEventType { START, STOP, STOP_ALL }

data class DemoTimerEvent(val epochSec: Long, val type: DemoTimerEventType)

data class DemoSprint(val positionM: Double, val durationSec: Int, val peakPower: Int)

data class DemoStop(val positionM: Double, val durationSec: Int)

/** Everything random about one ride, drawn up front from the ride's seed. */
data class DemoRidePlan(
    val start: Instant,
    val dayForm: Double,
    val effortTargets: List<Double>,
    val sprints: List<DemoSprint>,
    val stops: List<DemoStop>,
    val driftBpm: Double,
    val altitudeOffsetM: Double
)

/**
 * Per-second ride model: power first, speed from physics, heart rate lagging behind power.
 *
 * - Power: endurance base plus a climbing bump of ~8 % FTP per 1 % grade (capped below the
 *   interval threshold outside efforts), coasting with zero stretches on descents, pinned efforts,
 *   short sprints, all under autocorrelated ±8 % noise.
 * - Speed: gravity + rolling resistance + aero drag with inertia, integrated every second; braked
 *   for corners and stops, capped at 65 km/h.
 * - Heart rate: first-order lag to 100 + 0.32·P (slower down than up) plus cardiac drift.
 */
object DemoRideModel {
    const val FTP = 260
    private const val MASS_KG = 82.0
    private const val G = 9.81
    private const val CRR = 0.004
    private const val CDA = 0.32
    private const val RHO = 1.2

    private const val OUTSIDE_EFFORT_CAP_FTP = 0.90
    private const val CLIMB_BUMP_FTP_PER_PERCENT = 0.08
    private const val NOISE_SD = 0.08
    private const val NOISE_PHI = 0.85
    private const val WARMUP_SEC = 480

    private const val HR_BASE = 100.0
    private const val HR_PER_WATT = 0.32
    private const val HR_REST = 88.0
    private const val HR_MAX = 185.0
    private const val HR_TAU_UP = 30.0
    private const val HR_TAU_DOWN = 45.0
    private const val DRIFT_FULL_SEC = 3 * 3600.0

    private const val GPS_JITTER_SD_M = 2.0
    private const val GPS_JITTER_PHI = 0.97
    private const val METERS_PER_DEG_LAT = 111_320.0

    private const val SPRINT_RECOVERY_SEC = 15
    private const val SPRINT_RECOVERY_W = 90.0
    private const val HERO_CAFE_STOP_M = 42_500.0

    fun plan(track: DemoTrack, loop: DemoLoop, start: Instant, progress: Double, isHero: Boolean, rng: Random): DemoRidePlan {
        val dayForm = rng.nextGaussian() * 0.02
        val effortTargets = loop.efforts.map { e ->
            val target = when (loop.workout) {
                // Climbs vary climb to climb; interval/threshold targets build over the block.
                DemoWorkout.CLIMBS -> e.minFtp + rng.nextDouble() * (e.maxFtp - e.minFtp)
                else -> e.minFtp + progress * (e.maxFtp - e.minFtp) + rng.nextGaussian() * 0.008
            }
            target.coerceIn(e.minFtp, e.maxFtp)
        }

        val lengthM = track.lengthM
        fun nearEffort(d: Double, marginM: Double) = loop.efforts.any {
            d > it.startKm * 1000 - marginM && d < it.endKm * 1000 + marginM
        }

        // Sprints: flat or rising road, clear of efforts, at least 3 km apart.
        val sprintCandidates = generateSequence(3000.0) { it + 100.0 }
            .takeWhile { it < lengthM - 3000 }
            .filter { d -> (0..3).all { k -> track.gradeAt(d + k * 100.0) in -0.01..0.03 } }
            .filterNot { nearEffort(it, 1500.0) }
            .filterNot { isHero && abs(it - HERO_CAFE_STOP_M) < 1500 }
            .toMutableList()
        val sprints = mutableListOf<DemoSprint>()
        repeat(1 + rng.nextInt(3)) {
            val options = sprintCandidates.filter { c -> sprints.none { abs(it.positionM - c) < 3000 } }
            if (options.isEmpty()) return@repeat
            sprints += DemoSprint(
                positionM = options[rng.nextInt(options.size)],
                durationSec = 8 + rng.nextInt(8),
                peakPower = 820 + rng.nextInt(200)
            )
        }
        sprints.sortBy { it.positionM }

        // Stops: 20 s – 10 min (log-uniform), clear of efforts and sprints. The hero ride gets a
        // fixed café stop on its flat middle section plus a short one.
        val stops = mutableListOf<DemoStop>()
        if (isHero) stops += DemoStop(HERO_CAFE_STOP_M, 420 + rng.nextInt(180))
        val stopCount = if (isHero) 1 else 1 + rng.nextInt(2)
        var attempts = 0
        while (stops.size < stopCount + (if (isHero) 1 else 0) && attempts++ < 200) {
            val d = 4000 + rng.nextDouble() * (lengthM - 8000)
            if (nearEffort(d, 1000.0)) continue
            if (sprints.any { abs(it.positionM - d) < 1000 }) continue
            if (stops.any { abs(it.positionM - d) < 3000 }) continue
            val maxSec = if (isHero) 60.0 else 600.0
            stops += DemoStop(d, exp(ln(20.0) + rng.nextDouble() * (ln(maxSec) - ln(20.0))).roundToInt())
        }
        stops.sortBy { it.positionM }

        return DemoRidePlan(
            start = start,
            dayForm = dayForm,
            effortTargets = effortTargets,
            sprints = sprints,
            stops = stops,
            driftBpm = 3.0 + rng.nextDouble() * 3.0,
            altitudeOffsetM = rng.nextGaussian() * 3.0
        )
    }

    class Result(val samples: List<DemoSample>, val timerEvents: List<DemoTimerEvent>)

    fun simulate(track: DemoTrack, loop: DemoLoop, plan: DemoRidePlan, rng: Random): Result {
        val samples = ArrayList<DemoSample>(12_000)
        val events = mutableListOf<DemoTimerEvent>()
        val lengthM = track.lengthM
        val effortRanges = loop.efforts.map { it.startKm * 1000..it.endKm * 1000 }

        var t = plan.start.epochSecond
        var d = 0.0
        var v = 0.0
        var hr = HR_REST
        var noise = 0.0
        var jitterE = 0.0
        var jitterN = 0.0
        var coasting = false
        var coastWatts = 30.0
        var movingSec = 0
        var nextSprint = 0
        var sprintClock = -1
        var nextStop = 0

        fun record(power: Int) {
            jitterE = GPS_JITTER_PHI * jitterE + rng.nextGaussian() * GPS_JITTER_SD_M * sqrt(1 - GPS_JITTER_PHI * GPS_JITTER_PHI)
            jitterN = GPS_JITTER_PHI * jitterN + rng.nextGaussian() * GPS_JITTER_SD_M * sqrt(1 - GPS_JITTER_PHI * GPS_JITTER_PHI)
            val lat = track.latAt(d)
            samples += DemoSample(
                epochSec = t,
                lat = lat + jitterN / METERS_PER_DEG_LAT,
                lon = track.lonAt(d) + jitterE / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat))),
                altitudeM = track.eleAt(d) + plan.altitudeOffsetM,
                speedMps = v,
                power = power,
                heartRate = hr.roundToInt(),
                distanceM = d
            )
        }

        fun updateHr(power: Double) {
            val drift = plan.driftBpm * min(1.0, movingSec / DRIFT_FULL_SEC)
            val target = HR_BASE + HR_PER_WATT * power + drift
            val tau = if (target > hr) HR_TAU_UP else HR_TAU_DOWN
            hr = min(HR_MAX, hr + (target - hr) / tau)
        }

        events += DemoTimerEvent(t, DemoTimerEventType.START)
        record(0)

        while (d < lengthM - 1.0) {
            val stop = plan.stops.getOrNull(nextStop)
            if (stop != null && d >= stop.positionM - 3.0) {
                // Roll to a halt, stand a few seconds, pause the timer for the rest of the stop.
                v = 0.0
                repeat(4) { t++; updateHr(0.0); record(0) }
                events += DemoTimerEvent(t, DemoTimerEventType.STOP)
                t += stop.durationSec
                hr = HR_REST + (hr - HR_REST) * exp(-stop.durationSec / 60.0)
                events += DemoTimerEvent(t, DemoTimerEventType.START)
                record(0)
                nextStop++
                continue
            }

            var vMax = min(track.speedLimitAt(d), DemoTrack.MAX_SPEED_MPS)
            if (stop != null) vMax = min(vMax, DemoTrack.brakingSpeed(stop.positionM - d - 2.0) + 0.5)
            vMax = min(vMax, DemoTrack.brakingSpeed(lengthM - d) + 1.5)

            val grade = track.gradeAt(d)
            noise = NOISE_PHI * noise + rng.nextGaussian() * NOISE_SD * sqrt(1 - NOISE_PHI * NOISE_PHI)
            val effortIdx = effortRanges.indexOfFirst { d in it }

            val sprint = plan.sprints.getOrNull(nextSprint)
            if (sprintClock < 0 && sprint != null && d >= sprint.positionM && effortIdx < 0) sprintClock = 0

            val power: Double = when {
                sprintClock >= 0 -> {
                    val s = plan.sprints[nextSprint]
                    val p = if (sprintClock < s.durationSec) {
                        val frac = if (sprintClock < 2) 0.8 + 0.1 * sprintClock
                        else 1.0 - 0.22 * (sprintClock - 2) / (s.durationSec - 2).toDouble()
                        s.peakPower * frac * (1 + noise * 0.3)
                    } else SPRINT_RECOVERY_W * (1 + noise)
                    sprintClock++
                    if (sprintClock >= s.durationSec + SPRINT_RECOVERY_SEC) {
                        sprintClock = -1
                        nextSprint++
                    }
                    p
                }
                effortIdx >= 0 -> plan.effortTargets[effortIdx] * FTP * (1 + plan.dayForm * 0.5) * (1 + noise * 0.5)
                v >= vMax - 0.2 -> 0.0 // braking for a corner/stop, or at the descent speed cap
                grade < -0.03 -> {
                    if (rng.nextDouble() < 0.04) {
                        coasting = !coasting
                        coastWatts = 20.0 + rng.nextDouble() * 40.0
                    }
                    if (coasting) 0.0 else coastWatts
                }
                else -> {
                    val base = loop.baseFtp * FTP * (1 + plan.dayForm) *
                        (if (movingSec < WARMUP_SEC) 0.8 + 0.2 * movingSec / WARMUP_SEC else 1.0)
                    val shaped = if (grade > 0) {
                        min(base + CLIMB_BUMP_FTP_PER_PERCENT * FTP * grade * 100, OUTSIDE_EFFORT_CAP_FTP * FTP)
                            .coerceAtLeast(base)
                    } else {
                        base * (1 + grade / 0.03 * 0.4)
                    }
                    shaped * (1 + noise)
                }
            }.coerceIn(0.0, 1400.0)

            // Physics, one second: drive force P/v against gravity, rolling resistance and drag.
            val theta = atan(grade)
            val drive = power / max(v, 2.0)
            val resist = MASS_KG * G * (sin(theta) + CRR * cos(theta)) + 0.5 * RHO * CDA * v * v
            val vNew = (v + (drive - resist) / MASS_KG).coerceIn(0.0, max(vMax, 0.0))
            d += (v + vNew) / 2
            v = vNew
            t++
            movingSec++
            updateHr(power)
            record(power.roundToInt())
        }

        events += DemoTimerEvent(t, DemoTimerEventType.STOP_ALL)
        return Result(samples, events)
    }
}
