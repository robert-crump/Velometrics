package com.velometrics.app.domain.service

enum class RideDirection(val label: String, val bearingCenter: Double) {
    NORTH("North", 0.0),
    EAST("East", 90.0),
    SOUTH("South", 180.0),
    WEST("West", 270.0),
}
