package com.velometrics.app.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Opens the checked-in fixture `velometrics_fixture.db` (Ride-Graph#99 export split) through the
 * full [CyclingAssetDatabase] Room stack on the JVM via Robolectric, mirroring the production
 * builder (createFromAsset + fallbackToDestructiveMigration + the schema_version onOpen callback).
 * This is the fixture contract test from issue #124: it fails loudly (destructive-fallback wipe, or
 * a thrown schema mismatch) instead of silently passing on an empty database.
 *
 * [Config.application] swaps out the real (Hilt) [com.velometrics.app.VelometricsApplication] for a
 * plain [Application] so this test can build a [Room] database directly, without pulling in the
 * whole Hilt dependency graph.
 *
 * NOTE (#156): bundled asset swapped from `cycling_graph.db`/`cycling_graph_fixture.db` (the full
 * Ride-Graph export) to the trimmed `velometrics.db`/`velometrics_fixture.db` pair scoped to this
 * app's actual tables (`map_nodes`, `map_edges`, `pois`, `metadata` — no `map_turns`/`corridors`,
 * which #155 had already dropped from [CyclingAssetDatabase]'s entity list ahead of this swap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CyclingAssetDatabaseFixtureTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dbNames = mutableListOf<String>()

    private fun freshDbName(name: String): String {
        context.deleteDatabase(name)
        dbNames += name
        return name
    }

    @After
    fun tearDown() {
        dbNames.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun `fixture asset opens through Room without a destructive-fallback wipe`() = runBlocking {
        val dbName = freshDbName("fixture_velometrics_unit_test.db")
        val db = Room.databaseBuilder(context, CyclingAssetDatabase::class.java, dbName)
            .createFromAsset("velometrics_fixture.db")
            .addCallback(CyclingAssetDatabase.schemaVersionCallback())
            .fallbackToDestructiveMigration()
            .build()
        try {
            val metadata = db.mapMetadataDao().getMetadata()
            assertNotNull("metadata row must survive the open (a destructive wipe would drop it)", metadata)
            metadata!!
            assertTrue(
                "schema_version must match the app's expected version",
                metadata.schemaVersion == CyclingAssetDatabase.EXPECTED_SCHEMA_VERSION
            )

            val edges = db.mapEdgeDao()
                .getNear(metadata.bboxSouth, metadata.bboxNorth, metadata.bboxWest, metadata.bboxEast)
            assertTrue("map_edges must be readable via the bbox join", edges.isNotEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `onOpen throws a clear error when schema_version does not match`() = runBlocking {
        val dbName = freshDbName("schema_mismatch_unit_test.db")

        // Build once with no callback to create empty tables, then seed a metadata row whose
        // schema_version deliberately disagrees with CyclingAssetDatabase.EXPECTED_SCHEMA_VERSION.
        val setupDb = Room.databaseBuilder(context, CyclingAssetDatabase::class.java, dbName)
            .fallbackToDestructiveMigration()
            .build()
        setupDb.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO metadata
                (id, created_at, bbox_south, bbox_west, bbox_north, bbox_east,
                 node_count, edge_count, traversed_edge_count, track_count, coverage_geojson, schema_version)
            VALUES (1, '2026-01-01T00:00:00Z', 0, 0, 0, 0, 0, 0, 0, 0, NULL, 999)
            """.trimIndent()
        )
        setupDb.close()

        val mismatchedDb = Room.databaseBuilder(context, CyclingAssetDatabase::class.java, dbName)
            .addCallback(CyclingAssetDatabase.schemaVersionCallback())
            .fallbackToDestructiveMigration()
            .build()
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { mismatchedDb.mapMetadataDao().getMetadata() }
            }
            assertTrue(
                "error message should be actionable and name both versions",
                error.message?.contains("999") == true &&
                    error.message?.contains("${CyclingAssetDatabase.EXPECTED_SCHEMA_VERSION}") == true
            )
        } finally {
            mismatchedDb.close()
        }
    }
}
