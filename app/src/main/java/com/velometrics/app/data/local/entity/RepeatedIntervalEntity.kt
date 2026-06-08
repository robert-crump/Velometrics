package com.velometrics.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "repeated_intervals")
data class RepeatedIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val intervalIds: String,    // JSON array of Long IDs (sorted ascending)
    val edges: String,          // JSON array of [fromNode, toNode] pairs, in sequence order
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val distanceM: Double,
    val createdAt: Long         // epoch ms; for stable display ordering
)
