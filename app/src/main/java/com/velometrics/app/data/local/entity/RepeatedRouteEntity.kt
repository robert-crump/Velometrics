package com.velometrics.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "repeated_routes")
data class RepeatedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sessionIds: String,  // JSON array of Long IDs (sorted ascending)
    val createdAt: Long,     // epoch ms; for stable display ordering
    val isCustomName: Boolean  // false while `name` is still the auto-generated default (#213)
)
