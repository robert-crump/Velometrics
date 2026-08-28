package com.velometrics.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the checked-in fixture `velometrics_fixture.db` (Ride-Graph#99 export split) through the
 * full [CyclingAssetDatabase] Room stack, mirroring the production builder (createFromAsset +
 * fallbackToDestructiveMigration). If the exported asset's PRAGMA user_version disagreed with the
 * Room `@Database` version in a way that triggered a destructive fallback, the tables would come
 * back empty and these assertions would fail — so this is the regression guard for issue #123's
 * "opens without a destructive-fallback wipe" acceptance criterion.
 *
 * NOTE (#156): bundled asset swapped from `cycling_graph.db`/`cycling_graph_fixture.db` (the full
 * Ride-Graph export) to the trimmed `velometrics.db`/`velometrics_fixture.db` pair scoped to this
 * app's actual tables. The old `mapTurnsAreReadableNearBoundingBox` test was dropped along with it
 * — `map_turns` isn't part of this asset, and #155 had already removed `MapTurnDao` from
 * [CyclingAssetDatabase].
 */
@RunWith(AndroidJUnit4::class)
class CyclingAssetDatabaseFixtureTest {

    private lateinit var db: CyclingAssetDatabase
    private val dbName = "fixture_velometrics_test.db"

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Force a fresh copy from the asset on every run so we exercise the first-open path.
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, CyclingAssetDatabase::class.java, dbName)
            .createFromAsset("velometrics_fixture.db")
            .addCallback(CyclingAssetDatabase.schemaVersionCallback())
            .fallbackToDestructiveMigration()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun fixtureOpensThroughRoomWithoutDestructiveWipe() = runBlocking {
        val metadata = db.mapMetadataDao().getMetadata()
        assertNotNull("metadata row must survive the open (a destructive wipe would drop it)", metadata)
        metadata!!
        assertTrue("schema_version column must be populated", metadata.schemaVersion > 0)
        assertTrue("node_count must be > 0", metadata.nodeCount > 0)

        // Real rows readable => Room adopted the prepackaged asset instead of recreating empty tables.
        val nodes = db.mapNodeDao()
            .getNear(metadata.bboxSouth, metadata.bboxNorth, metadata.bboxWest, metadata.bboxEast)
        assertTrue("map_nodes must be readable and non-empty", nodes.isNotEmpty())

        // SELECT e.* binds every column, including the cool_*/wh_per_m* columns, into the entity.
        val edges = db.mapEdgeDao()
            .getNear(metadata.bboxSouth, metadata.bboxNorth, metadata.bboxWest, metadata.bboxEast)
        assertTrue("map_edges must be readable and non-empty", edges.isNotEmpty())
    }
}
