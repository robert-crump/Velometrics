package com.velometrics.app.util

import kotlin.math.*

object GeoUtils {
    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_DEG_LAT = 111_320.0

    /**
     * Convert Garmin FIT semicircles to degrees
     */
    fun semicirclesToDeg(semicircles: Long): Double {
        return semicircles * (180.0 / (1L shl 31))
    }

    /**
     * Calculate distance between two lat/lon coordinates using Haversine formula
     * @return distance in meters
     */
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_M * c
    }

    /**
     * Calculate meters per degree of longitude at given latitude
     */
    fun metersPerDegLon(lat: Double): Double {
        return METERS_PER_DEG_LAT * cos(Math.toRadians(lat))
    }

    /**
     * Convert meters to latitude difference
     */
    fun metersToLat(meters: Double): Double {
        return meters / METERS_PER_DEG_LAT
    }

    /**
     * Convert meters to longitude difference at given reference latitude
     */
    fun metersToLon(meters: Double, refLat: Double): Double {
        return meters / metersPerDegLon(refLat)
    }

    /**
     * Compute bearing from point 1 to point 2
     * @return bearing in degrees [0, 360)
     */
    fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        val bearingRad = atan2(y, x)
        val bearingDeg = Math.toDegrees(bearingRad)

        return (bearingDeg + 360) % 360
    }

    /**
     * Calculate fat burn rate in kcal per second based on power
     * Uses polynomial: (a*W² + b*W + c) / 3600, clamped >= 0
     */
    fun fatBurnKcalPerSec(powerW: Double): Double {
        val kcalPerHour = CyclingConstants.FAT_A * powerW.pow(2) +
                          CyclingConstants.FAT_B * powerW +
                          CyclingConstants.FAT_C
        return maxOf(0.0, kcalPerHour / 3600.0)
    }

    /**
     * Calculate carbohydrate burn rate in kcal per second based on power
     * Uses polynomial: (a*W⁴ + b*W³ + c*W² + d*W + e) / 3600, clamped >= 0
     */
    fun carbBurnKcalPerSec(powerW: Double): Double {
        val kcalPerHour = CyclingConstants.CARB_A * powerW.pow(4) +
                          CyclingConstants.CARB_B * powerW.pow(3) +
                          CyclingConstants.CARB_C * powerW.pow(2) +
                          CyclingConstants.CARB_D * powerW +
                          CyclingConstants.CARB_E
        return maxOf(0.0, kcalPerHour / 3600.0)
    }
}
