package com.velometrics.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.velometrics.app.domain.model.Corridor

@Entity(tableName = "corridors")
data class CorridorEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "entry_node") val entryNode: Long,
    @ColumnInfo(name = "exit_node") val exitNode: Long,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    @ColumnInfo(name = "pedal_reward") val pedalReward: Double,
    @ColumnInfo(name = "gravity_reward") val gravityReward: Double,
    @ColumnInfo(name = "predicted_reward") val predictedReward: Double,
    @ColumnInfo(name = "exit_hazard_score") val exitHazardScore: Double,
    val type: String,
    @ColumnInfo(name = "centroid_lat") val centroidLat: Double,
    @ColumnInfo(name = "centroid_lon") val centroidLon: Double,
)

fun CorridorEntity.toDomain(): Corridor = Corridor(
    id = id,
    entryNode = entryNode,
    exitNode = exitNode,
    lengthM = lengthM,
    pedalReward = pedalReward,
    gravityReward = gravityReward,
    predictedReward = predictedReward,
    exitHazardScore = exitHazardScore,
    type = type,
    centroidLat = centroidLat,
    centroidLon = centroidLon,
)
