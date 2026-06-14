package com.velometrics.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.velometrics.app.domain.model.MapNode

@Entity(tableName = "map_nodes")
data class MapNodeEntity(
    @PrimaryKey val id: Long,
    val lat: Double,
    val lon: Double,
    val priority: String?
)

fun MapNodeEntity.toDomain(): MapNode = MapNode(id, lat, lon, priority)
