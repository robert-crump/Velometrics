package com.cyclegraph.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cyclegraph.app.data.local.dao.MapEdgeDao
import com.cyclegraph.app.data.local.dao.MapNodeDao
import com.cyclegraph.app.data.local.dao.PoiDao
import com.cyclegraph.app.data.local.entity.MapEdgeEntity
import com.cyclegraph.app.data.local.entity.MapNodeEntity
import com.cyclegraph.app.data.local.entity.PoiEntity

@Database(
    entities = [MapNodeEntity::class, MapEdgeEntity::class, PoiEntity::class],
    version = 2,
    exportSchema = false
)
abstract class CyclingAssetDatabase : RoomDatabase() {
    abstract fun mapNodeDao(): MapNodeDao
    abstract fun mapEdgeDao(): MapEdgeDao
    abstract fun poiDao(): PoiDao
}
