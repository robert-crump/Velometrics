package com.velometrics.app.domain.model

/** A likely cause of a ride's elevated cardiac drift (#222), in ranking order (strongest first). */
enum class CardiacDriftCause {
    HEAT, INTENSITY, DURATION
}
