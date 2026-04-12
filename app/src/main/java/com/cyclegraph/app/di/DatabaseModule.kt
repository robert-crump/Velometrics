package com.cyclegraph.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclegraph.app.data.local.CycleGraphDatabase
import com.cyclegraph.app.data.local.CyclingAssetDatabase
import com.cyclegraph.app.data.local.dao.*
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CycleGraphDatabase {
        return Room.databaseBuilder(
            context,
            CycleGraphDatabase::class.java,
            "cyclegraph_database"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideCyclingSessionDao(database: CycleGraphDatabase): CyclingSessionDao {
        return database.cyclingSessionDao()
    }

    @Provides
    fun provideIntervalSessionDao(database: CycleGraphDatabase): IntervalSessionDao {
        return database.intervalSessionDao()
    }

    @Provides
    fun provideIntervalPrototypeRouteDao(database: CycleGraphDatabase): IntervalPrototypeRouteDao {
        return database.intervalPrototypeRouteDao()
    }

    @Provides
    fun provideRepeatedRouteDao(database: CycleGraphDatabase): RepeatedRouteDao {
        return database.repeatedRouteDao()
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
}
