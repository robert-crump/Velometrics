package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.MapMetadataEntity

@Dao
interface MapMetadataDao {
    @Query("SELECT * FROM metadata LIMIT 1")
    suspend fun getMetadata(): MapMetadataEntity?
}
