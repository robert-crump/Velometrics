package com.velometrics.app.data.graphimport

import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class GraphImportResult(
    val metadata: GraphMetadata,
    val nodes: List<MapNode>,
    val edges: List<MapEdge>
)

@Singleton
class GraphJsonImporter @Inject constructor() {

    private val gson = Gson()

    fun import(inputStream: InputStream): GraphImportResult {
        val reader = InputStreamReader(inputStream)
        val raw = gson.fromJson<RawGraphExport>(reader, RawGraphExport::class.java)

        val metadata = GraphMetadata(
            createdAt = raw.metadata.created_at,
            bbox = raw.metadata.bbox,
            nodeCount = raw.metadata.node_count,
            edgeCount = raw.metadata.edge_count,
            traversedEdgeCount = raw.metadata.traversed_edge_count,
            trackCount = raw.metadata.track_count
        )

        val nodes = raw.nodes.map { n ->
            MapNode(id = n.id, lat = n.lat, lon = n.lon)
        }

        val edges = raw.edges.map { e ->
            MapEdge(
                fromNode = e.from_node,
                toNode = e.to_node,
                lengthM = e.length_m,
                highway = e.highway,
                name = e.name,
                isTraversed = e.is_traversed,
                geometryEncoded = e.geometry_encoded,
                speedMedian = e.speed_median,
                speedMean = e.speed_mean,
                speedCount = e.speed_count,
                speedP25 = e.speed_p25,
                speedP75 = e.speed_p75,
                speedP90 = e.speed_p90,
                powerMedian = e.power_median,
                powerMean = e.power_mean,
                powerCount = e.power_count,
                powerP25 = e.power_p25,
                powerP75 = e.power_p75,
                powerP90 = e.power_p90,
                slopePercent = e.slope_percent,
                traversalCount = e.traversal_count,
                lastTraversal = e.last_traversal,
                timeOfDayDist = e.time_of_day_dist,
                stopCount = e.stop_count,
                avgStopDurationS = e.avg_stop_duration_s,
                stopProbability = e.stop_probability,
                estimatedStopTimeS = e.estimated_stop_time_s
            )
        }

        return GraphImportResult(metadata, nodes, edges)
    }

    // Raw JSON data classes matching the Python export format
    private data class RawGraphExport(
        val metadata: RawMetadata,
        val nodes: List<RawNode>,
        val edges: List<RawEdge>
    )

    private data class RawMetadata(
        val created_at: String,
        val bbox: List<Double>,
        val node_count: Int,
        val edge_count: Int,
        val traversed_edge_count: Int,
        val track_count: Int
    )

    private data class RawNode(
        val id: Long,
        val lat: Double,
        val lon: Double
    )

    private data class RawEdge(
        val from_node: Long,
        val to_node: Long,
        val length_m: Double,
        val highway: String,
        val name: String?,
        val is_traversed: Boolean,
        val geometry_encoded: String,
        val speed_median: Double?,
        val speed_mean: Double?,
        val speed_count: Int?,
        val speed_p25: Double?,
        val speed_p75: Double?,
        val speed_p90: Double?,
        val power_median: Double?,
        val power_mean: Double?,
        val power_count: Int?,
        val power_p25: Double?,
        val power_p75: Double?,
        val power_p90: Double?,
        val slope_percent: Double?,
        val traversal_count: Int?,
        val last_traversal: String?,
        val time_of_day_dist: List<Int>?,
        val stop_count: Int?,
        val avg_stop_duration_s: Double?,
        val stop_probability: Double?,
        val estimated_stop_time_s: Double?
    )
}
