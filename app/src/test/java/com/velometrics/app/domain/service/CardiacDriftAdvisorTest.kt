package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CardiacDriftBand
import com.velometrics.app.domain.model.CardiacDriftCause
import com.velometrics.app.domain.model.CardiacDriftCause.DURATION
import com.velometrics.app.domain.model.CardiacDriftCause.HEAT
import com.velometrics.app.domain.model.CardiacDriftCause.INTENSITY
import com.velometrics.app.domain.model.CyclingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class CardiacDriftAdvisorTest {

    private val ftp = 250
    private val utc = ZoneOffset.UTC

    private fun session(
        driftPercent: Double? = 7.0,
        temperatureC: Double? = null,
        normalizedPower: Int? = 150, // IF 0.60
        netDurationSec: Int = 5400,
        start: Instant = Instant.parse("2026-07-01T09:00:00Z"),
        causes: List<CardiacDriftCause>? = null,
        baselineSec: Int? = null
    ) = CyclingSession(
        fileName = "ride.fit",
        fileSha1 = "sha",
        sessionStart = start,
        sessionEnd = start.plusSeconds(netDurationSec.toLong()),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = 40.0,
        averagePower = 140,
        normalizedPower = normalizedPower,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = 100.0,
        hasPower = true,
        cardiacDriftPercent = driftPercent,
        avgTemperatureC = temperatureC,
        cardiacDriftCauses = causes,
        cardiacDriftDurationBaselineSec = baselineSec
    )

    @Test
    fun `bands split at 5 and 10 percent`() {
        assertEquals(CardiacDriftBand.LOW, CardiacDriftBand.fromPercent(4.9))
        assertEquals(CardiacDriftBand.LOW, CardiacDriftBand.fromPercent(-2.0))
        assertEquals(CardiacDriftBand.MEDIUM, CardiacDriftBand.fromPercent(5.0))
        assertEquals(CardiacDriftBand.HIGH, CardiacDriftBand.fromPercent(10.0))
    }

    @Test
    fun `no causes for low or missing drift`() {
        assertNull(CardiacDriftAdvisor.causes(session(driftPercent = 4.9, temperatureC = 30.0), ftp, null))
        assertNull(CardiacDriftAdvisor.causes(session(driftPercent = null), ftp, null))
    }

    @Test
    fun `empty causes when nothing fires`() {
        assertEquals(emptyList<CardiacDriftCause>(), CardiacDriftAdvisor.causes(session(temperatureC = 24.9), ftp, 5400))
    }

    @Test
    fun `each rule fires at its threshold`() {
        assertEquals(listOf(HEAT), CardiacDriftAdvisor.causes(session(temperatureC = 25.0), ftp, null))
        assertEquals(listOf(INTENSITY), CardiacDriftAdvisor.causes(session(normalizedPower = 188), ftp, null)) // IF 0.752
        assertEquals(emptyList<CardiacDriftCause>(), CardiacDriftAdvisor.causes(session(normalizedPower = 187), ftp, null))
        assertEquals(listOf(DURATION), CardiacDriftAdvisor.causes(session(netDurationSec = 7800), ftp, 6000)) // 1.3x
        assertEquals(emptyList<CardiacDriftCause>(), CardiacDriftAdvisor.causes(session(netDurationSec = 7799), ftp, 6000))
    }

    @Test
    fun `at most two causes, strongest first`() {
        val all = session(temperatureC = 30.0, normalizedPower = 200, netDurationSec = 9000)
        assertEquals(listOf(HEAT, INTENSITY), CardiacDriftAdvisor.causes(all, ftp, 5400))
    }

    @Test
    fun `duration baseline needs three rides of at least an hour`() {
        assertNull(CardiacDriftAdvisor.durationBaselineSec(listOf(3600, 7200, 1800, 1200)))
        assertEquals(5400, CardiacDriftAdvisor.durationBaselineSec(listOf(3600, 5400, 7200, 1800)))
    }

    @Test
    fun `no paragraph without stored causes`() {
        assertNull(CardiacDriftAdvisor.paragraph(session(causes = null), ftp, utc))
    }

    @Test
    fun `hot late ride says start riding earlier and drink more`() {
        val s = session(
            driftPercent = 11.4, temperatureC = 29.2, start = Instant.parse("2026-07-01T14:10:00Z"),
            netDurationSec = 5400, causes = listOf(HEAT)
        )
        assertEquals(
            "Your heart rate drifted noticeably (11.4%), most likely because of the heat (avg 29 °C). " +
                "Next time, start riding earlier and drink more on the way.",
            CardiacDriftAdvisor.paragraph(s, ftp, utc)
        )
    }

    @Test
    fun `hot early ride drops the timing advice`() {
        val s = session(temperatureC = 26.0, start = Instant.parse("2026-07-01T08:00:00Z"), causes = listOf(HEAT))
        assertEquals(
            "Cardiac drift was moderate (7.0%), most likely because of the heat (avg 26 °C). Next time, drink more on the way.",
            CardiacDriftAdvisor.paragraph(s, ftp, utc)
        )
    }

    @Test
    fun `two causes with evidence and eating advice on long rides`() {
        val s = session(
            driftPercent = 6.8, normalizedPower = 205, netDurationSec = 11400,
            causes = listOf(INTENSITY, DURATION), baselineSec = 7500
        )
        assertEquals(
            "Cardiac drift was moderate (6.8%), most likely because of a pace harder than endurance riding (IF 0.82) " +
                "and a ride much longer than usual (3h10min vs. 2h05min). Next time, keep the power lower on long rides so " +
                "your heart rate stays steady, build up ride length gradually and eat every 30–45 min.",
            CardiacDriftAdvisor.paragraph(s, ftp, utc)
        )
    }

    @Test
    fun `fallback mentions heat conditionally only without a temperature on a late ride`() {
        val late = Instant.parse("2026-07-01T15:00:00Z")
        assertEquals(
            "Cardiac drift was moderate (7.1%). If it was warm, start riding earlier; otherwise the usual culprit is " +
                "too little fluid or food, so drink regularly and eat every 30–45 min.",
            CardiacDriftAdvisor.paragraph(session(driftPercent = 7.1, start = late, causes = emptyList()), ftp, utc)
        )
        assertEquals(
            "Cardiac drift was moderate (7.1%). The usual culprit is too little fluid or food on long rides, so drink " +
                "regularly and eat every 30–45 min.",
            CardiacDriftAdvisor.paragraph(
                session(driftPercent = 7.1, start = late, temperatureC = 18.0, causes = emptyList()), ftp, utc
            )
        )
    }
}
