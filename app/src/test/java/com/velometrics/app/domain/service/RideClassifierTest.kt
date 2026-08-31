package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RideClassifierTest {

    private fun baseSession(
        netDurationSec: Int = 3600,
        intervalCount: Int = 0,
        powerZoneDistribution: Map<String, Int>? = null,
        hrZoneDistribution: Map<String, Int>? = null,
        cardiacDriftPercent: Double? = null,
        fatEfficiencyScore: Int? = null,
        hasPower: Boolean = powerZoneDistribution != null
    ): CyclingSession = CyclingSession(
        fileName = "ride.fit",
        fileSha1 = "sha1",
        sessionStart = Instant.parse("2026-01-01T00:00:00Z"),
        sessionEnd = Instant.parse("2026-01-01T01:00:00Z"),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = 30.0,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = powerZoneDistribution,
        speedHistogram = emptyMap(),
        intervalCount = intervalCount,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = hasPower,
        hrZoneDistribution = hrZoneDistribution,
        cardiacDriftPercent = cardiacDriftPercent,
        fatEfficiencyScore = fatEfficiencyScore
    )

    @Test
    fun `classify tags Intervals when 2 or more intervals are detected, even amid a qualifying Zone 2 fat-efficiency score`() {
        // fatEfficiencyScore alone would otherwise qualify as Zone 2 -- Intervals must still win
        // since it's checked first.
        val session = baseSession(intervalCount = 2, fatEfficiencyScore = 90)

        assertEquals(RideTag.INTERVALS, RideClassifier.classify(session))
    }

    @Test
    fun `classify does not tag Intervals for a single detected interval`() {
        val session = baseSession(intervalCount = 1)

        assertNull(RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Zone 2 for a fat-efficiency score of 75 or higher`() {
        val session = baseSession(fatEfficiencyScore = 75)

        assertEquals(RideTag.ZONE_2, RideClassifier.classify(session))
    }

    @Test
    fun `classify does not tag Zone 2 just below the fat-efficiency floor`() {
        val session = baseSession(fatEfficiencyScore = 74)

        assertNull(RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Zone 2 ahead of a Recovery fallback signal when both apply`() {
        // fatEfficiencyScore of 80 clears both Zone 2's 75 floor and Recovery's fallback (with
        // low drift) -- Zone 2 must win since it's checked first.
        val session = baseSession(fatEfficiencyScore = 80, cardiacDriftPercent = 1.0)

        assertEquals(RideTag.ZONE_2, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Recovery for a Zone 1 majority ride`() {
        val session = baseSession(powerZoneDistribution = mapOf("Zone 1" to 70, "Zone 2" to 30))

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Recovery from low cardiac drift and high fat efficiency when zone data doesn't already say so`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 3" to 60, "Zone 2" to 40),
            cardiacDriftPercent = 2.0,
            fatEfficiencyScore = 72
        )

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session))
    }

    @Test
    fun `classify prefers power zones over heart-rate zones for the Recovery Zone 1 signal`() {
        // Power says no Zone 1, HR says Zone 1 majority -- power should win, so this stays
        // unclassified rather than tagging Recovery off the HR-only signal.
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 3" to 70, "Zone 2" to 30),
            hrZoneDistribution = mapOf("Zone 1" to 70, "Zone 2" to 30)
        )

        assertNull(RideClassifier.classify(session))
    }

    @Test
    fun `classify returns null when no rule matches -- there is no fallback category`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 2" to 30, "Zone 3" to 40, "Zone 4" to 30),
            fatEfficiencyScore = 60
        )

        assertNull(RideClassifier.classify(session))
    }

    @Test
    fun `classify returns null when there is no signal data at all`() {
        val session = baseSession()

        assertNull(RideClassifier.classify(session))
    }
}
