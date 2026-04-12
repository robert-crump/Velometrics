package com.cyclegraph.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "repeated_routes")
data class RepeatedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sessionIds: String,  // JSON array of Long IDs (sorted ascending)
    val createdAt: Long      // epoch ms; for stable display ordering
)
