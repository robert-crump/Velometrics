package com.velometrics.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_best_efforts",
    foreignKeys = [
        ForeignKey(
            entity = CyclingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId", unique = true)]
)
data class SessionBestEffortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    // Rolling-window fastest time (seconds) to cover the given distance anywhere in the ride
    val split25kSec: Double? = null,
    val split50kSec: Double? = null,
    val split100kSec: Double? = null,
    // Rolling-window best average power (watts) sustained for the given duration anywhere in the ride
    val power1s: Int? = null,
    val power3s: Int? = null,
    val power5s: Int? = null,
    val power20s: Int? = null,
    val power30s: Int? = null,
    val power1m: Int? = null,
    val power5m: Int? = null,
    val power20m: Int? = null,
    val power30m: Int? = null
)
