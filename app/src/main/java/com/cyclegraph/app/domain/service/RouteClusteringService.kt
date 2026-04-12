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
        private const val MAX_LENGTH_DELTA_KM = 5.0    // absolute cap on length difference
        private const val LENGTH_DELTA_FRACTION = 0.10 // also cap at 10% of shorter route's distance
        private const val MAX_CENTROID_DIST_M = 3000.0 // routes with centroids >3 km apart are different
        private const val MIN_GROUP_SIZE = 3
        private const val MIN_SESSION_DIST_KM = 1.0    // skip bogus/accidental sessions
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
        if (abs(a.recordedDistM - b.recordedDistM) > lengthDeltaThresholdM) return false
        if (GeoUtils.haversineDistance(a.centroid[0], a.centroid[1], b.centroid[0], b.centroid[1]) >= MAX_CENTROID_DIST_M) return false
        return pairSimilarity(a, b) >= SIMILARITY_THRESHOLD
    }

    /** Bidirectional similarity: max of coverage(A→B) and coverage(B→A). */
    private fun pairSimilarity(a: PreparedTrack, b: PreparedTrack): Double =
        max(coverageScore(a.sampledPoints, b.grid), coverageScore(b.sampledPoints, a.grid))

    // ─── Full clustering (used by pull-to-refresh) ───

    suspend fun runClustering() = withContext(Dispatchers.Default) {
        val allSessions = sessionRepository.getRecentSessionsList(Int.MAX_VALUE)
        val sessionsWithGps = allSessions.filter { it.gpsTrack != null }

        if (sessionsWithGps.size < MIN_GROUP_SIZE) {
            repeatedRouteRepository.deleteAll()
            return@withContext
        }

        val prepared = sessionsWithGps.mapNotNull { prepareTrack(it) }
        val n = prepared.size

        // ─── Step 1: compute qualifying pairs ───
        val edgeSet = HashSet<Long>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (pairQualifies(prepared[i], prepared[j])) {
                    edgeSet.add(packEdge(i, j))
                }
            }
        }

        // ─── Step 2: complete-linkage agglomerative clustering ───
        val clusters = (0 until n).map { mutableListOf(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in 0 until clusters.size) {
                for (j in i + 1 until clusters.size) {
                    if (allPairsMatch(clusters[i], clusters[j], edgeSet)) {
                        clusters[i].addAll(clusters[j])
                        clusters.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }

        val validGroups = clusters.filter { it.size >= MIN_GROUP_SIZE }

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

    // ─── Incremental clustering (used after each import) ───

    suspend fun runIncrementalClustering(newSessionId: Long) = withContext(Dispatchers.Default) {
        val newSession = withContext(Dispatchers.IO) {
            sessionRepository.getSessionById(newSessionId)
        } ?: return@withContext

        val newPrepared = prepareTrack(newSession) ?: return@withContext

        val existingRoutes = withContext(Dispatchers.IO) {
            repeatedRouteRepository.getAllRoutesList()
        }

        // ─── Step 1: try to add to an existing cluster (complete-linkage) ───
        data class RouteCandidate(val route: RepeatedRoute, val avgSimilarity: Double)
        val routeCandidates = mutableListOf<RouteCandidate>()

        for (route in existingRoutes) {
            val routePrepared = route.sessions.mapNotNull { prepareTrack(it) }
            if (routePrepared.isEmpty()) continue

            var allCompatible = true
            var totalSim = 0.0
            for (rp in routePrepared) {
                if (!pairQualifies(newPrepared, rp)) {
                    allCompatible = false
                    break
                }
                totalSim += pairSimilarity(newPrepared, rp)
            }
            if (allCompatible) {
                routeCandidates.add(RouteCandidate(route, totalSim / routePrepared.size))
            }
        }

        if (routeCandidates.isNotEmpty()) {
            val best = routeCandidates.maxByOrNull { it.avgSimilarity }!!
            val updatedRoute = best.route.copy(sessions = best.route.sessions + newSession)
            withContext(Dispatchers.IO) { repeatedRouteRepository.saveRoute(updatedRoute) }
            return@withContext
        }

        // ─── Step 2: compare with cluster-less sessions ───
        val allRouteSessionIds = existingRoutes.flatMap { r -> r.sessions.map { it.id } }.toSet()
        val clusterlessSessions = withContext(Dispatchers.IO) {
            sessionRepository.getRecentSessionsList(Int.MAX_VALUE)
                .filter { it.id != newSessionId && it.id !in allRouteSessionIds }
        }

        val compatible = clusterlessSessions.mapNotNull { s ->
            val p = prepareTrack(s) ?: return@mapNotNull null
            if (pairQualifies(newPrepared, p)) p else null
        }

        if (compatible.size < MIN_GROUP_SIZE - 1) return@withContext

        // ─── Step 3: complete-linkage over workSet (new session + compatible cluster-less) ───
        val workSet = listOf(newPrepared) + compatible
        val n = workSet.size
        val edgeSet = HashSet<Long>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (pairQualifies(workSet[i], workSet[j])) {
                    edgeSet.add(packEdge(i, j))
                }
            }
        }

        val clusters = (0 until n).map { mutableListOf(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in 0 until clusters.size) {
                for (j in i + 1 until clusters.size) {
                    if (allPairsMatch(clusters[i], clusters[j], edgeSet)) {
                        clusters[i].addAll(clusters[j])
                        clusters.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }

        val newSessionCluster = clusters.first { 0 in it }
        if (newSessionCluster.size >= MIN_GROUP_SIZE) {
            val sessions = newSessionCluster.map { workSet[it].session }
            val routeNumber = existingRoutes.size + 1
            val newRoute = RepeatedRoute(
                id = 0L,
                name = "Repeated Route $routeNumber",
                sessions = sessions,
                representativeTrack = null
            )
            withContext(Dispatchers.IO) { repeatedRouteRepository.saveRoute(newRoute) }
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

    // ─── Edge helpers ───

    private fun packEdge(i: Int, j: Int): Long =
        if (i < j) i.toLong().shl(32) or j.toLong()
        else j.toLong().shl(32) or i.toLong()

    private fun allPairsMatch(c1: List<Int>, c2: List<Int>, edges: HashSet<Long>): Boolean {
        for (a in c1) for (b in c2) {
            if (packEdge(a, b) !in edges) return false
        }
        return true
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
