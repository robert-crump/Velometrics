package com.velometrics.app.domain.model

/** Qualitative read on a ride's Coggan aerobic decoupling percentage; MEDIUM and HIGH get advice (#222). */
enum class CardiacDriftBand {
    LOW, MEDIUM, HIGH;

    companion object {
        fun fromPercent(decouplingPercent: Double): CardiacDriftBand = when {
            decouplingPercent < 5.0 -> LOW
            decouplingPercent < 10.0 -> MEDIUM
            else -> HIGH
        }
    }
}
