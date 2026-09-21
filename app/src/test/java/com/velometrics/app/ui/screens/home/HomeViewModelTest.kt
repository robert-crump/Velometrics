package com.velometrics.app.ui.screens.home

import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncOutcome
import com.velometrics.app.data.dropbox.DropboxSyncOutcomeStore
import com.velometrics.app.data.dropbox.DropboxSyncScheduler
import com.velometrics.app.data.dropbox.DropboxSyncWorker
import com.velometrics.app.domain.model.RideRevealContent
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertNull
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeDropboxSyncCursorRepository
import com.velometrics.app.fakes.RideLifecycleFixture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers #192: pull-to-refresh (`syncDropbox`) must recluster unconditionally, so Repeated
 * Routes/Intervals badges stay correct after a ride delete even when Dropbox is disconnected or
 * finds nothing new.
 *
 * `syncDropbox`'s work runs on the real `Dispatchers.IO` (not a swappable test dispatcher), so
 * these tests wait for it via a real, short-timeout [CountDownLatch] rather than virtual time.
 */
class HomeViewModelTest {

    // Real, immediate-on-caller-thread dispatcher: just needs to exist for viewModelScope's
    // lazy initialization; syncDropbox's actual work runs on the real Dispatchers.IO regardless.
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private lateinit var workManager: WorkManager
    private val requests = mutableListOf<OneTimeWorkRequest>()
    private val outcomeFlow = MutableStateFlow<DropboxSyncOutcome?>(null)
    private val outcomeStore = mockk<DropboxSyncOutcomeStore>()

