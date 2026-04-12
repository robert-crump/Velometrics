package com.cyclegraph.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cyclegraph.app.data.local.converter.Converters
import com.cyclegraph.app.data.local.dao.*
import com.cyclegraph.app.data.local.entity.*

@Database(
    entities = [
        CyclingSessionEntity::class,
        IntervalSessionEntity::class,
        IntervalPrototypeRouteEntity::class,
        RepeatedRouteEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CycleGraphDatabase : RoomDatabase() {
    abstract fun cyclingSessionDao(): CyclingSessionDao
    abstract fun intervalSessionDao(): IntervalSessionDao
    abstract fun intervalPrototypeRouteDao(): IntervalPrototypeRouteDao
    abstract fun repeatedRouteDao(): RepeatedRouteDao
}
