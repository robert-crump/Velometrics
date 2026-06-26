package com.velometrics.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "corridors")
data class CorridorEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "entry_node") val entryNode: Long,
    @ColumnInfo(name = "exit_node") val exitNode: Long,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    @ColumnInfo(name = "pedal_reward") val pedalReward: Double,
    @ColumnInfo(name = "gravity_reward") val gravityReward: Double,
    @ColumnInfo(name = "exit_hazard_score") val exitHazardScore: Double,
    @ColumnInfo(name = "centroid_lat") val centroidLat: Double,
    @ColumnInfo(name = "centroid_lon") val centroidLon: Double,
    @ColumnInfo(name = "edge_list") val edgeList: String,
    val popularity: Int,
    @ColumnInfo(name = "group_id") val groupId: Long,
)