    @Before
    fun setUpWork() {
        requests.clear()
        workManager = mockk()
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        every {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), capture(requests))
        } returns mockk<Operation>()
        every { outcomeStore.outcome } returns outcomeFlow
        coEvery { outcomeStore.consume() } answers { outcomeFlow.value = null }
    }

    private fun buildViewModel(
        isConnected: MutableStateFlow<Boolean>,
        sessionRepository: CyclingSessionRepository = FakeCyclingSessionRepository(),
        dropboxSyncCursorRepository: DropboxSyncCursorRepository = FakeDropboxSyncCursorRepository()
    ): HomeViewModel {
        val dropboxAuthRepository = mockk<DropboxAuthRepository>()
        every { dropboxAuthRepository.isConnected } returns isConnected

        val fixture = RideLifecycleFixture()
        return HomeViewModel(
            sessionRepository = sessionRepository,
            rideLifecycle = fixture.lifecycle(sessionRepository, dropboxSyncCursorRepository),
            importSourceReader = mockk(relaxed = true),
            dropboxSyncScheduler = DropboxSyncScheduler(workManager, dropboxAuthRepository),
            dropboxSyncOutcomeStore = outcomeStore
        )
    }

    @Test
    fun `auto-sync on init enqueues non-expedited work only when connected`() {
        buildViewModel(isConnected = MutableStateFlow(false))
        assertTrue(requests.isEmpty())

        buildViewModel(isConnected = MutableStateFlow(true))
        val spec = requests.single().workSpec
        assertFalse(spec.expedited)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertFalse(spec.input.getBoolean(DropboxSyncWorker.KEY_IS_USER_INITIATED, true))
    }

    @Test
    fun `isSyncing reflects unfinished work from WorkManager immediately`() {
        val infos = MutableStateFlow(listOf(workInfo(WorkInfo.State.RUNNING)))
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns infos
        val vm = buildViewModel(isConnected = MutableStateFlow(false))
        assertTrue(waitFor(5, TimeUnit.SECONDS) { vm.isSyncing.value })

        infos.value = listOf(workInfo(WorkInfo.State.SUCCEEDED))
        assertTrue(waitFor(5, TimeUnit.SECONDS) { !vm.isSyncing.value })
    }

    private fun workInfo(state: WorkInfo.State) =
        WorkInfo(UUID.randomUUID(), state, emptySet())

    @Test
    fun `message outcome is exposed then consumed on clear`() {
        outcomeFlow.value = DropboxSyncOutcome.Message("Imported 2 new rides")
        val vm = buildViewModel(isConnected = MutableStateFlow(false))
        assertTrue(waitFor(5, TimeUnit.SECONDS) { vm.dropboxSyncMessage.value == "Imported 2 new rides" })

        vm.clearDropboxSyncMessage()

        assertTrue(waitFor(5, TimeUnit.SECONDS) { vm.dropboxSyncMessage.value == null })
        assertNull(outcomeFlow.value)
    }

    @Test
    fun `reveal outcome surfaces as RideReveal then is consumed on clearImportState`() {
        val content = RideRevealContent(
            sessionId = 3L, headline = "New PR", distanceKm = 40.0, netDurationSec = 3600, elevationGainM = null
        )
        outcomeFlow.value = DropboxSyncOutcome.Reveal(content)
        val vm = buildViewModel(isConnected = MutableStateFlow(false))
        assertTrue(waitFor(5, TimeUnit.SECONDS) { vm.importState.value == ImportUiState.RideReveal(content) })

        vm.clearImportState()

        assertTrue(waitFor(5, TimeUnit.SECONDS) { vm.importState.value == ImportUiState.Idle })
        assertNull(outcomeFlow.value)
    }

    // ------------------------------------------------------------------------------------------
    // #194: Home multiselect bulk delete
    // ------------------------------------------------------------------------------------------

    /** Builds a [HomeViewModel] with relaxed Dropbox/clustering collaborators, disconnected so
     * init's auto-sync no-ops — these tests only exercise selection state and delete wiring. */
    private fun buildViewModelForSelection(
        sessionRepository: CyclingSessionRepository = FakeCyclingSessionRepository(),
        dropboxSyncCursorRepository: DropboxSyncCursorRepository = FakeDropboxSyncCursorRepository()
    ): HomeViewModel = buildViewModel(
        isConnected = MutableStateFlow(false),
        sessionRepository = sessionRepository,
        dropboxSyncCursorRepository = dropboxSyncCursorRepository
    )

    private fun buildSession(id: Long) = CyclingSession(
        id = id,
        fileName = "ride-$id.fit",
        fileSha1 = "sha-$id",
        sessionStart = Instant.parse("2026-01-01T00:00:00Z"),
        sessionEnd = Instant.parse("2026-01-01T01:00:00Z"),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = 30.0,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = false
    )

    /** Wraps a [FakeCyclingSessionRepository]'s `deleteSessions`, counting down [latch] once the
     * call completes (or is about to throw), since `deleteSelectedSessions` runs on the real
     * `Dispatchers.IO`, same as `syncDropbox` above. */
    private class LatchedDeleteSessionsRepository(
        val delegate: FakeCyclingSessionRepository = FakeCyclingSessionRepository(),
        private val latch: CountDownLatch,
        private val shouldFail: Boolean = false
    ) : CyclingSessionRepository by delegate {
        override suspend fun deleteSessions(ids: List<Long>) {
            if (shouldFail) {
                latch.countDown()
                throw RuntimeException("simulated bulk-delete failure")
            }
            delegate.deleteSessions(ids)
            latch.countDown()
        }
    }

    @Test
    fun `enterSelectionMode turns on selection mode with only the long-pressed session selected`() {
        val vm = buildViewModelForSelection()

        vm.enterSelectionMode(sessionId = 1L)

        assertTrue(vm.selectionMode.value)
        assertEquals(setOf(1L), vm.selectedSessionIds.value)
    }

    @Test
    fun `toggleSessionSelected adds and removes ids, and no-ops outside selection mode`() {
        val vm = buildViewModelForSelection()

        // Outside selection mode: no-op.
        vm.toggleSessionSelected(1L)
        assertTrue(vm.selectedSessionIds.value.isEmpty())

        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)
        assertEquals(setOf(1L, 2L), vm.selectedSessionIds.value)

        // Toggling an already-selected id deselects it (multiselect, not single-select).
        vm.toggleSessionSelected(1L)
        assertEquals(setOf(2L), vm.selectedSessionIds.value)
    }

    @Test
    fun `exitSelectionMode clears the selection mode flag and every selected id`() {
        val vm = buildViewModelForSelection()
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.exitSelectionMode()

        assertFalse(vm.selectionMode.value)
        assertTrue(vm.selectedSessionIds.value.isEmpty())
    }

    @Test
    fun `deleteSelectedSessions deletes every selected session and exits selection mode on success`() {
        val latch = CountDownLatch(1)
        val repo = LatchedDeleteSessionsRepository(latch = latch)
        repo.delegate.sessions.add(buildSession(1L))
        repo.delegate.sessions.add(buildSession(2L))
        repo.delegate.sessions.add(buildSession(3L))
        val vm = buildViewModelForSelection(sessionRepository = repo)
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.deleteSelectedSessions()

        assertTrue("deleteSessions was not invoked", latch.await(5, TimeUnit.SECONDS))
        // exitSelectionMode() itself is synchronous but runs right after the suspend delete
        // completes on the IO dispatcher, so wait briefly for that state update to land.
        val exited = waitFor(5, TimeUnit.SECONDS) { !vm.selectionMode.value }
        assertTrue("selection mode was not exited after a successful delete", exited)
        assertTrue(vm.selectedSessionIds.value.isEmpty())
        assertTrue(repo.delegate.sessions.none { it.id == 1L || it.id == 2L })
        assertTrue(repo.delegate.sessions.any { it.id == 3L })
        assertEquals(null, vm.deleteError.value)
    }

    @Test
    fun `deleteSelectedSessions surfaces an error and keeps selection mode active on failure`() {
        val latch = CountDownLatch(1)
        val repo = LatchedDeleteSessionsRepository(latch = latch, shouldFail = true)
        repo.delegate.sessions.add(buildSession(1L))
        repo.delegate.sessions.add(buildSession(2L))
        val vm = buildViewModelForSelection(sessionRepository = repo)
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.deleteSelectedSessions()

        assertTrue("deleteSessions was not invoked", latch.await(5, TimeUnit.SECONDS))
        val errorShown = waitFor(5, TimeUnit.SECONDS) { vm.deleteError.value != null }
        assertTrue("expected an error message after a failed delete", errorShown)
        assertEquals("Couldn't delete rides", vm.deleteError.value)
        assertTrue("selection mode must stay active so the user can retry", vm.selectionMode.value)
        assertEquals(setOf(1L, 2L), vm.selectedSessionIds.value)
        assertTrue(repo.delegate.sessions.any { it.id == 1L } && repo.delegate.sessions.any { it.id == 2L })
    }

    @Test
    fun `deleteSelectedSessions invalidates the Dropbox sync cursor on success so the rides can be re-synced`() {
        val latch = CountDownLatch(1)
        val repo = LatchedDeleteSessionsRepository(latch = latch)
        repo.delegate.sessions.add(buildSession(1L))
        repo.delegate.sessions.add(buildSession(2L))
        val cursorRepository = FakeDropboxSyncCursorRepository()
        val vm = buildViewModelForSelection(sessionRepository = repo, dropboxSyncCursorRepository = cursorRepository)
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.deleteSelectedSessions()

        assertTrue("deleteSessions was not invoked", latch.await(5, TimeUnit.SECONDS))
        val invalidated = waitFor(5, TimeUnit.SECONDS) { cursorRepository.invalidated }
        assertTrue("expected the Dropbox sync cursor to be invalidated after a successful bulk delete", invalidated)
    }

    @Test
    fun `deleteSelectedSessions does not touch the Dropbox sync cursor on failure`() {
        val latch = CountDownLatch(1)
        val repo = LatchedDeleteSessionsRepository(latch = latch, shouldFail = true)
        repo.delegate.sessions.add(buildSession(1L))
        repo.delegate.sessions.add(buildSession(2L))
        val cursorRepository = FakeDropboxSyncCursorRepository()
        val vm = buildViewModelForSelection(sessionRepository = repo, dropboxSyncCursorRepository = cursorRepository)
        vm.enterSelectionMode(sessionId = 1L)
        vm.toggleSessionSelected(2L)

        vm.deleteSelectedSessions()

        assertTrue("deleteSessions was not invoked", latch.await(5, TimeUnit.SECONDS))
        waitFor(5, TimeUnit.SECONDS) { vm.deleteError.value != null }
        assertFalse(cursorRepository.invalidated)
    }

    /** Polls [condition] on the test thread until it's true or [timeout] elapses. */
    private fun waitFor(timeout: Long, unit: TimeUnit, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
