package com.velometrics.app.domain.service

import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import java.util.Locale
import kotlin.math.abs

/**
 * One recap metric (#224): how it is named in prose, its "comparable" band around the median
 * ([band], relative to the median when [relative], else absolute), and how a difference is phrased.
 * [difference] takes (higher, also) and returns e.g. "average power was higher" / "you also did fewer intervals".
 */
enum class RecapMetricKind(
    val proseName: String,
    val band: Double,
    val relative: Boolean,
    val format: (Double) -> String,
    val difference: (Boolean, Boolean) -> String
) {
    DURATION("duration", CyclingConstants.RECAP_BAND_DURATION_DISTANCE, true, ::duration,
        { higher, also -> "your ride was ${also(also)}${if (higher) "longer" else "shorter"}" }),
    DISTANCE("distance", CyclingConstants.RECAP_BAND_DURATION_DISTANCE, true, { "%.1f km".format(Locale.US, it) },
        metric("distance", "longer", "shorter")),
    SPEED("speed", CyclingConstants.RECAP_BAND_SPEED, true, { "%.1f km/h".format(Locale.US, it) },
        metric("speed", "higher", "lower")),
    AVERAGE_POWER("average power", CyclingConstants.RECAP_BAND_POWER_HR, true, { "%.0f W".format(Locale.US, it) },
        metric("average power", "higher", "lower")),
    INTERVAL_POWER("interval power", CyclingConstants.RECAP_BAND_POWER_HR, true, { "%.0f W".format(Locale.US, it) },
        metric("interval power", "higher", "lower")),
    HEART_RATE("heart rate", CyclingConstants.RECAP_BAND_POWER_HR, true, { "%.0f bpm".format(Locale.US, it) },
        metric("heart rate", "higher", "lower")),
    CARDIAC_DRIFT("cardiac drift", CyclingConstants.RECAP_BAND_CARDIAC_DRIFT_PP, false, { "%.1f%%".format(Locale.US, it) },
        metric("cardiac drift", "higher", "lower")),
    FAT_EFFICIENCY("fat efficiency", CyclingConstants.RECAP_BAND_FAT_EFFICIENCY, false, { "%.0f".format(Locale.US, it) },
        metric("fat efficiency", "higher", "lower")),
    FAT_BURNED("fat burned", CyclingConstants.RECAP_BAND_DURATION_DISTANCE, true, { "%.0f g".format(Locale.US, it) },
        { higher, also -> "you ${also(also)}burned ${if (higher) "more" else "less"} fat" }),
    INTERVAL_COUNT("interval count", CyclingConstants.RECAP_BAND_INTERVAL_COUNT, false, { "%.0f".format(Locale.US, it) },
        { higher, also -> "you ${also(also)}did ${if (higher) "more" else "fewer"} intervals" }),
    INTERVAL_TIME("interval time", CyclingConstants.RECAP_BAND_DURATION_DISTANCE, true, ::duration,
        metric("interval time", "longer", "shorter")),
    TIME_BELOW_60_FTP("time below 60% FTP", CyclingConstants.RECAP_BAND_DURATION_DISTANCE, true, ::duration,
        metric("time below 60% FTP", "longer", "shorter"));
}

private fun duration(seconds: Double) = FormatUtils.formatDurationCompact(seconds.toInt())
private fun also(also: Boolean) = if (also) "also " else ""
private fun metric(name: String, higher: String, lower: String): (Boolean, Boolean) -> String =
    { isHigher, also -> "$name was ${also(also)}${if (isHigher) higher else lower}" }

/** This ride's [current] value of [kind] against the pool [median]. */
data class RecapMetric(val kind: RecapMetricKind, val current: Double, val median: Double) {

    /** Distance from the median in band widths: at most 1 is comparable, above 1 is higher/lower. */
    val deviation: Double
        get() {
            val width = if (kind.relative) kind.band * abs(median) else kind.band
            val diff = abs(current - median)
            if (width == 0.0) return if (diff == 0.0) 0.0 else Double.POSITIVE_INFINITY
            return diff / width
        }

    val comparable: Boolean get() = deviation <= 1.0 + EPSILON

    val values: String get() = "(${kind.format(current)} vs. ${kind.format(median)})"

    companion object {
        /** Floating-point slack so a value exactly on the band boundary counts as comparable. */
        private const val EPSILON = 1e-9

        /** Null when either side is missing, so unavailable metrics drop out of the candidate list. */
        fun of(kind: RecapMetricKind, current: Number?, median: Number?): RecapMetric? =
            if (current == null || median == null) null else RecapMetric(kind, current.toDouble(), median.toDouble())
    }
}

/**
 * Session Detail recap prose (#224): turns a candidate metric list into two short sentences — the
 * metrics outside their band ("Compared with …, your ride was shorter (…) and average power was
 * higher (…).") then the comparable ones ("Heart rate (…) was comparable."). At most
 * [CyclingConstants.RECAP_MAX_METRICS] are mentioned: the [anchor] whenever present, the rest by
 * [RecapMetric.deviation] (ties keep candidate order).
 */
object ComparisonProse {

    /**
     * [comparedWith] completes "Compared with …" (e.g. "other Zone 2 rides"), [typical] completes
     * "This ride was close to …" (e.g. "your typical Zone 2 ride"). Null without candidates.
     */
    fun paragraph(
        comparedWith: String,
        typical: String,
        candidates: List<RecapMetric>,
        anchor: RecapMetricKind? = null
    ): String? {
        val picked = pick(candidates, anchor)
        if (picked.isEmpty()) return null
        val (comparable, different) = picked.partition { it.comparable }

        if (different.isEmpty()) {
            val verb = when (comparable.size) {
                1 -> "was"
                2 -> "were both"
                else -> "were all"
            }
            return "This ride was close to $typical. ${comparableList(comparable)} $verb comparable."
        }

        val lead = if (comparable.isEmpty() && different.size > 2) different.take(2) else different
        val first = "Compared with $comparedWith, ${joinNatural(lead.map { differencePhrase(it, also = false) })}."
        val second = when {
            comparable.isNotEmpty() -> "${comparableList(comparable)} ${if (comparable.size == 1) "was" else "were"} comparable."
            different.size > lead.size -> "${differencePhrase(different.last(), also = true).capitalized()}."
            else -> null
        }
        return listOfNotNull(first, second).joinToString(" ")
    }

    private fun pick(candidates: List<RecapMetric>, anchor: RecapMetricKind?): List<RecapMetric> {
        val anchorMetric = candidates.firstOrNull { it.kind == anchor }
        val ranked = candidates.filter { it !== anchorMetric }.sortedByDescending { it.deviation }
        return (listOfNotNull(anchorMetric) + ranked).take(CyclingConstants.RECAP_MAX_METRICS)
    }

    private fun differencePhrase(m: RecapMetric, also: Boolean) =
        "${m.kind.difference(m.current > m.median, also)} ${m.values}"

    private fun comparableList(metrics: List<RecapMetric>) =
        joinNatural(metrics.map { "${it.kind.proseName} ${it.values}" }).capitalized()

    private fun joinNatural(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

    private fun String.capitalized() = replaceFirstChar { it.uppercase() }
}
