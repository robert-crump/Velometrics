package com.velometrics.app.domain.service

import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.FtpEntry
import com.velometrics.app.domain.model.FtpHistory
import com.velometrics.app.domain.model.RideTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RideClassificationServiceTest {

    private val ftp = 200

    private fun recoverySession(
        id: Long,
        tag: String? = null,
        start: Instant = Instant.parse("2026-01-01T00:00:00Z")
    ): CyclingSession = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = start,
        sessionEnd = start.plusSeconds(3000),
        totalDurationSec = 3000,
        pauseDurationSec = 0,
        netDurationSec = 3000, // 50 min, under the 75-min Recovery ceiling
        distanceKm = 20.0,
        averagePower = 110, // 55% of 200 FTP, within the 50-60% Recovery band
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = true,
        tag = tag
    )

    @Test
    fun `reclassifyAll backfills a tag onto every pre-existing session`() = runBlocking {
        val repository = FakeCyclingSessionRepository()
        repository.sessions.add(recoverySession(id = 1))
        repository.sessions.add(recoverySession(id = 2))
        val service = RideClassificationService(repository)

        service.reclassifyAll(FtpHistory.constant(ftp))

        repository.sessions.forEach { assertEquals(RideTag.RECOVERY.label, it.tag) }
    }

    @Test
    fun `reclassifyAll is safely re-runnable and corrects a stale tag`() = runBlocking {
        val repository = FakeCyclingSessionRepository()
        // Pre-existing (wrong) tag, e.g. a stale category from before #170 removed it as a
        // fallback -- reclassifyAll must overwrite it, not just skip already-tagged sessions.
        repository.sessions.add(recoverySession(id = 1, tag = "Endurance"))
        val service = RideClassificationService(repository)

        service.reclassifyAll(FtpHistory.constant(ftp))
        service.reclassifyAll(FtpHistory.constant(ftp)) // re-run: must stay idempotent, not error or flip-flop

        assertEquals(RideTag.RECOVERY.label, repository.sessions.single().tag)
    }

    @Test
    fun `reviewRows classifies each session against the FTP in force on its ride date`() = runBlocking {
        val repository = FakeCyclingSessionRepository()
        // 110 W average: 55% of the 200 W FTP in force in January, only 37% of the 300 W in force in June.
        repository.sessions.add(recoverySession(id = 1, start = Instant.parse("2026-01-01T10:00:00Z")))
        repository.sessions.add(recoverySession(id = 2, start = Instant.parse("2026-06-15T10:00:00Z")))
        val history = FtpHistory(listOf(FtpEntry(null, 200), FtpEntry(LocalDate.of(2026, 3, 1), 300)))
        val service = RideClassificationService(repository)

        val rows = service.reviewRows(history).associateBy { it.session.id }

        assertEquals(RideTag.RECOVERY.label, rows.getValue(1).computedTag)
        assertEquals(null, rows.getValue(2).computedTag)
        assertEquals(200, rows.getValue(1).ftp)
        assertEquals(300, rows.getValue(2).ftp)
    }
}
