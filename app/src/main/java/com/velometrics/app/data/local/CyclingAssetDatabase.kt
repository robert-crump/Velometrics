package com.velometrics.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.velometrics.app.data.local.dao.MapEdgeDao
import com.velometrics.app.data.local.dao.MapMetadataDao
import com.velometrics.app.data.local.dao.MapNodeDao
import com.velometrics.app.data.local.dao.PoiDao
import com.velometrics.app.data.local.entity.MapEdgeEntity
import com.velometrics.app.data.local.entity.MapMetadataEntity
import com.velometrics.app.data.local.entity.MapNodeEntity
import com.velometrics.app.data.local.entity.PoiEntity

@Database(
    entities = [
        MapNodeEntity::class,
        MapEdgeEntity::class,
        PoiEntity::class,
        MapMetadataEntity::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class CyclingAssetDatabase : RoomDatabase() {
    abstract fun mapNodeDao(): MapNodeDao
    abstract fun mapEdgeDao(): MapEdgeDao
    abstract fun poiDao(): PoiDao
    abstract fun mapMetadataDao(): MapMetadataDao

    companion object {
        /**
         * The `metadata.schema_version` this app build was written against. Distinct from Room's
         * own `PRAGMA user_version` (the `@Database.version` above): this is a data-level contract
         * with the graph exporter, so a mismatch (e.g. an exported asset built for a newer app
         * version) fails loudly here instead of silently falling through to
         * `fallbackToDestructiveMigration` and wiping the asset.
         */
        const val EXPECTED_SCHEMA_VERSION = 1

        fun schemaVersionCallback(): Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.query("SELECT schema_version FROM metadata LIMIT 1").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val actual = cursor.getInt(0)
                        check(actual == EXPECTED_SCHEMA_VERSION) {
                            "cycling_graph.db metadata.schema_version=$actual does not match the " +
                                "app's expected schema_version=$EXPECTED_SCHEMA_VERSION. The graph " +
                                "asset and this app build are out of sync — re-export the asset or " +
                                "update EXPECTED_SCHEMA_VERSION."
                        }
                    }
                }
            }
        }
    }
}
