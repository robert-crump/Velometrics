package com.velometrics.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One FTP value effective from [effectiveEpochDay] (local date, ADR 0001). The primary key makes
 * the date unique, so saving on an existing date overwrites. [SEED_EPOCH_DAY] marks the "Before
 * first test" row that covers rides earlier than every real entry.
 */
@Entity(tableName = "ftp_history")
data class FtpHistoryEntity(
    @PrimaryKey val effectiveEpochDay: Long,
    val ftp: Int
) {
    companion object {
        const val SEED_EPOCH_DAY = Long.MIN_VALUE
    }
}
