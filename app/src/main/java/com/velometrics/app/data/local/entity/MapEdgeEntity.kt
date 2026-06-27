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
    @ColumnInfo(name = "is_traversed", defaultValue = "0") val isTraversed: Boolean,
    @ColumnInfo(name = "geometry_encoded") val geometryEncoded: String?,
    val metadata: String?,
    @ColumnInfo(name = "slope_percent") val slopePercent: Double?,
    val curvature: Double?,
    @ColumnInfo(name = "stop_penalty") val stopPenalty: Double?,
    @ColumnInfo(name = "stop_penalty_source") val stopPenaltySource: String?,
    @ColumnInfo(name = "flow_confidence") val flowConfidence: Double?,
    @ColumnInfo(name = "hazard_score") val hazardScore: Double?,
    @ColumnInfo(name = "braking_probability") val brakingProbability: Double?,
    @ColumnInfo(name = "maxspeed_kmh") val maxspeedKmh: Double?,
    @ColumnInfo(name = "median_ke_delta") val medianKeDelta: Double?,
    @ColumnInfo(name = "predicted_gravity_flow_probability") val predictedGravityFlowProbability: Double?,
    @ColumnInfo(name = "predicted_pedal_flow_probability") val predictedPedalFlowProbability: Double?,
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
        slopePercent = slopePercent,
        traversalCount = meta?.traversalCount,
        lastTraversal = meta?.lastTraversal,
        timeOfDayDist = meta?.timeOfDayDist,
        avgStopCount = meta?.avgStopCount,
        pedalFlowCount = meta?.pedalFlowCount,
        gravityFlowCount = meta?.gravityFlowCount,
        curvature = curvature,
        stopPenalty = stopPenalty,
        stopPenaltySource = stopPenaltySource,
        flowConfidence = flowConfidence,
        hazardScore = hazardScore,
        brakingProbability = brakingProbability,
        maxspeedKmh = maxspeedKmh,
        medianKeDelta = medianKeDelta,
        predictedGravityFlowProbability = predictedGravityFlowProbability,
        predictedPedalFlowProbability = predictedPedalFlowProbability,
    )
}
