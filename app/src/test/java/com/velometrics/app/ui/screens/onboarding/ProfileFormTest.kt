package com.velometrics.app.ui.screens.onboarding

import com.velometrics.app.domain.model.FtpLevel
import com.velometrics.app.domain.model.RiderProfileEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileFormTest {

    private val form = ProfileForm(currentYear = 2026)

    @Test
    fun `estimates match the issue's examples`() {
        assertEquals(195, RiderProfileEstimate.ftp(FtpLevel.Regular, 75))
        assertEquals("2.6 W/kg × 75 kg = 195 W", RiderProfileEstimate.ftpFormula(FtpLevel.Regular, 75))
        assertEquals(179, RiderProfileEstimate.maxHr(41))
        assertEquals("208 − 0.7 × 41 = 179 bpm", RiderProfileEstimate.maxHrFormula(41))
    }

    @Test
    fun `defaults are a valid profile`() {
        assertEquals(RiderProfile(riderWeightKg = 75, bikeKitWeightKg = 10, ftp = 195, maxHr = 180), form.profile)
        assertEquals("2.6 W/kg × 75 kg = 195 W", form.ftpFormula)
        assertEquals("208 − 0.7 × 40 = 180 bpm", form.maxHrFormula)
    }

    @Test
    fun `level and weight update the FTP live`() {
        val racing = form.withLevel(FtpLevel.Racing)
        assertEquals("300", racing.ftp)
        assertEquals("320", racing.withRiderWeight("80").ftp)
        assertEquals("4.0 W/kg × 80 kg = 320 W", racing.withRiderWeight("80").ftpFormula)
    }

    @Test
    fun `a typed FTP survives weight changes until a level is picked again`() {
        val typed = form.withFtp("250").withRiderWeight("68")
        assertEquals(250, typed.profile?.ftp)
        assertNull(typed.level)
        assertNull(typed.ftpFormula)
        assertEquals("136", typed.withLevel(FtpLevel.GettingStarted).ftp)
    }

    @Test
    fun `birth year updates the max HR live and a typed max HR replaces the estimate`() {
        val older = form.withBirthYear("1966")
        assertEquals("166", older.maxHr)
        assertEquals("208 − 0.7 × 60 = 166 bpm", older.maxHrFormula)

        val typed = older.withMaxHr("172")
        assertEquals(172, typed.profile?.maxHr)
        assertNull(typed.maxHrFormula)
    }

    @Test
    fun `an invalid field blocks the profile, an invalid birth year doesn't`() {
        assertNull(form.withRiderWeight("").profile)
        assertNull(form.withBikeKitWeight("60").profile)
        assertNull(form.withFtp("20").profile)
        assertNull(form.withMaxHr("250").profile)

        val badYear = form.withBirthYear("19")
        assertEquals(180, badYear.profile?.maxHr)
        assertNull(badYear.maxHrFormula)
    }
}
