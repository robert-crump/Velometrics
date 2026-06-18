package com.velometrics.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.velometrics.app.domain.model.CorridorConnector

@Entity(tableName = "corridor_connectors", primaryKeys = ["from_corridor", "to_corridor"])
data class CorridorConnectorEntity(
    @ColumnInfo(name = "from_corridor") val fromCorridor: Long,
    @ColumnInfo(name = "to_corridor") val toCorridor: Long,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
)

fun CorridorConnectorEntity.toDomain(): CorridorConnector = CorridorConnector(
    fromCorridor = fromCorridor,
    toCorridor = toCorridor,
    distanceM = distanceM,
)
