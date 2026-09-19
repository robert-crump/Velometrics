package com.velometrics.app.data.preferences

import com.velometrics.app.data.local.dao.FtpHistoryDao
import com.velometrics.app.data.local.entity.FtpHistoryEntity
import com.velometrics.app.domain.model.FtpEntry
import com.velometrics.app.domain.model.FtpHistory
import com.velometrics.app.util.CyclingConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Source of the pre-#218 single FTP setting, consumed once to seed the history. */
interface LegacyFtpStore {
    suspend fun takeLegacyFtp(): Int?
}

/**
 * FTP over time (#218, ADR 0001), stored in Room. The table starts empty after the 19→20
 * migration; the first read seeds the "Before first test" row from the legacy DataStore FTP, so
 * every existing ride resolves to the FTP the user had configured until they add dated entries.
 */
@Singleton
class FtpHistoryRepository @Inject constructor(
    private val dao: FtpHistoryDao,
    private val legacyFtpStore: LegacyFtpStore
) {
    private val seedMutex = Mutex()

    val entries: Flow<List<FtpEntry>> = flow { emit(ensureSeeded()) }
        .flatMapLatest { dao.observeAll() }
        .map { rows -> rows.map { it.toEntry() } }

    val history: Flow<FtpHistory> = entries.map { FtpHistory(it) }

    /** The FTP in force today. */
    val currentFtp: Flow<Int> = history.map { it.ftpOn(LocalDate.now()) }

    /** Saves [ftp] effective from [date] (null = the seed row), overwriting any entry on that date. */
    suspend fun save(date: LocalDate?, ftp: Int) {
        require(date == null || !date.isAfter(LocalDate.now())) { "FTP entries can't be dated in the future" }
        ensureSeeded()
        dao.upsert(FtpHistoryEntity(date?.toEpochDay() ?: FtpHistoryEntity.SEED_EPOCH_DAY, ftp))
    }

    /** Deletes the entry on [date]; the seed row can't be deleted. */
    suspend fun delete(date: LocalDate) = dao.delete(date.toEpochDay())

    private suspend fun ensureSeeded() = seedMutex.withLock {
        if (dao.count() == 0) {
            val ftp = legacyFtpStore.takeLegacyFtp() ?: CyclingConstants.DEFAULT_FTP
            dao.upsert(FtpHistoryEntity(FtpHistoryEntity.SEED_EPOCH_DAY, ftp))
        }
    }

    private fun FtpHistoryEntity.toEntry() = FtpEntry(
        effectiveDate = if (effectiveEpochDay == FtpHistoryEntity.SEED_EPOCH_DAY) null else LocalDate.ofEpochDay(effectiveEpochDay),
        ftp = ftp
    )
}
