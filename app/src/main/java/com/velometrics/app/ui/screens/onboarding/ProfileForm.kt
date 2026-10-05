package com.velometrics.app.ui.screens.onboarding

import com.velometrics.app.domain.model.FtpLevel
import com.velometrics.app.domain.model.RiderProfileEstimate
import com.velometrics.app.util.CyclingConstants

/** What the onboarding saves (#238). The birth year isn't part of it: it only feeds the max HR estimate. */
data class RiderProfile(val riderWeightKg: Int, val bikeKitWeightKg: Int, val ftp: Int, val maxHr: Int)

/**
 * The onboarding "Your profile" page (#238) as raw field text. Every field starts with a valid default.
 * Picking a level or changing the weight recomputes the FTP; typing an FTP clears the level ("I know my
 * FTP"), so a later weight change leaves it alone. The max HR works the same way with the birth year.
 */
data class ProfileForm(
    val currentYear: Int,
    val riderWeight: String = CyclingConstants.DEFAULT_RIDER_WEIGHT_KG.toString(),
    val bikeKitWeight: String = CyclingConstants.DEFAULT_BIKE_KIT_WEIGHT_KG.toString(),
    val level: FtpLevel? = FtpLevel.DEFAULT,
    val ftp: String = RiderProfileEstimate.ftp(FtpLevel.DEFAULT, CyclingConstants.DEFAULT_RIDER_WEIGHT_KG).toString(),
    val birthYear: String = (currentYear - DEFAULT_AGE).toString(),
    val maxHrEstimated: Boolean = true,
    val maxHr: String = RiderProfileEstimate.maxHr(DEFAULT_AGE).toString()
) {
    val riderWeightKg: Int? get() = riderWeight.parseIn(CyclingConstants.RIDER_WEIGHT_RANGE_KG)
    val bikeKitWeightKg: Int? get() = bikeKitWeight.parseIn(CyclingConstants.BIKE_KIT_WEIGHT_RANGE_KG)
    val ftpW: Int? get() = ftp.parseIn(CyclingConstants.FTP_RANGE_W)
    val maxHrBpm: Int? get() = maxHr.parseIn(CyclingConstants.MAX_HR_RANGE_BPM)
    val age: Int? get() = birthYear.parseIn((currentYear - MAX_AGE)..(currentYear - MIN_AGE))?.let { currentYear - it }

    /** The live formula under the FTP field, or null once the FTP was typed over. */
    val ftpFormula: String? get() {
        val weight = riderWeightKg ?: return null
        return level?.let { RiderProfileEstimate.ftpFormula(it, weight) }
    }

    /** The live formula under the max HR field, or null once the max HR was typed over. */
    val maxHrFormula: String? get() = age?.takeIf { maxHrEstimated }?.let { RiderProfileEstimate.maxHrFormula(it) }

    /** Null while any saved field is empty or out of range. */
    val profile: RiderProfile? get() {
        return RiderProfile(
            riderWeightKg = riderWeightKg ?: return null,
            bikeKitWeightKg = bikeKitWeightKg ?: return null,
            ftp = ftpW ?: return null,
            maxHr = maxHrBpm ?: return null
        )
    }

    fun withRiderWeight(text: String) = copy(riderWeight = text).withFtpEstimate()

    fun withBikeKitWeight(text: String) = copy(bikeKitWeight = text)

    fun withLevel(level: FtpLevel) = copy(level = level).withFtpEstimate()

    fun withFtp(text: String) = copy(ftp = text, level = null)

    fun withBirthYear(text: String): ProfileForm {
        val form = copy(birthYear = text, maxHrEstimated = true)
        return form.age?.let { form.copy(maxHr = RiderProfileEstimate.maxHr(it).toString()) } ?: form
    }

    fun withMaxHr(text: String) = copy(maxHr = text, maxHrEstimated = false)

    private fun withFtpEstimate(): ProfileForm {
        val level = level ?: return this
        val weight = riderWeightKg ?: return this
        return copy(ftp = RiderProfileEstimate.ftp(level, weight).toString())
    }

    companion object {
        const val DEFAULT_AGE = 40
        private const val MIN_AGE = 10
        private const val MAX_AGE = 100
    }
}

private fun String.parseIn(range: IntRange): Int? = trim().toIntOrNull()?.takeIf { it in range }
