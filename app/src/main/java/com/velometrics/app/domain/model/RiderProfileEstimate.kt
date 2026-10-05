package com.velometrics.app.domain.model

import kotlin.math.roundToInt

/** Onboarding FTP level (#238): an FTP estimate of [wattsPerKg] × rider weight. */
enum class FtpLevel(val label: String, val hint: String, val wattsPerKg: Double) {
    GettingStarted("Getting started", "Rides occasionally, < 2 h/week", 2.0),
    Regular("Regular", "Rides weekly, 2–5 h/week", 2.6),
    Trained("Trained", "Structured training, club rides", 3.2),
    Racing("Racing", "Races, > 8 h/week", 4.0);

    companion object {
        val DEFAULT = Regular
    }
}

/** First-run estimates for the rider profile (#238), used until the rider knows better. */
object RiderProfileEstimate {

    fun ftp(level: FtpLevel, riderWeightKg: Int): Int = (level.wattsPerKg * riderWeightKg).roundToInt()

    /** Tanaka: 208 − 0.7 × age. */
    fun maxHr(age: Int): Int = (208 - 0.7 * age).roundToInt()

    /** e.g. `2.6 W/kg × 75 kg = 195 W`. */
    fun ftpFormula(level: FtpLevel, riderWeightKg: Int) =
        "${level.wattsPerKg} W/kg × $riderWeightKg kg = ${ftp(level, riderWeightKg)} W"

    /** e.g. `208 − 0.7 × 41 = 179 bpm`. */
    fun maxHrFormula(age: Int) = "208 − 0.7 × $age = ${maxHr(age)} bpm"
}
