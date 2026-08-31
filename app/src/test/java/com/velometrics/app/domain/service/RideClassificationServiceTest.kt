package com.velometrics.app.domain.service

import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RideClassificationServiceTest {

    private fun zone1Session(id: Long, tag: String? = null): CyclingSession = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = Instant.parse("2026-01-01T00:00:00Z"),
        sessionEnd = Instant.parse("2026-01-01T01:00:00Z"),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = 20.0,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = mapOf("Zone 1" to 70, "Zone 2" to 30),
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
        repository.sessions.add(zone1Session(id = 1))
        repository.sessions.add(zone1Session(id = 2))
        val service = RideClassificationService(repository)

        service.reclassifyAll()

        repository.sessions.forEach { assertEquals(RideTag.RECOVERY.label, it.tag) }
    }

    @Test
    fun `reclassifyAll is safely re-runnable and corrects a stale tag`() = runBlocking {
        val repository = FakeCyclingSessionRepository()
        // Pre-existing (wrong) tag, e.g. from before a threshold change -- reclassifyAll must
        // overwrite it, not just skip already-tagged sessions.
        repository.sessions.add(zone1Session(id = 1, tag = RideTag.RACE.label))
        val service = RideClassificationService(repository)

        service.reclassifyAll()
        service.reclassifyAll() // re-run: must stay idempotent, not error or flip-flop

        assertEquals(RideTag.RECOVERY.label, repository.sessions.single().tag)
    }
}
