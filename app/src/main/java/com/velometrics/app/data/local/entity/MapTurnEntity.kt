package com.velometrics.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.velometrics.app.domain.model.MapTurn

@Entity(tableName = "map_turns", primaryKeys = ["from_node", "junction_node", "to_node"])
data class MapTurnEntity(
    @ColumnInfo(name = "from_node") val fromNode: Long,
    @ColumnInfo(name = "junction_node") val junctionNode: Long,
    @ColumnInfo(name = "to_node") val toNode: Long,
    @ColumnInfo(name = "hazard_score") val hazardScore: Double,
    @ColumnInfo(name = "hazard_source") val hazardSource: String,
    @ColumnInfo(name = "stop_penalty") val stopPenalty: Double,
    @ColumnInfo(name = "stop_penalty_source") val stopPenaltySource: String,
    @ColumnInfo(name = "braking_probability") val brakingProbability: Double?,
    @ColumnInfo(name = "median_ke_delta") val medianKeDelta: Double?,
    @ColumnInfo(name = "stop_penalty_confidence") val stopPenaltyConfidence: Double?,
)

fun MapTurnEntity.toDomain(): MapTurn = MapTurn(
    fromNode = fromNode,
    junctionNode = junctionNode,
    toNode = toNode,
    hazardScore = hazardScore,
    hazardSource = hazardSource,
    stopPenalty = stopPenalty,
    stopPenaltySource = stopPenaltySource,
    brakingProbability = brakingProbability,
    medianKeDelta = medianKeDelta,
    stopPenaltyConfidence = stopPenaltyConfidence,
)
