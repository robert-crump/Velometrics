package com.cyclegraph.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cycling_sessions",
    indices = [Index("fileSha1", unique = true)]
)
data class CyclingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileSha1: String,
    val sessionStart: Long,              // Instant as epoch millis
    val sessionEnd: Long,
    val totalDurationSec: Int,
    val pauseDurationSec: Int,
    val netDurationSec: Int,
    val distanceKm: Double,
    val averagePower: Int?,
    val normalizedPower: Int?,
    val fatBurnedGrams: Double?,
    val carbsBurnedGrams: Double?,
    val powerZoneDistribution: String?,  // JSON map
    val speedHistogram: String,          // JSON map
    val intervalCount: Int,
    val intervalTotalTimeSec: Int,
    val gpsQualityPercent: Double,
    val powerQualityPercent: Double?,
    val hasPower: Boolean,
    val gpsTrack: String? = null,
    val fatEfficiencyHistogram: String? = null,  // JSON map
    val fatEfficiencyScore: Int? = null,
    val sprintCount: Int = 0,
    val sprintHistogram: String? = null          // JSON map
)
