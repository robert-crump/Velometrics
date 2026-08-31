package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RideClassifierTest {

    private fun baseSession(
        netDurationSec: Int = 3600,
        intervalTotalTimeSec: Int = 0,
        powerZoneDistribution: Map<String, Int>? = null,
        hrZoneDistribution: Map<String, Int>? = null,
        averagePower: Int? = null,
        normalizedPower: Int? = null,
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
        averagePower = averagePower,
        normalizedPower = normalizedPower,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = powerZoneDistribution,
        speedHistogram = emptyMap(),
        intervalCount = if (intervalTotalTimeSec > 0) 1 else 0,
        intervalTotalTimeSec = intervalTotalTimeSec,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = hasPower,
        hrZoneDistribution = hrZoneDistribution,
        cardiacDriftPercent = cardiacDriftPercent,
        fatEfficiencyScore = fatEfficiencyScore
    )

    @Test
    fun `classify tags Intervals when interval time share exceeds the threshold, even amid a high-zone majority`() {
        // 20% of net time in detected intervals clears the 12% threshold, while the zone
        // distribution alone would otherwise qualify as Race (majority Zone 4+, steady NP-AP) --
        // Intervals must still win since it's checked first.
        val session = baseSession(
            netDurationSec = 3600,
            intervalTotalTimeSec = 720,
            powerZoneDistribution = mapOf("Zone 4" to 60, "Zone 3" to 40),
            averagePower = 200,
            normalizedPower = 205
        )

        assertEquals(RideTag.INTERVALS, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Race for a high-zone majority with a steady NP-AP ratio`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 4" to 70, "Zone 3" to 30),
            averagePower = 220,
            normalizedPower = 225 // NP:AP ~= 1.02, steady
        )

        assertEquals(RideTag.RACE, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Race ahead of a Recovery fallback signal when both apply`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 4" to 70, "Zone 3" to 30),
            averagePower = 220,
            normalizedPower = 225,
            cardiacDriftPercent = 1.0,
            fatEfficiencyScore = 95 // would also qualify as Recovery's fallback signal
        )

        assertEquals(RideTag.RACE, RideClassifier.classify(session))
    }

    @Test
    fun `classify does not tag Race when the high-zone effort is too spiky`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 4" to 70, "Zone 3" to 30),
            averagePower = 200,
            normalizedPower = 240 // NP:AP = 1.2, spiky
        )

        assertEquals(RideTag.ENDURANCE, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Race off HR-zone majority alone when there is no power meter`() {
        val session = baseSession(
            hasPower = false,
            hrZoneDistribution = mapOf("Zone 4" to 55, "Zone 3" to 45)
        )

        assertEquals(RideTag.RACE, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Zone 2 for a Zone 2 majority ride, ahead of a Recovery fallback signal`() {
        // Zone 2 majority and a genuinely qualifying Recovery fallback (low drift, high fat
        // efficiency) both hold -- Zone 2 must win since it's checked first.
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 2" to 65, "Zone 1" to 20, "Zone 3" to 15),
            cardiacDriftPercent = 1.5,
            fatEfficiencyScore = 85
        )

        assertEquals(RideTag.ZONE_2, RideClassifier.classify(session))
    }

    @Test
    fun `classify prefers power zones over heart-rate zones when both are present`() {
        // Power says Zone 2 majority, HR says Zone 4 majority -- power should win.
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 2" to 70, "Zone 3" to 30),
            hrZoneDistribution = mapOf("Zone 4" to 70, "Zone 3" to 30),
            averagePower = 150,
            normalizedPower = 155
        )

        assertEquals(RideTag.ZONE_2, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Recovery for a Zone 1 majority ride`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 1" to 70, "Zone 2" to 30)
        )

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session))
    }

    @Test
    fun `classify tags Recovery from low cardiac drift and high fat efficiency when zone data doesn't already say so`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 3" to 60, "Zone 2" to 40),
            cardiacDriftPercent = 2.0,
            fatEfficiencyScore = 90
        )

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session))
    }

    @Test
    fun `classify falls back to Endurance when no rule matches`() {
        val session = baseSession(
            powerZoneDistribution = mapOf("Zone 2" to 30, "Zone 3" to 40, "Zone 4" to 30),
            averagePower = 180,
            normalizedPower = 190
        )

        assertEquals(RideTag.ENDURANCE, RideClassifier.classify(session))
    }

    @Test
    fun `classify falls back to Endurance when there is no zone data at all`() {
        val session = baseSession()

        assertEquals(RideTag.ENDURANCE, RideClassifier.classify(session))
    }
}
