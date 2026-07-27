package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.velometrics.app.data.local.entity.SessionBestEffortEntity
import com.velometrics.app.domain.model.BestEffortRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionBestEffortDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionBestEffortEntity): Long

    @Query(
        """
        SELECT sbe.sessionId AS sessionId, cs.sessionStart AS sessionStart,
               sbe.split25kSec AS split25kSec, sbe.split50kSec AS split50kSec, sbe.split100kSec AS split100kSec,
               sbe.power1s AS power1s, sbe.power3s AS power3s, sbe.power5s AS power5s,
               sbe.power20s AS power20s, sbe.power30s AS power30s,
               sbe.power1m AS power1m, sbe.power5m AS power5m, sbe.power20m AS power20m, sbe.power30m AS power30m
        FROM session_best_efforts sbe
        JOIN cycling_sessions cs ON cs.id = sbe.sessionId
        """
    )
    fun getAllWithSessionDate(): Flow<List<BestEffortRecord>>
}
