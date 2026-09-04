package com.velometrics.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.velometrics.app.data.local.VelometricsDatabase
import com.velometrics.app.data.local.CyclingAssetDatabase
import com.velometrics.app.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.json.JSONObject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE map_edges ADD COLUMN sessionRiddenCount INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL(
                "UPDATE map_edges SET sessionRiddenCount = MIN(riddenCount, 5)"
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Drop old graph tables that are no longer used
            database.execSQL("DROP TABLE IF EXISTS map_edges")
            database.execSQL("DROP TABLE IF EXISTS map_nodes")
            database.execSQL("DROP TABLE IF EXISTS pending_points")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE cycling_sessions ADD COLUMN fatEfficiencyScore INTEGER"
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS repeated_routes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "sessionIds TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL" +
                ")"
            )
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS repeated_intervals (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "intervalIds TEXT NOT NULL, " +
                    "edges TEXT NOT NULL, " +
                    "startLat REAL NOT NULL, " +
                    "startLon REAL NOT NULL, " +
                    "endLat REAL NOT NULL, " +
                    "endLon REAL NOT NULL, " +
                    "distanceM REAL NOT NULL, " +
                    "createdAt INTEGER NOT NULL" +
                ")"
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Drop the prototypeRouteId column/FK from interval_sessions (#26: matching now
            // assigns intervals to RepeatedInterval archetypes, tracked via RepeatedIntervalEntity.intervalIds)
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS interval_sessions_new (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`cyclingSessionId` INTEGER NOT NULL, " +
                    "`startTimestamp` INTEGER NOT NULL, " +
                    "`durationSec` INTEGER NOT NULL, " +
                    "`durationNormalizedSec` INTEGER NOT NULL, " +
                    "`distanceM` REAL NOT NULL, " +
                    "`avgPower` INTEGER NOT NULL, " +
                    "`avgSpeedKmh` REAL NOT NULL, " +
                    "`avgSpeedNormalizedKmh` REAL NOT NULL, " +
                    "`direction` TEXT NOT NULL, " +
                    "`startLat` REAL NOT NULL, " +
                    "`startLon` REAL NOT NULL, " +
                    "`endLat` REAL NOT NULL, " +
                    "`endLon` REAL NOT NULL, " +
                    "`gpsTrack` TEXT NOT NULL, " +
                    "FOREIGN KEY(`cyclingSessionId`) REFERENCES `cycling_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            database.execSQL(
                "INSERT INTO interval_sessions_new (id, cyclingSessionId, startTimestamp, durationSec, " +
                    "durationNormalizedSec, distanceM, avgPower, avgSpeedKmh, avgSpeedNormalizedKmh, direction, " +
                    "startLat, startLon, endLat, endLon, gpsTrack) " +
                    "SELECT id, cyclingSessionId, startTimestamp, durationSec, durationNormalizedSec, distanceM, " +
                    "avgPower, avgSpeedKmh, avgSpeedNormalizedKmh, direction, startLat, startLon, endLat, endLon, gpsTrack " +
                    "FROM interval_sessions"
            )
            database.execSQL("DROP TABLE interval_sessions")
            database.execSQL("ALTER TABLE interval_sessions_new RENAME TO interval_sessions")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_interval_sessions_cyclingSessionId ON interval_sessions(cyclingSessionId)")
            database.execSQL("DROP TABLE IF EXISTS interval_prototype_routes")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN avgHeartRate INTEGER")
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN elevationGainM REAL")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN hrZoneDistribution TEXT")
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS session_best_efforts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "sessionId INTEGER NOT NULL, " +
                    "split25kSec REAL, " +
                    "split50kSec REAL, " +
                    "split100kSec REAL, " +
                    "power1s INTEGER, " +
                    "power3s INTEGER, " +
                    "power5s INTEGER, " +
                    "power20s INTEGER, " +
                    "power30s INTEGER, " +
                    "power1m INTEGER, " +
                    "power5m INTEGER, " +
                    "power20m INTEGER, " +
                    "power30m INTEGER, " +
                    "FOREIGN KEY(sessionId) REFERENCES cycling_sessions(id) ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_session_best_efforts_sessionId ON session_best_efforts(sessionId)"
            )
        }
    }

    // Speed histogram buckets narrowed from 8 to 5 (#148); every new boundary aligns with an old
    // one, so existing rows are merged rather than discarded. Old label -> new label.
    private val SPEED_HISTOGRAM_BUCKET_MERGE = mapOf(
        "0-5 km/h" to "0-10 km/h",
        "5-10 km/h" to "0-10 km/h",
        "10-20 km/h" to "10-20 km/h",
        "20-25 km/h" to "20-30 km/h",
        "25-30 km/h" to "20-30 km/h",
        "30-35 km/h" to "30-40 km/h",
        "35-40 km/h" to "30-40 km/h",
        ">40 km/h" to ">40 km/h"
    )
    private val SPEED_HISTOGRAM_NEW_BUCKETS =
        listOf("0-10 km/h", "10-20 km/h", "20-30 km/h", "30-40 km/h", ">40 km/h")

    internal val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            val cursor = database.query("SELECT id, speedHistogram FROM cycling_sessions")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow("id")
                val histIndex = it.getColumnIndexOrThrow("speedHistogram")
                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val oldHist = JSONObject(it.getString(histIndex))
                    val newCounts = SPEED_HISTOGRAM_NEW_BUCKETS.associateWith { 0 }.toMutableMap()
                    oldHist.keys().forEach { oldLabel ->
                        val newLabel = SPEED_HISTOGRAM_BUCKET_MERGE[oldLabel] ?: return@forEach
                        newCounts[newLabel] = (newCounts[newLabel] ?: 0) + oldHist.getInt(oldLabel)
                    }
                    val newJson = JSONObject(newCounts as Map<String, Any>).toString()
                    database.execSQL(
                        "UPDATE cycling_sessions SET speedHistogram = ? WHERE id = ?",
                        arrayOf(newJson, id)
                    )
                }
            }
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN cardiacDriftBuckets TEXT")
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN cardiacDriftPercent REAL")
        }
    }

    // Rule-based ride tagging (#169): new nullable column, backfilled onto pre-existing rows by
    // RideClassificationService.reclassifyAll() rather than in-migration (classification needs
    // domain logic, not just SQL).
    internal val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN tag TEXT")
        }
    }

    // Recovery tag-comparison narrative now leads with time spent below 60% FTP rather than time
    // in power Zone 1, and that threshold doesn't line up with any existing power-zone boundary
    // (Zone 1 is 0-55%, Zone 2 is 55-70%) -- computed fresh at import time from raw per-second
    // power, same no-backfill-for-existing-rows precedent as MIGRATION_13_14's tag column.
    internal val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN timeBelowSixtyPercentFtpSec INTEGER")
        }
    }

    // Import-time HR recovery metrics (#178): hasHR coverage flag on cycling_sessions, mirroring
    // hasPower; hrr60/hrr30/avgPower60sAfter/restBeforeNextIntervalSec on interval_sessions,
    // computed by IntervalDetector. Same no-backfill-for-existing-rows precedent as MIGRATION_13_14
    // and MIGRATION_14_15 -- new imports only.
    internal val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE cycling_sessions ADD COLUMN hasHR INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE interval_sessions ADD COLUMN hrr60 INTEGER")
            database.execSQL("ALTER TABLE interval_sessions ADD COLUMN hrr30 INTEGER")
            database.execSQL("ALTER TABLE interval_sessions ADD COLUMN avgPower60sAfter INTEGER")
            database.execSQL("ALTER TABLE interval_sessions ADD COLUMN restBeforeNextIntervalSec INTEGER")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VelometricsDatabase {
        return Room.databaseBuilder(
            context,
            VelometricsDatabase::class.java,
            "velometrics_database"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideCyclingSessionDao(database: VelometricsDatabase): CyclingSessionDao {
        return database.cyclingSessionDao()
    }

    @Provides
    fun provideIntervalSessionDao(database: VelometricsDatabase): IntervalSessionDao {
        return database.intervalSessionDao()
    }

    @Provides
    fun provideRepeatedRouteDao(database: VelometricsDatabase): RepeatedRouteDao {
        return database.repeatedRouteDao()
    }

    @Provides
    fun provideRepeatedIntervalDao(database: VelometricsDatabase): RepeatedIntervalDao {
        return database.repeatedIntervalDao()
    }

    @Provides
    fun provideSessionBestEffortDao(database: VelometricsDatabase): SessionBestEffortDao {
        return database.sessionBestEffortDao()
    }

    @Provides
    @Singleton
    fun provideCyclingAssetDatabase(@ApplicationContext context: Context): CyclingAssetDatabase =
        Room.databaseBuilder(context, CyclingAssetDatabase::class.java, "velometrics.db")
            .createFromAsset("velometrics.db")
            .addCallback(CyclingAssetDatabase.schemaVersionCallback())
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMapNodeDao(db: CyclingAssetDatabase): MapNodeDao = db.mapNodeDao()

    @Provides
    fun provideMapEdgeDao(db: CyclingAssetDatabase): MapEdgeDao = db.mapEdgeDao()

    @Provides
    fun providePoiDao(db: CyclingAssetDatabase): PoiDao = db.poiDao()

    @Provides
    fun provideMapMetadataDao(db: CyclingAssetDatabase): MapMetadataDao = db.mapMetadataDao()
}
