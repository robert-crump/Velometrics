package com.cyclegraph.app.domain.model

import com.cyclegraph.app.util.CyclingConstants
import kotlin.math.roundToInt

data class SessionEnergy(
    val totalKcal: Int,
    val fatGrams: Double,
    val carbGrams: Double
) {
    fun formatTotalKcal(): String = "${withThousandsSeparator(totalKcal)} kcal"

    fun formatFatCarbGrams(): String =
        "%.0fg / %.0fg".format(fatGrams, carbGrams)

    companion object {
        fun from(session: CyclingSession): SessionEnergy? {
            val fat = session.fatBurnedGrams ?: return null
            val carb = session.carbsBurnedGrams ?: return null
            val kcal = fat * CyclingConstants.KCAL_PER_GRAM_FAT +
                       carb * CyclingConstants.KCAL_PER_GRAM_CARB
            return SessionEnergy(
                totalKcal = kcal.roundToInt(),
                fatGrams = fat,
                carbGrams = carb
            )
        }

        private fun withThousandsSeparator(n: Int): String =
            n.toString().reversed().chunked(3).joinToString(".").reversed()
    }
}

val CyclingSession.energy: SessionEnergy?
    get() = SessionEnergy.from(this)
