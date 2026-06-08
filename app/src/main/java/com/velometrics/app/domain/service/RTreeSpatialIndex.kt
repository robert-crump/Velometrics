package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.PolylineDecoder
import com.github.davidmoten.rtree2.Entries
import com.github.davidmoten.rtree2.RTree
import com.github.davidmoten.rtree2.geometry.Geometries
import com.github.davidmoten.rtree2.geometry.Rectangle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RTreeSpatialIndex @Inject constructor() {

    private var tree: RTree<Long, Rectangle> = RTree.create()
    private val mutex = Mutex()

    // Store edge index by fromNode-toNode key for lookup
    private var edgeIndex = 0L

    data class EdgeCandidate(
        val edgeKey: Long,
        val distanceM: Double
    )

    suspend fun rebuildIndex(edges: List<MapEdge>, nodes: Map<Long, MapNode>) {
        mutex.withLock {
            // Bulk-load via STR rather than sequential .add() calls: the latter clones and
            // re-splits the immutable tree on every insert, which OOMs on large road graphs.
            val entries = edges.mapIndexed { index, edge ->
                Entries.entry(index.toLong(), createBoundingBox(edge, nodes))
            }
            tree = RTree.create(entries)
        }
    }

    suspend fun queryEdgesNear(lat: Double, lon: Double, radiusM: Double): List<EdgeCandidate> {
        val latDelta = GeoUtils.metersToLat(radiusM)
        val lonDelta = GeoUtils.metersToLon(radiusM, lat)

        val searchBox = Geometries.rectangle(
            lon - lonDelta,
            lat - latDelta,
            lon + lonDelta,
            lat + latDelta
        )

        return mutex.withLock {
            tree.search(searchBox)
                .map { entry ->
                    val edgeKey = entry.value()
                    val bbox = entry.geometry()
                    val edgeCenterLat = (bbox.y1() + bbox.y2()) / 2
                    val edgeCenterLon = (bbox.x1() + bbox.x2()) / 2
                    val distance = GeoUtils.haversineDistance(lat, lon, edgeCenterLat, edgeCenterLon)
                    EdgeCandidate(edgeKey, distance)
                }
                .toList()
                .sortedBy { it.distanceM }
        }
    }

    private fun createBoundingBox(edge: MapEdge, nodes: Map<Long, MapNode>): Rectangle {
        val decoded = PolylineDecoder.decode(edge.geometryEncoded)

        var minLat: Double
        var maxLat: Double
        var minLon: Double
        var maxLon: Double

        if (decoded.size >= 2) {
            minLat = decoded.minOf { it.latitude }
            maxLat = decoded.maxOf { it.latitude }
            minLon = decoded.minOf { it.longitude }
            maxLon = decoded.maxOf { it.longitude }
        } else {
            // Fallback to node coordinates
            val fromNode = nodes[edge.fromNode]
            val toNode = nodes[edge.toNode]
            if (fromNode != null && toNode != null) {
                minLat = minOf(fromNode.lat, toNode.lat)
                maxLat = maxOf(fromNode.lat, toNode.lat)
                minLon = minOf(fromNode.lon, toNode.lon)
                maxLon = maxOf(fromNode.lon, toNode.lon)
            } else {
                // Should not happen, but provide a default
                minLat = 0.0; maxLat = 0.0; minLon = 0.0; maxLon = 0.0
            }
        }

        // Ensure non-zero area
        val epsilon = 0.00001
        val finalMaxLat = if (maxLat == minLat) maxLat + epsilon else maxLat
        val finalMaxLon = if (maxLon == minLon) maxLon + epsilon else maxLon

        return Geometries.rectangle(minLon, minLat, finalMaxLon, finalMaxLat)
    }
}
