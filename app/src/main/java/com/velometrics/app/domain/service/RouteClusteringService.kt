package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.domain.model.SessionClusterData
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import android.util.Log
import com.velometrics.app.util.GeoUtils
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

        // Logs per-phase wall-clock timings of runClustering for on-device profiling.
        private const val PERF_LOGGING = true
    }

    // ─── Shared data structures ───

    private data class PreparedTrack(
        val sessionId: Long,
        val sampledPoints: List<List<Double>>,
        val grid: SpatialGrid,
        val centroid: List<Double>,
        val recordedDistM: Double
    )

    private fun prepareTrack(data: SessionClusterData): PreparedTrack? {
        if (data.gpsTrack == null || data.distanceKm < MIN_SESSION_DIST_KM) return null
        val raw = parseGpsTrack(data.gpsTrack) ?: return null
        if (raw.size < 4) return null
        val totalLen = trackLengthM(raw)
        if (totalLen < 100.0) return null
        val sampleCount = max(2, (totalLen / SAMPLE_SPACING_M).toInt())
        return PreparedTrack(
            sessionId = data.id,
            sampledPoints = resampleTrack(raw, sampleCount),
            grid = SpatialGrid(raw),
            centroid = computeCentroid(raw),
            recordedDistM = data.distanceKm * 1000.0
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
                "reject ${a.sessionId}/${b.sessionId}: length Δ=${lengthDelta.toInt()}m > ${lengthDeltaThresholdM.toInt()}m"
            )
            return false
        }
        val centroidDist = GeoUtils.haversineDistance(
            a.centroid[0], a.centroid[1], b.centroid[0], b.centroid[1]
        )
        if (centroidDist >= MAX_CENTROID_DIST_M) {
            if (DEBUG_LOGGING) Log.d(
                TAG,
                "reject ${a.sessionId}/${b.sessionId}: centroid dist=${centroidDist.toInt()}m ≥ ${MAX_CENTROID_DIST_M.toInt()}m"
            )
            return false
        }
        val sim = pairSimilarity(a, b)
        if (sim < SIMILARITY_THRESHOLD) {
            if (DEBUG_LOGGING) Log.d(
                TAG,
                "reject ${a.sessionId}/${b.sessionId}: similarity=${"%.3f".format(sim)} < $SIMILARITY_THRESHOLD"
            )
            return false
        }
        if (DEBUG_LOGGING) Log.d(
            TAG,
            "accept ${a.sessionId}/${b.sessionId}: sim=${"%.3f".format(sim)}, lenΔ=${lengthDelta.toInt()}m, cenΔ=${centroidDist.toInt()}m"
        )
        return true
    }

    /**
     * Bidirectional similarity: max of coverage(A→B) and coverage(B→A). Short-circuits once the
     * first direction reaches [SIMILARITY_THRESHOLD] (qualification only compares against that
     * threshold), so the second, redundant coverage scan is skipped for matching pairs.
     */
    private fun pairSimilarity(a: PreparedTrack, b: PreparedTrack): Double {
        val covAB = coverageScore(a.sampledPoints, b.grid)
        if (covAB >= SIMILARITY_THRESHOLD) return covAB
        return max(covAB, coverageScore(b.sampledPoints, a.grid))
    }

    // ─── Full clustering (single-linkage / connected components) ───

    suspend fun runClustering() = withContext(Dispatchers.Default) {
        val tStart = System.nanoTime()
        val allClusterData = sessionRepository.getAllClusterData()
        val clusterDataWithGps = allClusterData.filter { it.gpsTrack != null }

        if (clusterDataWithGps.size < MIN_GROUP_SIZE) {
            if (DEBUG_LOGGING) Log.d(TAG, "summary: ${clusterDataWithGps.size} sessions w/ GPS < MIN_GROUP_SIZE=$MIN_GROUP_SIZE — clearing all routes")
            repeatedRouteRepository.deleteAll()
            return@withContext
        }

        val tLoaded = System.nanoTime()
        val prepared = clusterDataWithGps.mapNotNull { prepareTrack(it) }
        val n = prepared.size
        val tPrepared = System.nanoTime()

        // ─── Step 1: build edge graph (qualifying pairs) ───
        // Bin tracks by centroid into MAX_CENTROID_DIST_M cells and only compare pairs in the same
        // or adjacent cells. pairQualifies always rejects centroids ≥ MAX_CENTROID_DIST_M apart, and
        // any pair outside the 3×3 neighborhood is guaranteed at least that far apart on one axis —
        // so this prunes the O(n²) sweep toward O(n·k) without changing which pairs qualify. The
        // longitude cell uses the highest-magnitude latitude (smallest cos) so a 2-cell column gap
        // always exceeds MAX_CENTROID_DIST_M at every latitude in the dataset.
        val adjacency = Array(n) { mutableListOf<Int>() }
        var edgeCount = 0
        if (n > 1) {
            val latCellSize = MAX_CENTROID_DIST_M / GeoUtils.METERS_PER_DEG_LAT
            val maxAbsLat = prepared.maxOf { abs(it.centroid[0]) }
            val lonCellSize = MAX_CENTROID_DIST_M / (GeoUtils.METERS_PER_DEG_LAT * cos(Math.toRadians(maxAbsLat)))
            val rows = LongArray(n) { floor(prepared[it].centroid[0] / latCellSize).toLong() }
            val cols = LongArray(n) { floor(prepared[it].centroid[1] / lonCellSize).toLong() }
            val buckets = HashMap<Long, MutableList<Int>>()
            for (i in 0 until n) {
                buckets.getOrPut(rows[i] * 2_000_000L + cols[i] + 1_000_000L) { mutableListOf() }.add(i)
            }
            for (i in 0 until n) {
                for (dr in -1L..1L) {
                    for (dc in -1L..1L) {
                        val bucket = buckets[(rows[i] + dr) * 2_000_000L + (cols[i] + dc) + 1_000_000L] ?: continue
                        for (j in bucket) {
                            if (j <= i) continue
                            if (pairQualifies(prepared[i], prepared[j])) {
                                adjacency[i].add(j)
                                adjacency[j].add(i)
                                edgeCount++
                            }
                        }
                    }
                }
            }
        }
        val tGraph = System.nanoTime()

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

        // ─── Step 3: load full sessions only for valid groups, then preserve names ───
        val groupSessionIds = validGroups.flatten().map { prepared[it].sessionId }.distinct()
        val fullSessionMap = sessionRepository.getSessionsByIdsList(groupSessionIds).associateBy { it.id }

        val existingRoutes = repeatedRouteRepository.getAllRoutesList()
        val newRoutes = validGroups.map { indices ->
            val sessionIdSet = indices.map { prepared[it].sessionId }.toSet()
            val sessions = indices.mapNotNull { fullSessionMap[prepared[it].sessionId] }
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

        val tCompute = System.nanoTime()
        withContext(Dispatchers.IO) {
            repeatedRouteRepository.deleteRoutesByIds(toDelete)
            for (route in finalRoutes) {
                repeatedRouteRepository.saveRoute(route)
            }
        }

        if (PERF_LOGGING) {
            fun ms(from: Long, to: Long) = (to - from) / 1_000_000
            Log.i(
                TAG,
                "perf: n=$n edges=$edgeCount groups=${validGroups.size} | " +
                    "load=${ms(tStart, tLoaded)}ms prepare=${ms(tLoaded, tPrepared)}ms " +
                    "graph=${ms(tPrepared, tGraph)}ms postprocess=${ms(tGraph, tCompute)}ms " +
                    "persist=${ms(tCompute, System.nanoTime())}ms total=${ms(tStart, System.nanoTime())}ms"
            )
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
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] +
                GeoUtils.haversineDistance(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
        }
        val totalLen = cumulative[points.size - 1]
        if (totalLen == 0.0) return points

        // cumulative is non-decreasing and targetDist increases with k, so a single forward-moving
        // index replaces the previous per-sample indexOfLast rescan — O(points + n) instead of the
        // O(points · n) that dominated route clustering's prepare phase on long tracks.
        val result = ArrayList<List<Double>>(n)
        var idx = 0
        for (k in 0 until n) {
            val targetDist = totalLen * k / (n - 1).toDouble()
            while (idx < points.size - 1 && cumulative[idx + 1] <= targetDist) idx++
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
