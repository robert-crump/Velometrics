package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.velometrics.app.data.local.entity.FtpHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FtpHistoryDao {
    @Query("SELECT * FROM ftp_history ORDER BY effectiveEpochDay")
    fun observeAll(): Flow<List<FtpHistoryEntity>>

    @Query("SELECT COUNT(*) FROM ftp_history")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FtpHistoryEntity)

    @Query("DELETE FROM ftp_history WHERE effectiveEpochDay = :effectiveEpochDay")
    suspend fun delete(effectiveEpochDay: Long)
}
