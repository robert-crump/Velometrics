package com.velometrics.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object FormatUtils {

    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")
        .withZone(ZoneId.systemDefault())

    fun formatDurationHhMm(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0s"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun formatDurationLong(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${hours}h ${minutes}m ${seconds}s"
    }

    fun formatDistance(km: Double): String = "%.1f km".format(Locale.US, km)

    fun formatSpeed(kmh: Double): String = "%.1f km/h".format(Locale.US, kmh)

    fun formatPower(watts: Int): String = "$watts W"

    fun formatElevationGain(meters: Double): String = "%.0f m".format(Locale.US, meters)

    fun formatCardiacEfficiency(wattsPerBpm: Double): String = "%.2f W/bpm".format(Locale.US, wattsPerBpm)

    fun formatDate(instant: Instant): String = dateFormatter.format(instant)

    fun formatComparison(
        currentValue: Number,
        medianValue: Number?,
        unit: String,
        higherIsBetter: Boolean?
    ): String {
        val current = currentValue.toDouble()
        val formatted = when (unit) {
            "W" -> "${currentValue.toInt()} W"
            "km/h" -> "%.1f km/h".format(Locale.US, current)
            "km" -> "%.1f km".format(Locale.US, current)
            else -> "$currentValue $unit"
        }
        if (medianValue == null) return formatted

        val median = medianValue.toDouble()
        if (median == 0.0) return formatted

        val diff = current - median
        val pct = (diff / median * 100).roundToInt()
        val diffFormatted = when (unit) {
            "W" -> "%+d".format(Locale.US, diff.roundToInt())
            "km/h" -> "%+.1f".format(Locale.US, diff)
            "km" -> "%+.1f".format(Locale.US, diff)
            else -> "%+.1f".format(Locale.US, diff)
        }
        return "$formatted ($diffFormatted | %+d%%)".format(Locale.US, pct)
    }

    fun formatDurationComparison(currentSec: Int, medianSec: Int?): String {
        val currentFormatted = formatDuration(currentSec)
        if (medianSec == null) return currentFormatted

        if (medianSec == 0) return currentFormatted

        val diff = currentSec - medianSec
        val pct = (diff.toDouble() / medianSec * 100).roundToInt()
        val sign = if (diff >= 0) "+" else "-"
        val diffFormatted = "${sign}${formatDuration(abs(diff))}"
        return "$currentFormatted ($diffFormatted | %+d%%)".format(Locale.US, pct)
    }

    fun categoryDisplayName(category: String): String = when (category) {
        "cafe"            -> "Café"
        "bakery"          -> "Bakery"
        "bicycle"         -> "Bicycle"
        "restaurant"      -> "Restaurant"
        "fast_food"       -> "Fast food"
        "friture"         -> "Fast food"
        "fuel"            -> "Fuel station"
        "drinking_water"  -> "Drinking water"
        "vending_machine" -> "Vending machine"
        else              -> category.replaceFirstChar { it.uppercase() }
    }

    // Formats a distance in metres: < 1 km → nearest 10 m; ≥ 1 km → one decimal with comma separator
    fun formatPoiDistance(m: Double): String {
        return if (m < 1000.0) {
            val rounded = ((m / 10.0).roundToInt() * 10).coerceAtLeast(10)
            "$rounded m"
        } else {
            val km = m / 1000.0
            "${"%.1f".format(km).replace('.', ',')} km"
        }
    }

    // Formats a GPX POI distance: < 1 km → nearest 10 m; ≥ 1 km → nearest full km (e.g. "3 km")
    fun formatGpxPoiDistance(m: Double): String {
        return if (m < 1000.0) {
            val rounded = ((m / 10.0).roundToInt() * 10).coerceAtLeast(10)
            "$rounded m"
        } else {
            "${(m / 1000.0).roundToInt()} km"
        }
    }
}
