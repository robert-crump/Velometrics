package com.velometrics.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.velometrics.app.domain.model.MapEdge
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

@Entity(tableName = "map_edges", primaryKeys = ["from_node", "to_node"])
data class MapEdgeEntity(
    @ColumnInfo(name = "from_node") val fromNode: Long,
    @ColumnInfo(name = "to_node") val toNode: Long,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    val highway: String?,
    val name: String?,
    val surface: String?,
    @ColumnInfo(name = "is_traversed") val isTraversed: Boolean,
    @ColumnInfo(name = "geometry_encoded") val geometryEncoded: String?,
    val metadata: String?
)

private data class EdgeMetadataJson(
    @SerializedName("speed_median") val speedMedian: Double?,
    @SerializedName("speed_mean") val speedMean: Double?,
    @SerializedName("speed_count") val speedCount: Int?,
    @SerializedName("speed_p25") val speedP25: Double?,
    @SerializedName("speed_p75") val speedP75: Double?,
    @SerializedName("speed_p90") val speedP90: Double?,
    @SerializedName("power_median") val powerMedian: Double?,
    @SerializedName("power_mean") val powerMean: Double?,
    @SerializedName("power_count") val powerCount: Int?,
    @SerializedName("power_p25") val powerP25: Double?,
    @SerializedName("power_p75") val powerP75: Double?,
    @SerializedName("power_p90") val powerP90: Double?,
    @SerializedName("slope_percent") val slopePercent: Double?,
    @SerializedName("traversal_count") val traversalCount: Int?,
    @SerializedName("last_traversal") val lastTraversal: String?,
    @SerializedName("time_of_day_dist") val timeOfDayDist: List<Int>?,
    @SerializedName("avg_stop_count") val avgStopCount: Double?,
    @SerializedName("pedal_flow_count") val pedalFlowCount: Int?,
    @SerializedName("gravity_flow_count") val gravityFlowCount: Int?
)

private val gson = Gson()

fun MapEdgeEntity.toDomain(): MapEdge {
    val meta = metadata?.let {
        try { gson.fromJson(it, EdgeMetadataJson::class.java) } catch (_: Exception) {
            null
        }
    }
    return MapEdge(
        fromNode = fromNode,
        toNode = toNode,
        lengthM = lengthM,
        highway = highway ?: "",
        name = name,
        isTraversed = isTraversed,
        geometryEncoded = geometryEncoded ?: "",
        speedMedian = meta?.speedMedian,
        speedMean = meta?.speedMean,
        speedCount = meta?.speedCount,
        speedP25 = meta?.speedP25,
        speedP75 = meta?.speedP75,
        speedP90 = meta?.speedP90,
        powerMedian = meta?.powerMedian,
        powerMean = meta?.powerMean,
        powerCount = meta?.powerCount,
        powerP25 = meta?.powerP25,
        powerP75 = meta?.powerP75,
        powerP90 = meta?.powerP90,
        slopePercent = meta?.slopePercent,
        traversalCount = meta?.traversalCount,
        lastTraversal = meta?.lastTraversal,
        timeOfDayDist = meta?.timeOfDayDist,
        avgStopCount = meta?.avgStopCount,
        pedalFlowCount = meta?.pedalFlowCount,
        gravityFlowCount = meta?.gravityFlowCount
    )
}
