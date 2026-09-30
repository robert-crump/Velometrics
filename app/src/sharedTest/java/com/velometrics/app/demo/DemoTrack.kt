package com.velometrics.app.demo

import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPX track point: the only thing the demo assets carry (no metadata, no timestamps). */
data class GpxPoint(val lat: Double, val lon: Double, val ele: Double)

/**
 * A loop resampled every [STEP_M] metres along its path, with smoothed elevation, gradient and a
 * cornering speed limit, so the ride model can look everything up by distance travelled.
 */
class DemoTrack private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val ele: DoubleArray,
    private val grade: DoubleArray,
    private val speedLimit: DoubleArray
) {
    val lengthM: Double = (lat.size - 1) * STEP_M

    fun latAt(d: Double) = interp(lat, d)
    fun lonAt(d: Double) = interp(lon, d)
    fun eleAt(d: Double) = interp(ele, d)

    /** Smoothed gradient as a fraction (0.05 = 5 %). */
    fun gradeAt(d: Double) = interp(grade, d)

    /** Highest speed (m/s) the rider may carry at [d]: corners ahead are braked for in advance. */
    fun speedLimitAt(d: Double) = interp(speedLimit, d)

    private fun interp(a: DoubleArray, d: Double): Double {
        val x = (d / STEP_M).coerceIn(0.0, (a.size - 1).toDouble())
        val i = floor(x).toInt().coerceAtMost(a.size - 2)
        val f = x - i
        return a[i] + f * (a[i + 1] - a[i])
    }

    companion object {
        const val STEP_M = 5.0
        private const val ELE_SMOOTH_HALF_WINDOW_M = 60.0
        private const val GRADE_HALF_BASE_M = 25.0
        private const val HEADING_HALF_BASE_M = 20.0
        private const val LATERAL_ACCEL = 3.0     // m/s² tolerated through corners
        private const val BRAKE_DECEL = 2.5       // m/s² when slowing for a corner
        const val MAX_SPEED_MPS = 65.0 / 3.6      // descents are capped here
        private const val MIN_CORNER_SPEED_MPS = 4.0

        private val TRKPT = Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)">\s*<ele>([-0-9.]+)</ele>""")

        fun parseGpx(input: InputStream): List<GpxPoint> {
            val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return TRKPT.findAll(text).map { m ->
                GpxPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble())
            }.toList()
        }

        fun from(points: List<GpxPoint>): DemoTrack {
            require(points.size >= 2) { "track needs at least two points" }
            val cum = DoubleArray(points.size)
            for (i in 1 until points.size) {
                cum[i] = cum[i - 1] + haversine(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            }
            val n = (cum.last() / STEP_M).toInt() + 1
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val rawEle = DoubleArray(n)
            var seg = 0
            for (k in 0 until n) {
                val d = k * STEP_M
                while (seg < points.size - 2 && cum[seg + 1] < d) seg++
                val len = cum[seg + 1] - cum[seg]
                val f = if (len > 0) ((d - cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
                val a = points[seg]
                val b = points[seg + 1]
                lat[k] = a.lat + f * (b.lat - a.lat)
                lon[k] = a.lon + f * (b.lon - a.lon)
                rawEle[k] = a.ele + f * (b.ele - a.ele)
            }

            val ele = movingAverage(rawEle, (ELE_SMOOTH_HALF_WINDOW_M / STEP_M).toInt())
            val g = (GRADE_HALF_BASE_M / STEP_M).toInt()
            val grade = DoubleArray(n) { k ->
                val lo = (k - g).coerceAtLeast(0)
                val hi = (k + g).coerceAtMost(n - 1)
                if (hi > lo) (ele[hi] - ele[lo]) / ((hi - lo) * STEP_M) else 0.0
            }

            // Cornering limit from heading change across ±HEADING_HALF_BASE_M: radius = arc / Δθ.
            val h = (HEADING_HALF_BASE_M / STEP_M).toInt()
            val corner = DoubleArray(n) { k ->
                val lo = (k - h).coerceAtLeast(0)
                val hi = (k + h).coerceAtMost(n - 1)
                if (k - 1 < lo || k + 1 > hi) return@DoubleArray MAX_SPEED_MPS
                val inBearing = bearing(lat[lo], lon[lo], lat[k], lon[k])
                val outBearing = bearing(lat[k], lon[k], lat[hi], lon[hi])
                var turn = abs(outBearing - inBearing)
                if (turn > PI) turn = 2 * PI - turn
                if (turn < 1e-3) return@DoubleArray MAX_SPEED_MPS
                val radius = (hi - lo) * STEP_M / turn
                sqrt(LATERAL_ACCEL * radius).coerceIn(MIN_CORNER_SPEED_MPS, MAX_SPEED_MPS)
            }
            // Backward pass: never arrive at a corner faster than braking allows.
            val limit = corner.copyOf()
            for (k in n - 2 downTo 0) {
                limit[k] = min(limit[k], sqrt(limit[k + 1] * limit[k + 1] + 2 * BRAKE_DECEL * STEP_M))
            }
            return DemoTrack(lat, lon, ele, grade, limit)
        }

        fun brakingSpeed(distanceToStopM: Double) = sqrt(2 * BRAKE_DECEL * distanceToStopM.coerceAtLeast(0.0))

        private fun movingAverage(a: DoubleArray, half: Int): DoubleArray {
            val prefix = DoubleArray(a.size + 1)
            for (i in a.indices) prefix[i + 1] = prefix[i] + a[i]
            return DoubleArray(a.size) { i ->
                val lo = (i - half).coerceAtLeast(0)
                val hi = (i + half).coerceAtMost(a.size - 1)
                (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            }
        }

        private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dx = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
            val dy = lat2 - lat1
            return atan2(dx, dy)
        }

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
