package com.velometrics.app.domain.model

import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import kotlin.math.roundToInt

data class SessionEnergy(
    val totalKcal: Int,
    val fatGrams: Double,
    val carbGrams: Double
) {
    fun formatTotalKcal(): String = "${FormatUtils.formatWithThousandsSeparator(totalKcal)} kcal"

    fun formatFatCarbGrams(): String =
        "%.0fg / %.0fg".format(fatGrams, carbGrams)

    companion object {
        fun from(session: CyclingSession): SessionEnergy? =
            from(session.fatBurnedGrams, session.carbsBurnedGrams)

        fun from(fatBurnedGrams: Double?, carbsBurnedGrams: Double?): SessionEnergy? {
            val fat = fatBurnedGrams ?: return null
            val carb = carbsBurnedGrams ?: return null
            val kcal = fat * CyclingConstants.KCAL_PER_GRAM_FAT +
                       carb * CyclingConstants.KCAL_PER_GRAM_CARB
            return SessionEnergy(
                totalKcal = kcal.roundToInt(),
                fatGrams = fat,
                carbGrams = carb
            )
        }
    }
}

val CyclingSession.energy: SessionEnergy?
    get() = SessionEnergy.from(this)
