package com.cyclegraph.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

object FormatUtils {

    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")
        .withZone(ZoneId.systemDefault())

    private val dateShortFormatter = DateTimeFormatter.ofPattern("dd MMM")
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

    fun formatDistance(km: Double): String = "%.1f km".format(km)

    fun formatSpeed(kmh: Double): String = "%.1f km/h".format(kmh)

    fun formatPower(watts: Int): String = "$watts W"

    fun formatDate(instant: Instant): String = dateFormatter.format(instant)

    fun formatDateShort(instant: Instant): String = dateShortFormatter.format(instant)

    fun formatComparison(
        currentValue: Number,
        medianValue: Number?,
        unit: String,
        higherIsBetter: Boolean?
    ): String {
        val current = currentValue.toDouble()
        val formatted = when (unit) {
            "W" -> "${currentValue.toInt()} W"
            "km/h" -> "%.1f km/h".format(current)
            "km" -> "%.1f km".format(current)
            else -> "$currentValue $unit"
        }
        if (medianValue == null) return formatted

        val median = medianValue.toDouble()
        if (median == 0.0) return formatted

        val diff = current - median
        val pct = (diff / median * 100).roundToInt()
        val diffFormatted = when (unit) {
            "W" -> "%+d".format(diff.roundToInt())
            "km/h" -> "%+.1f".format(diff)
            "km" -> "%+.1f".format(diff)
            else -> "%+.1f".format(diff)
        }
        return "$formatted ($diffFormatted | %+d%%)".format(pct)
    }

    fun formatDurationComparison(currentSec: Int, medianSec: Int?): String {
        val currentFormatted = formatDuration(currentSec)
        if (medianSec == null) return currentFormatted

        if (medianSec == 0) return currentFormatted

        val diff = currentSec - medianSec
        val pct = (diff.toDouble() / medianSec * 100).roundToInt()
        val sign = if (diff >= 0) "+" else "-"
        val diffFormatted = "${sign}${formatDuration(abs(diff))}"
        return "$currentFormatted ($diffFormatted | %+d%%)".format(pct)
    }

    fun categoryDisplayName(category: String): String = when (category) {
        "cafe"       -> "Café"
        "bakery"     -> "Bakery"
        "restaurant" -> "Restaurant"
        "fast_food"  -> "Fast food"
        "fuel"       -> "Fuel station"
        "friture"    -> "Friture"
        else         -> category.replaceFirstChar { it.uppercase() }
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
}
