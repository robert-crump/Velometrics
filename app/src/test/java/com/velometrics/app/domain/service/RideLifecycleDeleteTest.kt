package com.velometrics.app.domain.service

import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import com.velometrics.app.fakes.RideLifecycleFixture
import com.velometrics.app.fakes.testSession
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLifecycleDeleteTest {

    private val fixture = RideLifecycleFixture()
    private val sessions = fixture.sessions
    private val cursor = fixture.cursor
    private val lifecycle = fixture.lifecycle()

    @Test
    fun `delete removes the sessions and invalidates the Dropbox cursor`() = runBlocking {
        val a = sessions.insertSession(testSession(0, Instant.parse("2026-01-01T00:00:00Z")))
        val b = sessions.insertSession(testSession(0, Instant.parse("2026-01-02T00:00:00Z")))

        val result = lifecycle.delete(listOf(a))

        assertEquals(DeleteResult.Deleted, result)
        assertEquals(listOf(b), sessions.sessions.map { it.id })
        assertTrue(cursor.invalidated)
    }

    @Test
    fun `a failed delete reports Failed and leaves the Dropbox cursor alone`() = runBlocking {
        val failing = object : CyclingSessionRepository by sessions {
            override suspend fun deleteSessions(ids: List<Long>) = throw IllegalStateException("db locked")
        }

        val result = fixture.lifecycle(sessionRepository = failing).delete(listOf(1L))

        assertEquals(DeleteResult.Failed, result)
        assertFalse(cursor.invalidated)
    }

    @Test
    fun `a cursor invalidation failure after a successful delete still reports Deleted`() = runBlocking {
        val id = sessions.insertSession(testSession(0, Instant.parse("2026-01-01T00:00:00Z")))
        val brokenCursor = object : DropboxSyncCursorRepository {
            override fun invalidateSyncCursor() = throw IllegalStateException("prefs unavailable")
        }

        val result = fixture.lifecycle(cursorRepository = brokenCursor).delete(listOf(id))

        assertEquals(DeleteResult.Deleted, result)
        assertTrue(sessions.sessions.isEmpty())
    }
}
