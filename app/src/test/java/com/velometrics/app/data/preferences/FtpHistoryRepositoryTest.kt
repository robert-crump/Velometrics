package com.velometrics.app.data.preferences

import com.velometrics.app.data.local.dao.FtpHistoryDao
import com.velometrics.app.data.local.entity.FtpHistoryEntity
import com.velometrics.app.domain.model.FtpEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class FtpHistoryRepositoryTest {

    private class FakeDao : FtpHistoryDao {
        val rows = MutableStateFlow<Map<Long, Int>>(emptyMap())
        override fun observeAll(): Flow<List<FtpHistoryEntity>> =
            rows.map { m -> m.toSortedMap().map { FtpHistoryEntity(it.key, it.value) } }
        override suspend fun count() = rows.value.size
        override suspend fun upsert(entity: FtpHistoryEntity) {
            rows.value = rows.value + (entity.effectiveEpochDay to entity.ftp)
        }
        override suspend fun delete(effectiveEpochDay: Long) {
            rows.value = rows.value - effectiveEpochDay
        }
    }

    private class FakeLegacy(var ftp: Int?) : LegacyFtpStore {
        override suspend fun takeLegacyFtp(): Int? = ftp.also { ftp = null }
    }

    @Test
    fun `first read seeds Before-first-test from the legacy setting and consumes it`() = runTest {
        val legacy = FakeLegacy(ftp = 240)
        val repo = FtpHistoryRepository(FakeDao(), legacy)

        val entries = repo.entries.first()

        assertEquals(listOf(FtpEntry(null, 240)), entries)
        assertNull(legacy.ftp)
    }

    @Test
    fun `seed falls back to the default FTP when no legacy setting exists`() = runTest {
        val repo = FtpHistoryRepository(FakeDao(), FakeLegacy(ftp = null))

        assertEquals(
            listOf(FtpEntry(null, com.velometrics.app.util.CyclingConstants.DEFAULT_FTP)),
            repo.entries.first()
        )
    }

    @Test
    fun `saving on an existing date overwrites that entry`() = runTest {
        val repo = FtpHistoryRepository(FakeDao(), FakeLegacy(ftp = 200))
        val day = LocalDate.now().minusDays(10)

        repo.save(day, 230)
        repo.save(day, 240)

        assertEquals(listOf(FtpEntry(null, 200), FtpEntry(day, 240)), repo.entries.first())
    }

    @Test
    fun `entries come back oldest first with the seed leading`() = runTest {
        val repo = FtpHistoryRepository(FakeDao(), FakeLegacy(ftp = 200))
        val recent = LocalDate.now().minusDays(1)
        val older = LocalDate.now().minusDays(30)

        repo.save(recent, 250)
        repo.save(older, 230)

        assertEquals(listOf(null, older, recent), repo.entries.first().map { it.effectiveDate })
    }

    @Test
    fun `future dates are rejected`() = runTest {
        val repo = FtpHistoryRepository(FakeDao(), FakeLegacy(ftp = 200))

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.save(LocalDate.now().plusDays(1), 250) }
        }
    }

    @Test
    fun `delete removes a dated entry`() = runTest {
        val repo = FtpHistoryRepository(FakeDao(), FakeLegacy(ftp = 200))
        val day = LocalDate.now().minusDays(5)
        repo.save(day, 230)

        repo.delete(day)

        assertEquals(listOf(FtpEntry(null, 200)), repo.entries.first())
    }

    @Test
    fun `current FTP is the latest entry in force today`() = runTest {
        val repo = FtpHistoryRepository(FakeDao(), FakeLegacy(ftp = 200))
        repo.save(LocalDate.now().minusDays(30), 230)
        repo.save(LocalDate.now().minusDays(1), 250)

        assertEquals(250, repo.currentFtp.first())
    }
}
