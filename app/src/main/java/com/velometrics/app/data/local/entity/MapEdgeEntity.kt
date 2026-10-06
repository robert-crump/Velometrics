package com.velometrics.app.data.local.entity

import com.velometrics.app.util.Json
import androidx.room.ColumnInfo
import androidx.room.Entity
import com.velometrics.app.domain.model.MapEdge
import com.google.gson.annotations.SerializedName

@Entity(tableName = "map_edges", primaryKeys = ["from_node", "to_node"])
data class MapEdgeEntity(
    @ColumnInfo(name = "from_node") val fromNode: Long,
    @ColumnInfo(name = "to_node") val toNode: Long,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    val highway: String?,
    val name: String?,
    val surface: String?,
    @ColumnInfo(name = "is_traversed", defaultValue = "0") val isTraversed: Boolean,
    @ColumnInfo(name = "geometry_encoded") val geometryEncoded: String?,
    val metadata: String?,
    @ColumnInfo(name = "slope_percent") val slopePercent: Double?,
    @ColumnInfo(name = "flow_confidence") val flowConfidence: Double?,
    @ColumnInfo(name = "predicted_gravity_flow_probability") val predictedGravityFlowProbability: Double?,
    @ColumnInfo(name = "predicted_pedal_flow_probability") val predictedPedalFlowProbability: Double?,
    @ColumnInfo(name = "cool_score") val coolScore: Double?,
    @ColumnInfo(name = "cool_confidence") val coolConfidence: Double?,
    @ColumnInfo(name = "wh_per_m") val whPerM: Double?,
    @ColumnInfo(name = "wh_per_m_source") val whPerMSource: String?,
    @ColumnInfo(name = "wh_per_m_confidence") val whPerMConfidence: Double?,
)

private data class EdgeMetadataJson(
    @SerializedName("speed_median") val speedMedian: Double?,
    @SerializedName("speed_mean") val speedMean: Double?,
    @SerializedName("speed_count") val speedCount: Int?,
    @SerializedName("power_median") val powerMedian: Double?,
    @SerializedName("power_mean") val powerMean: Double?,
    @SerializedName("power_count") val powerCount: Int?,
    @SerializedName("traversal_count") val traversalCount: Int?,
    @SerializedName("last_traversal") val lastTraversal: String?,
    @SerializedName("avg_stop_count") val avgStopCount: Double?,
    @SerializedName("pedal_flow_count") val pedalFlowCount: Int?,
    @SerializedName("gravity_flow_count") val gravityFlowCount: Int?
)


fun MapEdgeEntity.toDomain(): MapEdge {
    val meta = metadata?.let {
        try { Json.gson.fromJson(it, EdgeMetadataJson::class.java) } catch (_: Exception) {
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
        powerMedian = meta?.powerMedian,
        powerMean = meta?.powerMean,
        powerCount = meta?.powerCount,
        slopePercent = slopePercent,
        traversalCount = meta?.traversalCount,
        lastTraversal = meta?.lastTraversal,
        avgStopCount = meta?.avgStopCount,
        pedalFlowCount = meta?.pedalFlowCount,
        gravityFlowCount = meta?.gravityFlowCount,
        flowConfidence = flowConfidence,
        predictedGravityFlowProbability = predictedGravityFlowProbability,
        predictedPedalFlowProbability = predictedPedalFlowProbability,
        coolScore = coolScore,
        coolConfidence = coolConfidence,
        whPerM = whPerM,
        whPerMSource = whPerMSource,
        whPerMConfidence = whPerMConfidence,
    )
}
