package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.fakes.RideLifecycleFixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RideLifecycleReclusterTest {

    private val fixture = RideLifecycleFixture()
    private val lifecycle = fixture.lifecycle()

    private fun source(name: String) = ImportSource(name) { name.toByteArray() }

    @Test
    fun `a file import reclusters every clusterer once per batch`() = runBlocking {
        lifecycle.import(listOf(source("a.fit"), source("b.fit")), onSmallFile = { false }).toList()

        assertEquals(1, fixture.routeClusterer.runs)
        assertEquals(1, fixture.intervalClusterer.runs)
    }

    @Test
    fun `reclustering still happens when the batch block fails`() = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                lifecycle.trackImports(ReclusterMode.Await) { throw IllegalStateException("sync blew up") }
            }
        }

        assertEquals(1, fixture.routeClusterer.runs)
        assertEquals(1, fixture.intervalClusterer.runs)
    }

    @Test
    fun `awaited reclustering isolates a failing clusterer from the others`() = runBlocking {
        fixture.routeClusterer.failWith = IllegalStateException("routes broke")

        val batch = lifecycle.trackImports(ReclusterMode.Await) { emptyList<ImportResult>() }

        assertEquals(emptyList<ImportResult>(), batch.results)
        assertEquals(1, fixture.intervalClusterer.runs)
    }

    @Test
    fun `recluster runs every clusterer on demand`() = runBlocking {
        lifecycle.recluster()

        assertEquals(1, fixture.routeClusterer.runs)
        assertEquals(1, fixture.intervalClusterer.runs)
    }

    @Test
    fun `delete never reclusters`() = runBlocking {
        lifecycle.delete(listOf(1L))

        assertEquals(0, fixture.routeClusterer.runs)
        assertEquals(0, fixture.intervalClusterer.runs)
    }
}
