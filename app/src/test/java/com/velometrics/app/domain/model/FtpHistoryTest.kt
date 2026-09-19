package com.velometrics.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FtpHistoryTest {

    private val history = FtpHistory(
        listOf(
            FtpEntry(effectiveDate = null, ftp = 200),
            FtpEntry(effectiveDate = LocalDate.of(2026, 3, 1), ftp = 230),
            FtpEntry(effectiveDate = LocalDate.of(2026, 6, 1), ftp = 250)
        )
    )

    @Test
    fun rideBeforeFirstRealEntryUsesSeed() {
        assertEquals(200, history.ftpOn(LocalDate.of(2026, 2, 28)))
    }

    @Test
    fun rideOnEffectiveDateUsesThatEntry() {
        assertEquals(230, history.ftpOn(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun rideBetweenEntriesUsesLatestEarlierEntry() {
        assertEquals(230, history.ftpOn(LocalDate.of(2026, 5, 31)))
    }

    @Test
    fun rideAfterLastEntryUsesLastEntry() {
        assertEquals(250, history.ftpOn(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun entriesNeedNotBeSuppliedInOrder() {
        val shuffled = FtpHistory(
            listOf(
                FtpEntry(LocalDate.of(2026, 6, 1), 250),
                FtpEntry(null, 200),
                FtpEntry(LocalDate.of(2026, 3, 1), 230)
            )
        )
        assertEquals(230, shuffled.ftpOn(LocalDate.of(2026, 4, 1)))
    }
}
