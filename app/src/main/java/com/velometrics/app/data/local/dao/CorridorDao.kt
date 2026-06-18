package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.CorridorConnectorEntity
import com.velometrics.app.data.local.entity.CorridorEntity

@Dao
interface CorridorDao {
    @Query("SELECT * FROM corridors")
    suspend fun getAll(): List<CorridorEntity>

    @Query("SELECT * FROM corridor_connectors")
    suspend fun getAllConnectors(): List<CorridorConnectorEntity>

    @Query("SELECT * FROM corridor_connectors WHERE from_corridor = :corridorId OR to_corridor = :corridorId")
    suspend fun getConnectorsForCorridor(corridorId: Long): List<CorridorConnectorEntity>
}
