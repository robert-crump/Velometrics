package com.velometrics.app.domain.model

/** Qualitative read on a ride's Coggan aerobic decoupling percentage. */
enum class CardiacDriftBand {
    GOOD, NORMAL, SIGNIFICANT;

    companion object {
        fun fromPercent(decouplingPercent: Double): CardiacDriftBand = when {
            decouplingPercent < 5.0 -> GOOD
            decouplingPercent < 10.0 -> NORMAL
            else -> SIGNIFICANT
        }
    }
}
