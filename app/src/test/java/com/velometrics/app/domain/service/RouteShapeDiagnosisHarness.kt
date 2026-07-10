package com.velometrics.app.domain.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.velometrics.app.data.local.CyclingAssetDatabase
import com.velometrics.app.data.repository.MapGraphRepositoryImpl
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.repository.MapGraphRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.PrintStream

/**
 * Issue #129 diagnosis harness — NOT a regression test. Skipped unless ROUTE_SHAPE_DIAGNOSIS=1 is
 * set in the environment, so normal `gradlew test` runs never pay for it.
 *
 * Runs [RouteGenerator.generate] against the full production `cycling_graph.db` asset on the JVM
 * and, per (distance, direction) run, writes to `app/build/reports/route-shape-diagnosis/`:
 *  - `<run>.log`      — the generator's own Log.d stream (per-stage timings, tier decisions)
 *  - `<run>.geojson`  — home point, coarse skeleton (corridor centroids in visit order), final
 *                       route split into corridor edges vs connector/exit/return edges
 *  - `summary.md`     — one row per run with the #129 shape metrics and wall time
 *
 * Environment overrides:
 *  - ROUTE_SHAPE_HOME="lat,lon"           (default: home area rounded off the exact address)
 *  - ROUTE_SHAPE_DISTANCES_KM="20,50,80"
 *  - ROUTE_SHAPE_DIRECTIONS="NONE,NORTH"  (RideDirection names, NONE = no direction preference)
 *
 * Run: `$env:ROUTE_SHAPE_DIAGNOSIS="1"; ./gradlew :app:testDebugUnitTest --tests "*RouteShapeDiagnosisHarness"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RouteShapeDiagnosisHarness {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private var db: CyclingAssetDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `generate routes against the real graph and dump shape diagnostics`() {
        assumeTrue(
            "set ROUTE_SHAPE_DIAGNOSIS=1 to run the #129 diagnosis harness",
            System.getenv("ROUTE_SHAPE_DIAGNOSIS") == "1",
        )

        val home = (System.getenv("ROUTE_SHAPE_HOME") ?: DEFAULT_HOME).split(",")
        val homeLat = home[0].trim().toDouble()
        val homeLon = home[1].trim().toDouble()
        val distancesKm = (System.getenv("ROUTE_SHAPE_DISTANCES_KM") ?: "20,50,80")
            .split(",").map { it.trim().toDouble() }
        val directions = (System.getenv("ROUTE_SHAPE_DIRECTIONS") ?: "NONE")
            .split(",").map { it.trim().uppercase() }
            .map { if (it == "NONE") null else RideDirection.valueOf(it) }

        val outDir = File(System.getProperty("user.dir"), "build/reports/route-shape-diagnosis")
        outDir.mkdirs()

        context.deleteDatabase(DB_NAME)
        val database = Room.databaseBuilder(context, CyclingAssetDatabase::class.java, DB_NAME)
            .createFromAsset("cycling_graph.db")
            .addCallback(CyclingAssetDatabase.schemaVersionCallback())
            .fallbackToDestructiveMigration()
            .build()
        db = database
        val repository = MapGraphRepositoryImpl(
            database.mapNodeDao(), database.mapEdgeDao(), database.mapTurnDao(),
            database.poiDao(), database.mapMetadataDao(), database.corridorDao(),
        )

        val summary = StringBuilder()
        summary.appendLine("# Route shape diagnosis (issue #129)")
        summary.appendLine()
        summary.appendLine("Home: $homeLat, $homeLon — seed $SEED — ${java.time.Instant.now()}")
        summary.appendLine()
        summary.appendLine("| run | target | actual | dev% | tier | repeat | rawRepeat | compact | repRoads | wall |")
        summary.appendLine("|---|---|---|---|---|---|---|---|---|---|")

        for (direction in directions) {
            for (km in distancesKm) {
                val runId = "d${km.toInt()}km_${direction?.name?.lowercase() ?: "any"}"
                val logStream = PrintStream(File(outDir, "$runId.log"))
                ShadowLog.stream = logStream

                val startMs = System.currentTimeMillis()
                val result = runBlocking {
                    RouteGenerator.generate(
                        homeLat, homeLon, km * 1000.0, repository,
                        config = GeneratorConfig(direction = direction, seed = SEED),
                    )
                }
                val wallMs = System.currentTimeMillis() - startMs
                ShadowLog.stream = null
                logStream.close()

                when (result) {
                    is RoutePlanResult.Failure -> {
                        summary.appendLine("| $runId | ${km.toInt()}km | FAILED: ${result.reason} | | | | | | | ${wallMs / 1000}s |")
                        println("$runId: FAILED in ${wallMs / 1000}s — ${result.reason}")
                    }
                    is RoutePlanResult.Success -> {
                        val candidate = result.candidate
                        val edges = candidate.refinedRoute.edges
                        val nodeCoords = runBlocking { resolveNodeCoords(repository, edges) }
                        val report = RouteShapeMetrics.evaluate(edges, nodeCoords)

                        val allCorridors = runBlocking { repository.getAllCorridors() }.associateBy { it.id } +
                            candidate.coarseLoop.syntheticCorridors
                        File(outDir, "$runId.geojson").writeText(
                            buildGeoJson(homeLat, homeLon, candidate, allCorridors, nodeCoords),
                        )

                        val row = "| $runId | ${km.toInt()}km | ${(report.totalLengthM / 1000).format1()}km " +
                            "| ${candidate.distanceDeviationPercent.format1()} | ${result.appliedTier} " +
                            "| ${report.repeatFraction.format2()} | ${report.rawRepeatFraction.format2()} " +
                            "| ${report.compactness.format2()} | ${report.repeatedRoadCount} | ${wallMs / 1000}s |"
                        summary.appendLine(row)
                        println("$runId: ${(report.totalLengthM / 1000).format1()}km repeat=${report.repeatFraction.format2()} raw=${report.rawRepeatFraction.format2()} compact=${report.compactness.format2()} in ${wallMs / 1000}s")
                    }
                }
            }
        }

        File(outDir, "summary.md").writeText(summary.toString())
        println()
        println(summary)
        println("Artifacts written to ${outDir.absolutePath}")
    }

    private suspend fun resolveNodeCoords(
        repository: MapGraphRepository,
        edges: List<MapEdge>,
    ): Map<Long, Pair<Double, Double>> {
        val ids = edges.flatMapTo(LinkedHashSet()) { listOf(it.fromNode, it.toNode) }
        val coords = HashMap<Long, Pair<Double, Double>>(ids.size)
        for (chunk in ids.chunked(500)) {
            repository.getNodesByIds(*chunk.toLongArray()).forEach { coords[it.id] = it.lat to it.lon }
        }
        return coords
    }

    /**
     * FeatureCollection with the final route split into corridor vs non-corridor (connector +
     * exit/return leg) edges, the coarse skeleton as ordered centroid points, and home. Drop the
     * file into geojson.io to inspect a run.
     */
    private fun buildGeoJson(
        homeLat: Double,
        homeLon: Double,
        candidate: RankedCandidate,
        corridorsById: Map<Long, Corridor>,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): String {
        val corridorPairs = candidate.corridorEdges.mapTo(HashSet()) { it.fromNode to it.toNode }
        val (corridorEdges, legEdges) = candidate.refinedRoute.edges.partition {
            (it.fromNode to it.toNode) in corridorPairs
        }

        val features = mutableListOf<String>()
        features += pointFeature(homeLat, homeLon, """"stage":"home"""")

        candidate.coarseLoop.corridors.forEachIndexed { order, id ->
            val c = corridorsById[id] ?: return@forEachIndexed
            features += pointFeature(
                c.centroidLat, c.centroidLon,
                """"stage":"skeleton","order":$order,"corridorId":$id,"synthetic":${id < 0}""",
            )
        }
        val skeletonLine = candidate.coarseLoop.corridors.mapNotNull { corridorsById[it] }
            .map { it.centroidLat to it.centroidLon }
        if (skeletonLine.size >= 2) {
            features += lineFeature(skeletonLine, """"stage":"skeleton-order"""")
        }

        multiLineFeature(corridorEdges, nodeCoords, """"stage":"corridor"""")?.let { features += it }
        multiLineFeature(legEdges, nodeCoords, """"stage":"leg-or-connector"""")?.let { features += it }

        return """{"type":"FeatureCollection","features":[${features.joinToString(",\n")}]}"""
    }

    private fun pointFeature(lat: Double, lon: Double, props: String) =
        """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

    private fun lineFeature(points: List<Pair<Double, Double>>, props: String): String {
        val coords = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        return """{"type":"Feature","properties":{$props},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }

    private fun multiLineFeature(
        edges: List<MapEdge>,
        nodeCoords: Map<Long, Pair<Double, Double>>,
        props: String,
    ): String? {
        val lines = edges.mapNotNull { edge ->
            val points = RouteShapeMetrics.routeGeometry(listOf(edge), nodeCoords)
            if (points.size < 2) null
            else "[" + points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" } + "]"
        }
        if (lines.isEmpty()) return null
        return """{"type":"Feature","properties":{$props},"geometry":{"type":"MultiLineString","coordinates":[${lines.joinToString(",")}]}}"""
    }

    private fun Double.format1() = String.format(java.util.Locale.US, "%.1f", this)
    private fun Double.format2() = String.format(java.util.Locale.US, "%.2f", this)

    private companion object {
        const val DB_NAME = "route_shape_diagnosis.db"
        const val SEED = 42L
        // Rounded ~30-50m off the exact home address on purpose; override with ROUTE_SHAPE_HOME.
        const val DEFAULT_HOME = "50.781,6.073"
    }
}
