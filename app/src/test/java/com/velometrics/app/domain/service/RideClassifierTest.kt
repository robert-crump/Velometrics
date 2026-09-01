package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RideClassifierTest {

    private val ftp = 200

    private fun baseSession(
        netDurationSec: Int = 3600,
        averagePower: Int? = null,
        intervalCount: Int = 0,
        fatEfficiencyScore: Int? = null,
        hasPower: Boolean = averagePower != null
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
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = intervalCount,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = hasPower,
        fatEfficiencyScore = fatEfficiencyScore
    )

    @Test
    fun `classify tags Recovery for a short ride with average power in the 50-60% FTP band`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 110) // 55% of 200 FTP, 50 min

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Recovery at exactly the 50% FTP floor`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 100)

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Recovery at exactly the 60% FTP ceiling`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 120)

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify does not tag Recovery just below the 50% FTP floor`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 99)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify does not tag Recovery just above the 60% FTP ceiling`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 121)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify does not tag Recovery at exactly 75 minutes -- must be shorter, not equal`() {
        val session = baseSession(netDurationSec = 75 * 60, averagePower = 110)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Recovery one second under 75 minutes`() {
        val session = baseSession(netDurationSec = 75 * 60 - 1, averagePower = 110)

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify does not tag Recovery when average power is missing`() {
        val session = baseSession(netDurationSec = 3000, averagePower = null)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Recovery ahead of a qualifying Zone 2 fat-efficiency score`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 110, fatEfficiencyScore = 90)

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Recovery ahead of a qualifying Intervals count`() {
        val session = baseSession(netDurationSec = 3000, averagePower = 110, intervalCount = 3)

        assertEquals(RideTag.RECOVERY, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Zone 2 for a fat-efficiency score of 75 or higher`() {
        val session = baseSession(fatEfficiencyScore = 75)

        assertEquals(RideTag.ZONE_2, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify does not tag Zone 2 just below the fat-efficiency floor`() {
        val session = baseSession(fatEfficiencyScore = 74)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Zone 2 ahead of a qualifying Intervals count when both apply`() {
        val session = baseSession(fatEfficiencyScore = 80, intervalCount = 3)

        assertEquals(RideTag.ZONE_2, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify tags Intervals when 2 or more intervals are detected and neither Recovery nor Zone 2 apply`() {
        val session = baseSession(intervalCount = 2)

        assertEquals(RideTag.INTERVALS, RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify does not tag Intervals for a single detected interval`() {
        val session = baseSession(intervalCount = 1)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify returns null when no rule matches -- there is no fallback category`() {
        val session = baseSession(netDurationSec = 5400, averagePower = 160, fatEfficiencyScore = 60)

        assertNull(RideClassifier.classify(session, ftp))
    }

    @Test
    fun `classify returns null when there is no signal data at all`() {
        val session = baseSession()

        assertNull(RideClassifier.classify(session, ftp))
    }
}
