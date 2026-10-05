package com.velometrics.app.domain.model

/**
 * One of the 101 equal-distance samples (100 in series stored before #235) of a ride's heart rate / elevation profile (#204).
 * [heartRate] and [altitudeM] are null when no record near the sample carried a value.
 */
data class HrDistancePoint(
    val distanceKm: Double,
    val heartRate: Int?,
    val altitudeM: Double?
)
