package com.cyclegraph.app.util

import java.time.DayOfWeek
import java.time.LocalDateTime

object OpeningHoursUtils {

    private val DAY_ABBREVS = mapOf(
        "mo" to DayOfWeek.MONDAY,
        "tu" to DayOfWeek.TUESDAY,
        "we" to DayOfWeek.WEDNESDAY,
        "th" to DayOfWeek.THURSDAY,
        "fr" to DayOfWeek.FRIDAY,
        "sa" to DayOfWeek.SATURDAY,
        "su" to DayOfWeek.SUNDAY
    )

    /** Returns true if open, false if closed, null if the format could not be parsed. */
    fun isOpenNow(openingHours: String, now: LocalDateTime = LocalDateTime.now()): Boolean? {
        val s = openingHours.trim().lowercase()
        if (s == "24/7") return true
        if (s == "off") return false

        val rules = s.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        // OSM semantics: last matching rule wins
        var result: Boolean? = null
        for (rule in rules) {
            val r = evaluateRule(rule, now)
            if (r != null) result = r
        }
        return result
    }

    private fun evaluateRule(rule: String, now: LocalDateTime): Boolean? {
        if (rule == "off") return false
        if (rule == "24/7") return true

        val timeRegex = Regex("""(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})""")
        val timeMatch = timeRegex.find(rule) ?: return null

        val openMinutes  = parseTime(timeMatch.groupValues[1]) ?: return null
        val closeMinutes = parseTime(timeMatch.groupValues[2]) ?: return null

        val dayPart = rule.substring(0, timeMatch.range.first).trim()
        val appliesToday = if (dayPart.isEmpty()) true else matchesDay(dayPart, now.dayOfWeek)
        if (!appliesToday) return null

        val nowMinutes = now.hour * 60 + now.minute
        return if (closeMinutes > openMinutes) {
            nowMinutes in openMinutes until closeMinutes
        } else {
            // Overnight span (e.g. 22:00-02:00)
            nowMinutes >= openMinutes || nowMinutes < closeMinutes
        }
    }

    private fun parseTime(s: String): Int? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun matchesDay(daySpec: String, day: DayOfWeek): Boolean {
        if (daySpec.contains(",")) {
            return daySpec.split(",").any { matchesDay(it.trim(), day) }
        }
        if (daySpec.contains("-")) {
            val parts = daySpec.split("-", limit = 2)
            if (parts.size == 2) {
                val from = DAY_ABBREVS[parts[0].trim()] ?: return false
                val to   = DAY_ABBREVS[parts[1].trim()] ?: return false
                return isDayInRange(day, from, to)
            }
        }
        return DAY_ABBREVS[daySpec] == day
    }

    private fun isDayInRange(day: DayOfWeek, from: DayOfWeek, to: DayOfWeek): Boolean {
        val d = day.value; val f = from.value; val t = to.value
        return if (f <= t) d in f..t else d >= f || d <= t
    }
}
