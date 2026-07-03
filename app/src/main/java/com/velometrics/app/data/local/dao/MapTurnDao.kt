package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.MapTurnEntity

@Dao
interface MapTurnDao {
    @Query(
        """
        SELECT t.* FROM map_turns t
        INNER JOIN map_nodes n ON t.junction_node = n.id
        WHERE n.lat BETWEEN :minLat AND :maxLat AND n.lon BETWEEN :minLon AND :maxLon
        """
    )
    suspend fun getNear(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<MapTurnEntity>
}
