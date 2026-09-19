package com.velometrics.app.ui.screens.home

import androidx.work.WorkManager
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncOutcome
import com.velometrics.app.data.dropbox.DropboxSyncOutcomeStore
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.service.ImportSource
import com.velometrics.app.fakes.RideLifecycleFixture
import com.velometrics.app.fakes.testSession
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Home import state machine (#203), run against a real `RideLifecycleImpl` over fakes. Import
 * runs on the real `Dispatchers.IO`, so assertions poll with a short real-time timeout.
 */
class HomeViewModelImportTest {

    private val fixture = RideLifecycleFixture()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): HomeViewModel {
        val auth = mockk<DropboxAuthRepository>()
        every { auth.isConnected } returns MutableStateFlow(false)
        val outcomeStore = mockk<DropboxSyncOutcomeStore>()
        every { outcomeStore.outcome } returns MutableStateFlow<DropboxSyncOutcome?>(null)
        return HomeViewModel(
            sessionRepository = fixture.sessions,
            rideLifecycle = fixture.lifecycle(),
            importSourceReader = mockk(relaxed = true),
            workManager = mockk<WorkManager>(relaxed = true),
            dropboxSyncOutcomeStore = outcomeStore,
            dropboxAuthRepository = auth
        )
    }

    private fun source(name: String) = ImportSource(name) { name.toByteArray() }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `a batch shows its progress and finishes with Done`() {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel()

        vm.importFromSources(listOf(source("a.fit"), ImportSource("b.fit") { gate.await(); "b".toByteArray() }))

        assertTrue(waitFor { vm.importState.value == ImportUiState.BatchLoading(2, 2) })
        gate.complete(Unit)
        assertTrue(waitFor { vm.importState.value is ImportUiState.Done })
        val done = vm.importState.value as ImportUiState.Done
        assertEquals("summary b.fit", (done.result as ImportResult.Success).summary)
    }

    @Test
    fun `a small file asks first, and confirming imports it`() {
        fixture.importer.smallFiles += "short.fit"
        val vm = viewModel()

        vm.importFromSources(listOf(source("short.fit")))

        assertTrue(waitFor { vm.importState.value is ImportUiState.SmallFileWarning })
        assertEquals(
            ImportUiState.SmallFileWarning("short.fit", 3, current = 1, total = 1),
            vm.importState.value
        )
        vm.confirmSmallFileImport()
        assertTrue(waitFor { vm.importState.value is ImportUiState.Done })
        assertTrue((vm.importState.value as ImportUiState.Done).result is ImportResult.Success)
    }

    @Test
    fun `skipping a small file imports nothing`() {
        fixture.importer.smallFiles += "short.fit"
        val vm = viewModel()

        vm.importFromSources(listOf(source("short.fit")))
        assertTrue(waitFor { vm.importState.value is ImportUiState.SmallFileWarning })
        vm.skipSmallFile()

        assertTrue(waitFor { vm.importState.value is ImportUiState.Done })
        assertEquals(ImportResult.Error("No files"), (vm.importState.value as ImportUiState.Done).result)
        assertTrue(fixture.sessions.sessions.isEmpty())
    }

    @Test
    fun `a batch whose newest ride is new shows the reveal instead of Done`() {
        runBlocking { fixture.sessions.insertSession(testSession(0, Instant.parse("2026-01-01T08:00:00Z"))) }
        fixture.importer.startsByFileName["new.fit"] = Instant.parse("2026-02-01T08:00:00Z")
        val vm = viewModel()

        vm.importFromSources(listOf(source("new.fit")))

        assertTrue(waitFor { vm.importState.value is ImportUiState.RideReveal })
        assertEquals(2L, (vm.importState.value as ImportUiState.RideReveal).content.sessionId)
    }
}
