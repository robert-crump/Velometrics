package com.cyclegraph.app.domain.service

import com.cyclegraph.app.domain.model.CyclingSession
import com.cyclegraph.app.domain.model.RepeatedRoute
import com.cyclegraph.app.domain.repository.CyclingSessionRepository
import com.cyclegraph.app.domain.repository.RepeatedRouteRepository
import android.util.Log
import com.cyclegraph.app.util.GeoUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class RouteClusteringService @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val repeatedRouteRepository: RepeatedRouteRepository
) {
    private val gson = Gson()
    private val trackType = object : TypeToken<List<List<Double>>>() {}.type

    companion object {
        private const val TAG = "RouteClusteringService"
        private const val SAMPLE_SPACING_M = 50.0      // sample session A every 50 m
        private const val MATCH_THRESHOLD_M = 25.0     // A sample counts as visited if B has a point within 25 m
        private const val SIMILARITY_THRESHOLD = 0.85  // pair qualifies for clustering at ≥85% similarity
        private const val MAX_LENGTH_DELTA_KM = 7.0    // absolute cap on length difference
        private const val LENGTH_DELTA_FRACTION = 0.15 // also cap at 15% of shorter route's distance
        private const val MAX_CENTROID_DIST_M = 3000.0 // routes with centroids >3 km apart are different
        private const val MIN_GROUP_SIZE = 3
        private const val MIN_SESSION_DIST_KM = 1.0    // skip bogus/accidental sessions

        // Flip to true (locally) to log per-pair rejection reasons + summary.
        private const val DEBUG_LOGGING = false
    }

    // ─── Shared data structures ───

    private data class PreparedTrack(
        val session: CyclingSession,
        val sampledPoints: List<List<Double>>,
        val grid: SpatialGrid,
        val centroid: List<Double>,
        val recordedDistM: Double
    )

    private fun prepareTrack(session: CyclingSession): PreparedTrack? {
        if (session.gpsTrack == null || session.distanceKm < MIN_SESSION_DIST_KM) return null
        val raw = parseGpsTrack(session.gpsTrack) ?: return null
        if (raw.size < 4) return null
        val totalLen = trackLengthM(raw)
        if (totalLen < 100.0) return null
        val sampleCount = max(2, (totalLen / SAMPLE_SPACING_M).toInt())
        return PreparedTrack(
            session = session,
            sampledPoints = resampleTrack(raw, sampleCount),
            grid = SpatialGrid(raw),
            centroid = computeCentroid(raw),
            recordedDistM = session.distanceKm * 1000.0
        )
    }

    /** Returns true if the pair passes pre-filters AND coverage score ≥ SIMILARITY_THRESHOLD. */
    private fun pairQualifies(a: PreparedTrack, b: PreparedTrack): Boolean {
        val lengthDeltaThresholdM = min(
            MAX_LENGTH_DELTA_KM * 1000.0,
            min(a.recordedDistM, b.recordedDistM) * LENGTH_DELTA_FRACTION
        )
        val lengthDelta = abs(a.recordedDistM - b.recordedDistM)
        if (lengthDelta > lengthDeltaThresholdM) {
            if (DEBUG_LOGGING) Log.d(
                TAG,
                "reject ${a.session.id}/${b.session.id}: length Δ=${lengthDelta.toInt()}m > ${lengthDeltaThresholdM.toInt()}m"
            )
            return false
        }
        val centroidDist = GeoUtils.haversineDistance(
            a.centroid[0], a.centroid[1], b.centroid[0], b.centroid[1]
        )
        if (centroidDist >= MAX_CENTROID_DIST_M) {
            if (DEBUG_LOGGING) Log.d(
                TAG,
                "reject ${a.session.id}/${b.session.id}: centroid dist=${centroidDist.toInt()}m ≥ ${MAX_CENTROID_DIST_M.toInt()}m"
            )
            return false
        }
        val sim = pairSimilarity(a, b)
        if (sim < SIMILARITY_THRESHOLD) {
            if (DEBUG_LOGGING) Log.d(
                TAG,
                "reject ${a.session.id}/${b.session.id}: similarity=${"%.3f".format(sim)} < $SIMILARITY_THRESHOLD"
            )
            return false
        }
        if (DEBUG_LOGGING) Log.d(
            TAG,
            "accept ${a.session.id}/${b.session.id}: sim=${"%.3f".format(sim)}, lenΔ=${lengthDelta.toInt()}m, cenΔ=${centroidDist.toInt()}m"
        )
        return true
    }

    /** Bidirectional similarity: max of coverage(A→B) and coverage(B→A). */
    private fun pairSimilarity(a: PreparedTrack, b: PreparedTrack): Double =
        max(coverageScore(a.sampledPoints, b.grid), coverageScore(b.sampledPoints, a.grid))

    // ─── Full clustering (single-linkage / connected components) ───

    suspend fun runClustering() = withContext(Dispatchers.Default) {
        val allSessions = sessionRepository.getRecentSessionsList(Int.MAX_VALUE)
        val sessionsWithGps = allSessions.filter { it.gpsTrack != null }

        if (sessionsWithGps.size < MIN_GROUP_SIZE) {
            if (DEBUG_LOGGING) Log.d(TAG, "summary: ${sessionsWithGps.size} sessions w/ GPS < MIN_GROUP_SIZE=$MIN_GROUP_SIZE — clearing all routes")
            repeatedRouteRepository.deleteAll()
            return@withContext
        }

        val prepared = sessionsWithGps.mapNotNull { prepareTrack(it) }
        val n = prepared.size

        // ─── Step 1: build edge graph (qualifying pairs) ───
        val adjacency = Array(n) { mutableListOf<Int>() }
        var edgeCount = 0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (pairQualifies(prepared[i], prepared[j])) {
                    adjacency[i].add(j)
                    adjacency[j].add(i)
                    edgeCount++
                }
            }
        }

        // ─── Step 2: connected components (single-linkage) ───
        val componentId = IntArray(n) { -1 }
        var nextComponent = 0
        for (start in 0 until n) {
            if (componentId[start] != -1) continue
            // BFS
            val queue = ArrayDeque<Int>()
            queue.add(start)
            componentId[start] = nextComponent
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (neighbor in adjacency[node]) {
                    if (componentId[neighbor] == -1) {
                        componentId[neighbor] = nextComponent
                        queue.add(neighbor)
                    }
                }
            }
            nextComponent++
        }

        val components = Array(nextComponent) { mutableListOf<Int>() }
        for (i in 0 until n) components[componentId[i]].add(i)
        val validGroups = components.filter { it.size >= MIN_GROUP_SIZE }

        if (DEBUG_LOGGING) Log.d(
            TAG,
            "summary: $n sessions → $edgeCount edges → ${components.size} components → ${validGroups.size} ≥ MIN_GROUP_SIZE=$MIN_GROUP_SIZE"
        )

        // ─── Step 3: name preservation ───
        val existingRoutes = repeatedRouteRepository.getAllRoutesList()
        val newRoutes = validGroups.map { indices ->
            val sessions = indices.map { prepared[it].session }
            val sessionIdSet = sessions.map { it.id }.toSet()
            val matchedExisting = existingRoutes.firstOrNull { existing ->
                val existingSet = existing.sessions.map { it.id }.toSet()
                existingSet.isNotEmpty() && existingSet.all { id -> id in sessionIdSet }
            }
            Triple(sessions, matchedExisting?.name ?: "", matchedExisting?.id ?: 0L)
        }

        var counter = 1
        val finalRoutes = newRoutes.map { (sessions, name, existingId) ->
            RepeatedRoute(
                id = existingId,
                name = if (name.isNotEmpty()) name else "Repeated Route ${counter++}",
                sessions = sessions,
                representativeTrack = null
            )
        }

        val matchedOldIds = newRoutes.map { (_, _, id) -> id }.filter { it != 0L }.toSet()
        val toDelete = existingRoutes.map { it.id }.filter { it !in matchedOldIds }

        withContext(Dispatchers.IO) {
            repeatedRouteRepository.deleteRoutesByIds(toDelete)
            for (route in finalRoutes) {
                repeatedRouteRepository.saveRoute(route)
            }
        }
    }

    // ─── Similarity ───

    private fun coverageScore(sampledA: List<List<Double>>, gridB: SpatialGrid): Double {
        if (sampledA.isEmpty()) return 0.0
        var visited = 0
        for (pt in sampledA) {
            if (gridB.hasPointWithin(pt[0], pt[1])) visited++
        }
        return visited.toDouble() / sampledA.size
    }

    // ─── Spatial grid ───

    private inner class SpatialGrid(points: List<List<Double>>) {
        private val latCellSize: Double
        private val lonCellSize: Double
        private val cells = HashMap<Long, MutableList<List<Double>>>()

        init {
            val avgLat = points.sumOf { it[0] } / points.size
            latCellSize = MATCH_THRESHOLD_M / 111_320.0
            lonCellSize = MATCH_THRESHOLD_M / (111_320.0 * cos(Math.toRadians(avgLat)))
            for (pt in points) {
                cells.getOrPut(cellKey(pt[0], pt[1])) { mutableListOf() }.add(pt)
            }
        }

        private fun cellKey(lat: Double, lon: Double): Long {
            val row = floor(lat / latCellSize).toLong()
            val col = floor(lon / lonCellSize).toLong()
            return row * 2_000_000L + col + 1_000_000L
        }

        fun hasPointWithin(lat: Double, lon: Double): Boolean {
            val row = floor(lat / latCellSize).toLong()
            val col = floor(lon / lonCellSize).toLong()
            for (dr in -1L..1L) {
                for (dc in -1L..1L) {
                    val bucket = cells[(row + dr) * 2_000_000L + (col + dc) + 1_000_000L]
                        ?: continue
                    for (pt in bucket) {
                        if (GeoUtils.haversineDistance(lat, lon, pt[0], pt[1]) <= MATCH_THRESHOLD_M) return true
                    }
                }
            }
            return false
        }
    }

    // ─── GPS track helpers ───

    private fun parseGpsTrack(json: String?): List<List<Double>>? {
        if (json == null) return null
        return try {
            gson.fromJson(json, trackType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPS track JSON", e)
            null
        }
    }

    private fun computeCentroid(points: List<List<Double>>): List<Double> {
        if (points.isEmpty()) return listOf(0.0, 0.0)
        val meanLat = points.sumOf { it[0] } / points.size
        val meanLon = points.sumOf { it[1] } / points.size
        return listOf(meanLat, meanLon)
    }

    private fun trackLengthM(points: List<List<Double>>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += GeoUtils.haversineDistance(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
        return total
    }

    private fun resampleTrack(points: List<List<Double>>, n: Int): List<List<Double>> {
        if (points.size <= 1) return points
        val cumulative = mutableListOf(0.0)
        for (i in 1 until points.size) {
            cumulative.add(cumulative.last() + GeoUtils.haversineDistance(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1]))
        }
        val totalLen = cumulative.last()
        if (totalLen == 0.0) return points

        val result = mutableListOf<List<Double>>()
        for (k in 0 until n) {
            val targetDist = totalLen * k / (n - 1).toDouble()
            val idx = cumulative.indexOfLast { it <= targetDist }.coerceAtLeast(0)
            if (idx >= points.size - 1) {
                result.add(points.last())
            } else {
                val segDist = cumulative[idx + 1] - cumulative[idx]
                val frac = if (segDist == 0.0) 0.0 else (targetDist - cumulative[idx]) / segDist
                result.add(listOf(
                    points[idx][0] + frac * (points[idx + 1][0] - points[idx][0]),
                    points[idx][1] + frac * (points[idx + 1][1] - points[idx][1])
                ))
            }
        }
        return result
    }

}
