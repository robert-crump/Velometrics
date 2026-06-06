package com.velometrics.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "interval_sessions",
    foreignKeys = [
        ForeignKey(
            entity = CyclingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["cyclingSessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = IntervalPrototypeRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["prototypeRouteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("cyclingSessionId"), Index("prototypeRouteId")]
)
data class IntervalSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cyclingSessionId: Long,
    val startTimestamp: Long,            // Instant as epoch millis
    val durationSec: Int,
    val durationNormalizedSec: Int,
    val distanceM: Double,
    val avgPower: Int,
    val avgSpeedKmh: Double,
    val avgSpeedNormalizedKmh: Double,
    val direction: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val gpsTrack: String,                // JSON: [[lat,lon],[lat,lon],...]
    val prototypeRouteId: Long? = null
)
