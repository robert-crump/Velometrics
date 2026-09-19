package com.velometrics.app.data.dropbox

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.velometrics.app.domain.model.RideRevealContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DropboxSyncOutcomeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DropboxSyncOutcomeStore

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("outcome.preferences_pb").also { it.delete() }
        }
        store = DropboxSyncOutcomeStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val reveal = RideRevealContent(
        sessionId = 42L,
        headline = "New PR",
        distanceKm = 55.5,
        netDurationSec = 7200,
        elevationGainM = 640.0
    )

    @Test
    fun emitsNullWhenNothingPublished() = runBlocking {
        assertNull(store.outcome.first())
    }

    @Test
    fun publishesMessage() = runBlocking {
        store.publish(DropboxSyncOutcome.Message("Imported 3 new rides"))
        assertEquals(DropboxSyncOutcome.Message("Imported 3 new rides"), store.outcome.first())
    }

    @Test
    fun publishesRevealWithAndWithoutElevation() = runBlocking {
        store.publish(DropboxSyncOutcome.Reveal(reveal))
        assertEquals(DropboxSyncOutcome.Reveal(reveal), store.outcome.first())

        val flat = reveal.copy(elevationGainM = null)
        store.publish(DropboxSyncOutcome.Reveal(flat))
        assertEquals(DropboxSyncOutcome.Reveal(flat), store.outcome.first())
    }

    @Test
    fun lastWriteWinsWithoutStaleLeakage() = runBlocking {
        store.publish(DropboxSyncOutcome.Reveal(reveal))
        store.publish(DropboxSyncOutcome.Message("B"))
        assertEquals(DropboxSyncOutcome.Message("B"), store.outcome.first())

        store.publish(DropboxSyncOutcome.Reveal(reveal.copy(sessionId = 7L, elevationGainM = null)))
        assertEquals(
            DropboxSyncOutcome.Reveal(reveal.copy(sessionId = 7L, elevationGainM = null)),
            store.outcome.first()
        )
    }

    @Test
    fun consumeClearsOutcome() = runBlocking {
        store.publish(DropboxSyncOutcome.Message("A"))
        store.consume()
        assertNull(store.outcome.first())

        store.publish(DropboxSyncOutcome.Message("C"))
        assertEquals(DropboxSyncOutcome.Message("C"), store.outcome.first())
    }
}
