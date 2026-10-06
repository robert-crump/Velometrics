package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CardiacDriftBand
import com.velometrics.app.domain.model.CardiacDriftCause
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.median
import java.time.ZoneId
import java.util.Locale

/**
 * Cardiac drift advice (#222): picks the likely causes of a ride's MEDIUM/HIGH drift from data the
 * ride already carries ([causes], run at import) and writes the two-sentence Session Detail
 * paragraph from them ([paragraph], run at display time so wording changes need no re-import).
 */
object CardiacDriftAdvisor {

    /**
     * Ranked likely causes, at most [CyclingConstants.CARDIAC_DRIFT_MAX_CAUSES]; an empty list means
     * nothing fired (generic advice). Null when the ride gets no advice at all (no or LOW drift).
     */
    fun causes(session: CyclingSession, ftp: Int, durationBaselineSec: Int?): List<CardiacDriftCause>? {
        val percent = session.cardiacDriftPercent ?: return null
        if (CardiacDriftBand.fromPercent(percent) == CardiacDriftBand.LOW) return null
        val hot = session.avgTemperatureC?.let { it >= CyclingConstants.CARDIAC_DRIFT_HEAT_MIN_TEMP_C } == true
        val hard = intensityFactor(session, ftp)?.let { it >= CyclingConstants.CARDIAC_DRIFT_INTENSITY_MIN_IF } == true
        val long = durationBaselineSec != null &&
            session.netDurationSec >= durationBaselineSec * CyclingConstants.CARDIAC_DRIFT_DURATION_FACTOR
        return listOfNotNull(
            CardiacDriftCause.HEAT.takeIf { hot },
            CardiacDriftCause.INTENSITY.takeIf { hard },
            CardiacDriftCause.DURATION.takeIf { long }
        ).take(CyclingConstants.CARDIAC_DRIFT_MAX_CAUSES)
    }

    /**
     * The rider's usual long-ride duration: median of [priorNetDurationsSec] (net durations of the
     * rides in the baseline window) that are at least an hour long, or null with too few of them.
     */
    fun durationBaselineSec(priorNetDurationsSec: List<Int>): Int? {
        val longRides = priorNetDurationsSec.filter { it >= CyclingConstants.CARDIAC_DRIFT_MIN_RIDE_SEC }
        if (longRides.size < CyclingConstants.CARDIAC_DRIFT_BASELINE_MIN_RIDES) return null
        return longRides.map { it.toDouble() }.median()?.toInt()
    }

    fun intensityFactor(session: CyclingSession, ftp: Int): Double? =
        session.normalizedPower?.takeIf { ftp > 0 }?.let { it.toDouble() / ftp }

    /**
     * Diagnosis sentence (band, percentage, up to two causes with one evidence number each) plus an
     * advice sentence; null when the ride has no stored causes. [ftp] is the FTP on the ride date.
     */
    fun paragraph(session: CyclingSession, ftp: Int, zone: ZoneId = ZoneId.systemDefault()): String? {
        val causes = session.cardiacDriftCauses ?: return null
        val percent = session.cardiacDriftPercent ?: return null
        val pct = "%.1f%%".format(Locale.US, percent)
        val lead = when (CardiacDriftBand.fromPercent(percent)) {
            CardiacDriftBand.HIGH -> "Your heart rate drifted noticeably ($pct)"
            else -> "Cardiac drift was moderate ($pct)"
        }
        val lateStart = session.sessionStart.atZone(zone).toLocalTime().hour >= CyclingConstants.CARDIAC_DRIFT_LATE_START_HOUR
        val longEnoughToEat = session.netDurationSec >= CyclingConstants.CARDIAC_DRIFT_FUELING_MIN_SEC

        if (causes.isEmpty()) {
            val advice = if (session.avgTemperatureC == null && lateStart) {
                "If it was warm, start riding earlier; otherwise the usual culprit is too little fluid or food, so drink regularly and eat every 30–45 min."
            } else {
                "The usual culprit is too little fluid or food on long rides, so drink regularly and eat every 30–45 min."
            }
            return "$lead. $advice"
        }

        val evidence = causes.mapNotNull { cause ->
            when (cause) {
                CardiacDriftCause.HEAT -> session.avgTemperatureC?.let { "the heat (avg ${"%.0f".format(Locale.US, it)} °C)" }
                CardiacDriftCause.INTENSITY -> intensityFactor(session, ftp)?.let {
                    "a pace harder than endurance riding (IF ${"%.2f".format(Locale.US, it)})"
                }
                CardiacDriftCause.DURATION -> session.cardiacDriftDurationBaselineSec?.let {
                    "a ride much longer than usual (${FormatUtils.formatDurationCompact(session.netDurationSec)} vs. ${FormatUtils.formatDurationCompact(it)})"
                }
            }
        }
        val actions = buildList {
            if (CardiacDriftCause.HEAT in causes) {
                if (lateStart) add("start riding earlier")
                add("drink more on the way")
            }
            if (CardiacDriftCause.INTENSITY in causes) add("keep the power lower on long rides so your heart rate stays steady")
            if (CardiacDriftCause.DURATION in causes) add("build up ride length gradually")
            if (longEnoughToEat) add("eat every 30–45 min")
        }
        val diagnosis = if (evidence.isEmpty()) "$lead." else "$lead, most likely because of ${evidence.joinToString(" and ")}."
        return "$diagnosis Next time, ${joinNatural(actions)}."
    }

    private fun joinNatural(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()
}
