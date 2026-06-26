package com.velometrics.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.velometrics.app.data.local.dao.CorridorDao
import com.velometrics.app.data.local.dao.MapEdgeDao
import com.velometrics.app.data.local.dao.MapMetadataDao
import com.velometrics.app.data.local.dao.MapNodeDao
import com.velometrics.app.data.local.dao.PoiDao
import com.velometrics.app.data.local.entity.CorridorConnectorEntity
import com.velometrics.app.data.local.entity.CorridorEntity
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
        CorridorEntity::class,
        CorridorConnectorEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class CyclingAssetDatabase : RoomDatabase() {
    abstract fun mapNodeDao(): MapNodeDao
    abstract fun mapEdgeDao(): MapEdgeDao
    abstract fun poiDao(): PoiDao
    abstract fun mapMetadataDao(): MapMetadataDao
    abstract fun corridorDao(): CorridorDao
}
