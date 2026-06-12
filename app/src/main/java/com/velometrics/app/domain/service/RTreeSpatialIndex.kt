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
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RTreeSpatialIndex @Inject constructor() {

    private var tree: RTree<Long, Rectangle> = RTree.create()
    private val mutex = Mutex()

    // Decoded polyline geometry per edge, indexed by edgeKey (== edge index). Decoded once per
    // rebuildIndex so per-point queries don't re-decode the same polylines repeatedly.
    private var edgeGeometries: List<List<LatLng>> = emptyList()

    data class EdgeCandidate(
        val edgeKey: Long,
        val distanceM: Double,
        val bearingDeg: Double
    )

    suspend fun rebuildIndex(edges: List<MapEdge>, nodes: Map<Long, MapNode>) {
        mutex.withLock {
            edgeGeometries = edges.map { edge -> decodeGeometry(edge, nodes) }

            // Bulk-load via STR rather than sequential .add() calls: the latter clones and
            // re-splits the immutable tree on every insert, which OOMs on large road graphs.
            val entries = edgeGeometries.mapIndexed { index, geometry ->
                Entries.entry(index.toLong(), createBoundingBox(geometry))
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
                    val geometry = edgeGeometries[edgeKey.toInt()]
                    val (distance, bearing) = nearestSegment(lat, lon, geometry)
                    EdgeCandidate(edgeKey, distance, bearing)
                }
                .toList()
                .sortedBy { it.distanceM }
        }
    }

    /**
     * Finds the segment of [geometry] closest to (lat, lon) and returns its perpendicular
     * distance and bearing. [geometry] always has at least 2 points (see [decodeGeometry]).
     */
    private fun nearestSegment(lat: Double, lon: Double, geometry: List<LatLng>): Pair<Double, Double> {
        var bestDist = Double.MAX_VALUE
        var bestBearing = 0.0
        for (i in 0 until geometry.size - 1) {
            val p1 = geometry[i]
            val p2 = geometry[i + 1]
            val dist = GeoUtils.pointToSegmentDistance(lat, lon, p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            if (dist < bestDist) {
                bestDist = dist
                bestBearing = GeoUtils.computeBearing(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            }
        }
        return bestDist to bestBearing
    }

    /** Decodes an edge's polyline geometry, falling back to the straight fromNode-toNode line. */
    private fun decodeGeometry(edge: MapEdge, nodes: Map<Long, MapNode>): List<LatLng> {
        val decoded = PolylineDecoder.decode(edge.geometryEncoded)
        if (decoded.size >= 2) return decoded

        val fromNode = nodes[edge.fromNode]
        val toNode = nodes[edge.toNode]
        return if (fromNode != null && toNode != null) {
            listOf(LatLng(fromNode.lat, fromNode.lon), LatLng(toNode.lat, toNode.lon))
        } else {
            // Should not happen, but provide a default
            listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0))
        }
    }

    private fun createBoundingBox(geometry: List<LatLng>): Rectangle {
        val minLat = geometry.minOf { it.latitude }
        val maxLat = geometry.maxOf { it.latitude }
        val minLon = geometry.minOf { it.longitude }
        val maxLon = geometry.maxOf { it.longitude }

        // Ensure non-zero area
        val epsilon = 0.00001
        val finalMaxLat = if (maxLat == minLat) maxLat + epsilon else maxLat
        val finalMaxLon = if (maxLon == minLon) maxLon + epsilon else maxLon

        return Geometries.rectangle(minLon, minLat, finalMaxLon, finalMaxLat)
    }
}
