package com.velometrics.app.domain.model

import java.time.LocalDate

/** One FTP value and the local date it takes effect. A null [effectiveDate] is the "Before first test" seed. */
data class FtpEntry(val effectiveDate: LocalDate?, val ftp: Int)

/** FTP over time (ADR 0001): a ride is judged against the FTP in force on its local ride date. */
class FtpHistory(entries: List<FtpEntry>) {

    private val sorted = entries.sortedBy { it.effectiveDate ?: LocalDate.MIN }

    fun ftpOn(date: LocalDate): Int =
        sorted.lastOrNull { (it.effectiveDate ?: LocalDate.MIN) <= date }?.ftp ?: sorted.first().ftp

    companion object {
        /** A history with a single seed entry, i.e. the same FTP on every date. */
        fun constant(ftp: Int) = FtpHistory(listOf(FtpEntry(null, ftp)))
    }
}
