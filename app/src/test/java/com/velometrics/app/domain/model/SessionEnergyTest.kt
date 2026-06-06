package com.velometrics.app.domain.model

import com.velometrics.app.util.CyclingConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SessionEnergyTest {

    private fun session(fatG: Double?, carbG: Double?): CyclingSession =
        CyclingSession(
            fileName = "test.fit",
            fileSha1 = "sha",
            sessionStart = Instant.EPOCH,
            sessionEnd = Instant.EPOCH,
            totalDurationSec = 0,
            pauseDurationSec = 0,
            netDurationSec = 0,
            distanceKm = 0.0,
            averagePower = null,
            normalizedPower = null,
            fatBurnedGrams = fatG,
            carbsBurnedGrams = carbG,
            powerZoneDistribution = null,
            speedHistogram = emptyMap(),
            intervalCount = 0,
            intervalTotalTimeSec = 0,
            gpsQualityPercent = 0.0,
            powerQualityPercent = null,
            hasPower = false
        )

    @Test
    fun `from returns null when fat grams is null`() {
        assertNull(SessionEnergy.from(session(null, 50.0)))
    }

    @Test
    fun `from returns null when carb grams is null`() {
        assertNull(SessionEnergy.from(session(20.0, null)))
    }

    @Test
    fun `from computes kcal using fat and carb energy densities`() {
        val e = SessionEnergy.from(session(20.0, 50.0))!!
        val expected = (20.0 * CyclingConstants.KCAL_PER_GRAM_FAT +
                        50.0 * CyclingConstants.KCAL_PER_GRAM_CARB).toInt()
        assertEquals(expected, e.totalKcal)
        assertEquals(20.0, e.fatGrams, 0.0)
        assertEquals(50.0, e.carbGrams, 0.0)
    }

    @Test
    fun `kcal rounds to nearest integer`() {
        // 1 * 9.3 + 1 * 4.1 = 13.4 → 13
        assertEquals(13, SessionEnergy.from(session(1.0, 1.0))!!.totalKcal)
        // 1 * 9.3 + 2 * 4.1 = 17.5 → 18 (roundToInt ties round toward +∞)
        assertEquals(18, SessionEnergy.from(session(1.0, 2.0))!!.totalKcal)
    }

    @Test
    fun `formatTotalKcal uses dot as thousands separator`() {
        // 100g fat + 100g carb = 930 + 410 = 1340 kcal
        val e = SessionEnergy.from(session(100.0, 100.0))!!
        assertEquals(1340, e.totalKcal)
        assertEquals("1.340 kcal", e.formatTotalKcal())
    }

    @Test
    fun `formatTotalKcal handles values under a thousand`() {
        val e = SessionEnergy.from(session(10.0, 10.0))!!  // 93 + 41 = 134
        assertEquals("134 kcal", e.formatTotalKcal())
    }

    @Test
    fun `formatTotalKcal handles millions`() {
        val e = SessionEnergy(totalKcal = 1_234_567, fatGrams = 0.0, carbGrams = 0.0)
        assertEquals("1.234.567 kcal", e.formatTotalKcal())
    }

    @Test
    fun `formatTotalKcal handles zero`() {
        val e = SessionEnergy(totalKcal = 0, fatGrams = 0.0, carbGrams = 0.0)
        assertEquals("0 kcal", e.formatTotalKcal())
    }

    @Test
    fun `formatFatCarbGrams renders both values without decimals`() {
        val e = SessionEnergy(totalKcal = 0, fatGrams = 42.3, carbGrams = 187.1)
        assertEquals("42g / 187g", e.formatFatCarbGrams())
    }

    @Test
    fun `energy extension property delegates to from`() {
        val s = session(20.0, 50.0)
        assertEquals(SessionEnergy.from(s), s.energy)
    }

    @Test
    fun `energy extension property returns null when data missing`() {
        assertNull(session(null, null).energy)
    }
}
