package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import java.io.File
import java.util.Locale

/**
 * Manual dev tool for issue #170 (validate/tune [RideClassifier] thresholds against real ride
 * history): writes one CSV row per session with its persisted tag, a freshly-recomputed tag
 * (staleness check), and the underlying signals [RideClassifier] reads, so a human can eyeball
 * the tag distribution and spot-check edge cases.
 *
 * Invoked from a debug-only row in Settings ([com.velometrics.app.ui.screens.settings
 * .SettingsViewModel.dumpSessionTagsForReview]) rather than the `androidTest` instrumented test
 * this was originally written for — `connectedDebugAndroidTest` reproducibly triggered an
 * unrelated on-device app uninstall during #170's investigation, wiping app data both times, so
 * the in-app debug button is the supported path going forward. Kept usable from both call sites
 * (this object has no Android Context dependency) in case that's ever revisited.
 */
object RideTagDumper {

    private val CSV_HEADER = listOf(
        "id", "sessionStart", "fileName", "distanceKm", "netDurationMin",
        "storedTag", "computedTag", "tagStale",
        "intervalCount", "fatEfficiencyScore", "averagePower", "percentOfFtp", "hasPower"
    ).joinToString(",")

    /** Returns tag counts (by persisted tag) alongside writing the CSV, for a quick log/UI summary. */
    fun dumpToCsv(sessions: List<CyclingSession>, outFile: File, ftp: Int): Map<String, Int> {
        outFile.bufferedWriter().use { out ->
            out.write(CSV_HEADER + "\n")
            for (session in sessions) {
                out.write(rowFor(session, ftp) + "\n")
            }
        }
        return sessions.groupingBy { it.tag ?: "(none)" }.eachCount()
    }

    private fun rowFor(session: CyclingSession, ftp: Int): String {
        val computedTag = RideClassifier.classify(session, ftp)?.label
        val percentOfFtp = session.averagePower?.let { it.toDouble() / ftp }
        return listOf(
            session.id,
            session.sessionStart,
            csvField(session.fileName),
            "%.1f".format(Locale.US, session.distanceKm),
            "%.1f".format(Locale.US, session.netDurationSec / 60.0),
            csvField(session.tag ?: ""),
            csvField(computedTag ?: ""),
            (session.tag != computedTag),
            session.intervalCount,
            session.fatEfficiencyScore ?: "",
            session.averagePower ?: "",
            fmt(percentOfFtp),
            session.hasPower
        ).joinToString(",")
    }

    private fun csvField(s: String): String = "\"${s.replace("\"", "\"\"")}\""

    private fun fmt(d: Double?): String = if (d == null) "" else "%.3f".format(Locale.US, d)
}
