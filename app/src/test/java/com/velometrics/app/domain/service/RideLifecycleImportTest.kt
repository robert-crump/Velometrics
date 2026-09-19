package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.fakes.RideLifecycleFixture
import com.velometrics.app.fakes.testSession
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLifecycleImportTest {

    private val fixture = RideLifecycleFixture()
    private val lifecycle = fixture.lifecycle()

    private fun source(name: String) = ImportSource(name) { name.toByteArray() }

    @Test
    fun `import reports per-file progress then the batch results in file order`() = runBlocking {
        val progress = lifecycle.import(listOf(source("a.fit"), source("b.fit")), onSmallFile = { false }).toList()

        val finished = progress.last() as ImportProgress.Finished
        assertEquals(
            listOf(ImportProgress.Importing(1, 2, "a.fit"), ImportProgress.Importing(2, 2, "b.fit")),
            progress.dropLast(1)
        )
        assertEquals(listOf("summary a.fit", "summary b.fit"),
            finished.results.map { (it as ImportResult.Success).summary })
    }

    @Test
    fun `a confirmed small file is imported anyway`() = runBlocking {
        fixture.importer.smallFiles += "short.fit"
        val asked = mutableListOf<ImportResult.SmallFile>()

        val finished = lifecycle.import(listOf(source("short.fit"))) { asked += it; true }
            .toList().last() as ImportProgress.Finished

        assertEquals(listOf("short.fit"), asked.map { it.fileName })
        assertEquals(1, finished.results.size)
        assertTrue(finished.results.single() is ImportResult.Success)
        assertEquals(listOf("short.fit" to false, "short.fit" to true), fixture.importer.calls)
    }

    @Test
    fun `a skipped small file is left out of the results and the batch continues`() = runBlocking {
        fixture.importer.smallFiles += "short.fit"

        val finished = lifecycle.import(listOf(source("short.fit"), source("ok.fit"))) { false }
            .toList().last() as ImportProgress.Finished

        assertEquals(listOf("summary ok.fit"), finished.results.map { (it as ImportResult.Success).summary })
    }

    @Test
    fun `an unreadable file is an error and the batch continues`() = runBlocking {
        val finished = lifecycle.import(
            listOf(ImportSource("gone.fit") { null }, source("ok.fit")),
            onSmallFile = { false }
        ).toList().last() as ImportProgress.Finished

        assertEquals("Could not read file: gone.fit", (finished.results[0] as ImportResult.Error).message)
        assertTrue(finished.results[1] is ImportResult.Success)
    }

    @Test
    fun `the batch reveals its newest ride when it is newer than everything already stored`() = runBlocking {
        fixture.sessions.insertSession(testSession(0, Instant.parse("2026-01-01T08:00:00Z")))
        fixture.importer.startsByFileName["new.fit"] = Instant.parse("2026-02-01T08:00:00Z")

        val finished = lifecycle.import(listOf(source("new.fit")), onSmallFile = { false })
            .toList().last() as ImportProgress.Finished

        assertEquals(2L, finished.reveal?.sessionId)
    }

    @Test
    fun `a batch of older rides does not reveal`() = runBlocking {
        fixture.sessions.insertSession(testSession(0, Instant.parse("2026-03-01T08:00:00Z")))
        fixture.importer.startsByFileName["old.fit"] = Instant.parse("2026-01-01T08:00:00Z")

        val finished = lifecycle.import(listOf(source("old.fit")), onSmallFile = { false })
            .toList().last() as ImportProgress.Finished

        assertNull(finished.reveal)
    }

    @Test
    fun `a second batch waits until the first has finished`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = launch(Dispatchers.Default) {
            lifecycle.trackImports(ReclusterMode.Background) {
                events += "first started"; gate.await(); events += "first done"; emptyList()
            }
        }
        while ("first started" !in events) yield()
        val second = launch(Dispatchers.Default) {
            lifecycle.trackImports(ReclusterMode.Background) { events += "second started"; emptyList() }
        }
        delay(100)
        assertEquals(listOf("first started"), events.toList())

        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("first started", "first done", "second started"), events.toList())
    }
}
