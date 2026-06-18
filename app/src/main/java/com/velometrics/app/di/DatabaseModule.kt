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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VelometricsDatabase {
        return Room.databaseBuilder(
            context,
            VelometricsDatabase::class.java,
            "velometrics_database"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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
    @Singleton
    fun provideCyclingAssetDatabase(@ApplicationContext context: Context): CyclingAssetDatabase =
        Room.databaseBuilder(context, CyclingAssetDatabase::class.java, "cycling_graph.db")
            .createFromAsset("cycling_graph.db")
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

    @Provides
    fun provideCorridorDao(db: CyclingAssetDatabase): CorridorDao = db.corridorDao()
}
