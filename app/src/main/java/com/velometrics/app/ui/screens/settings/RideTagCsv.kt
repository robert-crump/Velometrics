package com.velometrics.app.ui.screens.settings

import com.velometrics.app.domain.service.TagReviewRow
import java.util.Locale

/** CSV rendering for the debug-only ride-tag review dump (#170). */
object RideTagCsv {

    private val HEADER = listOf(
        "id", "sessionStart", "fileName", "distanceKm", "netDurationMin",
        "storedTag", "computedTag", "tagStale",
        "intervalCount", "fatEfficiencyScore", "averagePower", "ftp", "percentOfFtp", "hasPower"
    ).joinToString(",")

    fun render(rows: List<TagReviewRow>): String =
        (listOf(HEADER) + rows.map { rowFor(it) }).joinToString("\n", postfix = "\n")

    private fun rowFor(row: TagReviewRow): String {
        val session = row.session
        val percentOfFtp = session.averagePower?.let { it.toDouble() / row.ftp }
        return listOf(
            session.id,
            session.sessionStart,
            csvField(session.fileName),
            "%.1f".format(Locale.US, session.distanceKm),
            "%.1f".format(Locale.US, session.netDurationSec / 60.0),
            csvField(row.storedTag ?: ""),
            csvField(row.computedTag ?: ""),
            row.isStale,
            session.intervalCount,
            session.fatEfficiencyScore ?: "",
            session.averagePower ?: "",
            row.ftp,
            percentOfFtp?.let { "%.3f".format(Locale.US, it) } ?: "",
            session.hasPower
        ).joinToString(",")
    }

    private fun csvField(s: String): String = "\"${s.replace("\"", "\"\"")}\""
}
