package com.cyclegraph.app.domain.model

import java.time.Instant

data class CyclingSession(
    val id: Long = 0,
    val fileName: String,
    val fileSha1: String,
    val sessionStart: Instant,
    val sessionEnd: Instant,
    val totalDurationSec: Int,
    val pauseDurationSec: Int,
    val netDurationSec: Int,
    val distanceKm: Double,
    val averagePower: Int?,
    val normalizedPower: Int?,
    val fatBurnedGrams: Double?,
    val carbsBurnedGrams: Double?,
    val powerZoneDistribution: Map<String, Int>?,
    val speedHistogram: Map<String, Int>,
    val intervalCount: Int,
    val intervalTotalTimeSec: Int,
    val gpsQualityPercent: Double,
    val powerQualityPercent: Double?,
    val hasPower: Boolean,
    val gpsTrack: String? = null,
    val fatEfficiencyHistogram: Map<String, Int>? = null,
    val fatEfficiencyScore: Int? = null,
    val sprintCount: Int = 0,
    val sprintHistogram: Map<String, Int>? = null
)
