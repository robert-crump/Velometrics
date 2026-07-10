package com.velometrics.app.domain.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.velometrics.app.data.local.CyclingAssetDatabase
import com.velometrics.app.data.repository.MapGraphRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Tier-2 regression test for issue #135: the structural guard against a repeat of #107/#111/
 * #113-#119, where the mechanism shipped green unit tests while generated routes stayed
 * tree/lollipop-shaped. Unlike [RouteShapeDiagnosisHarness] (a diagnostic dump, no assertions,
 * opt-in for manual runs), this test asserts the frozen [RouteShapeGateConfig] defaults actually
 * hold for a small, fixed set of routes generated against the real production `cycling_graph.db`
 * asset — the fixtures used by [RouteGeneratorTest] are too small/synthetic to catch a shape
 * regression that only shows up on real road topology.
 *
 * The three (distance, direction) pairs below are exactly the runs the maintainer visually
 * confirmed as "reads as a rideable oval" during #135's calibration pass (see [RouteShapeGateConfig]
 * doc): d50km_east, d50km_south, d80km_west. This is deliberately not "every distance x direction
 * combo" — [RouteGenerator.selectWinner] treats the shape gate as a soft preference with a
 * best-effort fallback (#133), not a hard guarantee, and the calibration matrix showed plenty of
 * legitimate (distance, direction) combos that don't clear the gate on this graph (e.g. d20km_any
 * at 0.02 compactness) without that being a regression. Asserting gate-pass on arbitrary combos
 * would make this test flaky/wrong; pinning to maintainer-confirmed-good combos makes it a real
 * regression guard: if a future change knocks any of these three off the gate, that's the "fixed
 * the mechanism, not the shape" failure mode #135 exists to catch.
 *
 * Opt-in via TIER2_SHAPE_GATE=1, same pattern as ROUTE_SHAPE_DIAGNOSIS. Measured total runtime for
 * these 3 runs was ~5.7 min (221s + 47s + 75s, from the #135 calibration matrix) — over the ~5
 * minute per-PR budget from #135's test-strategy note, so this runs on-demand/nightly, not as part
 * of the default `gradlew test` / `testDebugUnitTest` invocation.
 *
 * Run: `$env:TIER2_SHAPE_GATE="1"; ./gradlew :app:testDebugUnitTest --tests "*RouteShapeGateRegressionTest" --rerun-tasks`
 * (`--rerun-tasks` because Gradle doesn't track `System.getenv()` reads as a task input, so a plain
 * re-run can silently report a stale UP-TO-DATE pass instead of executing.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RouteShapeGateRegressionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private var db: CyclingAssetDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `routes generated against the production graph clear the frozen shape gate`() {
        assumeTrue(
            "set TIER2_SHAPE_GATE=1 to run the #135 tier-2 shape-gate regression test",
            System.getenv("TIER2_SHAPE_GATE") == "1",
        )

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

        val shapeConfig = RouteShapeGateConfig()
        val failures = mutableListOf<String>()

        for ((km, direction) in CALIBRATION_POINTS) {
            val label = "${km.toInt()}km/${direction?.name ?: "NONE"}"
            val result = runBlocking {
                RouteGenerator.generate(
                    HOME_LAT, HOME_LON, km * 1000.0, repository,
                    config = GeneratorConfig(direction = direction, seed = SEED),
                )
            }
            when (result) {
                is RoutePlanResult.Failure -> failures += "$label: generation failed - ${result.reason}"
                is RoutePlanResult.Success -> {
                    val edges = result.candidate.refinedRoute.edges
                    val nodeCoords = runBlocking { resolveNodeCoords(repository, edges) }
                    val report = RouteShapeMetrics.evaluate(edges, nodeCoords)
                    if (!RouteGenerator.passesShapeGate(report, shapeConfig)) {
                        failures += "$label: compactness=${report.compactness} (need >= ${shapeConfig.minCompactness}), " +
                            "repeatFraction=${report.repeatFraction} (need <= ${shapeConfig.maxRepeatFraction})"
                    }
                }
            }
        }

        assertTrue(
            "shape gate regression on the production graph:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    private suspend fun resolveNodeCoords(
        repository: com.velometrics.app.domain.repository.MapGraphRepository,
        edges: List<com.velometrics.app.domain.model.MapEdge>,
    ): Map<Long, Pair<Double, Double>> {
        val ids = edges.flatMapTo(LinkedHashSet()) { listOf(it.fromNode, it.toNode) }
        val coords = HashMap<Long, Pair<Double, Double>>(ids.size)
        for (chunk in ids.chunked(500)) {
            repository.getNodesByIds(*chunk.toLongArray()).forEach { coords[it.id] = it.lat to it.lon }
        }
        return coords
    }

    private companion object {
        const val DB_NAME = "route_shape_gate_regression.db"
        const val SEED = 42L
        // Same rounded-off default as RouteShapeDiagnosisHarness; not the exact home address.
        const val HOME_LAT = 50.781
        const val HOME_LON = 6.073

        // Maintainer-confirmed-good combos from the #135 calibration matrix (see class doc).
        val CALIBRATION_POINTS: List<Pair<Double, RideDirection?>> = listOf(
            50.0 to RideDirection.EAST,
            50.0 to RideDirection.SOUTH,
            80.0 to RideDirection.WEST,
        )
    }
}
