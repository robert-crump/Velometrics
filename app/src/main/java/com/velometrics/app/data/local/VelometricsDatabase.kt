package com.velometrics.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.velometrics.app.data.local.converter.Converters
import com.velometrics.app.data.local.dao.*
import com.velometrics.app.data.local.entity.*

@Database(
    entities = [
        CyclingSessionEntity::class,
        IntervalSessionEntity::class,
        RepeatedRouteEntity::class,
        RepeatedIntervalEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class VelometricsDatabase : RoomDatabase() {
    abstract fun cyclingSessionDao(): CyclingSessionDao
    abstract fun intervalSessionDao(): IntervalSessionDao
    abstract fun repeatedRouteDao(): RepeatedRouteDao
    abstract fun repeatedIntervalDao(): RepeatedIntervalDao
}
