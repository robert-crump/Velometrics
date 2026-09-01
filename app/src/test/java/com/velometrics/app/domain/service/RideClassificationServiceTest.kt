package com.velometrics.app.domain.service

import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RideClassificationServiceTest {

    private val ftp = 200

    private fun recoverySession(id: Long, tag: String? = null): CyclingSession = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = Instant.parse("2026-01-01T00:00:00Z"),
        sessionEnd = Instant.parse("2026-01-01T00:50:00Z"),
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

        service.reclassifyAll(ftp)

        repository.sessions.forEach { assertEquals(RideTag.RECOVERY.label, it.tag) }
    }

    @Test
    fun `reclassifyAll is safely re-runnable and corrects a stale tag`() = runBlocking {
        val repository = FakeCyclingSessionRepository()
        // Pre-existing (wrong) tag, e.g. a stale category from before #170 removed it as a
        // fallback -- reclassifyAll must overwrite it, not just skip already-tagged sessions.
        repository.sessions.add(recoverySession(id = 1, tag = "Endurance"))
        val service = RideClassificationService(repository)

        service.reclassifyAll(ftp)
        service.reclassifyAll(ftp) // re-run: must stay idempotent, not error or flip-flop

        assertEquals(RideTag.RECOVERY.label, repository.sessions.single().tag)
    }
}
