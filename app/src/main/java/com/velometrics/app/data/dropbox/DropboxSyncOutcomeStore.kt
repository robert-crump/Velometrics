package com.velometrics.app.data.dropbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.velometrics.app.domain.model.RideRevealContent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncOutcomeDataStore: DataStore<Preferences> by preferencesDataStore(name = "dropbox_sync_outcome")

/** What to tell the user after a Dropbox sync run finished. */
sealed interface DropboxSyncOutcome {
    data class Message(val text: String) : DropboxSyncOutcome
    data class Reveal(val content: RideRevealContent) : DropboxSyncOutcome
}

/**
 * Persists the result of a Dropbox sync run independent of any ViewModel or process lifetime.
 * Last write wins; [consume] clears it once the UI has shown it.
 */
@Singleton
class DropboxSyncOutcomeStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.syncOutcomeDataStore)

    private companion object {
        val KEY_RUN_ID = longPreferencesKey("run_id")
        val KEY_KIND = stringPreferencesKey("kind")
        val KEY_MESSAGE = stringPreferencesKey("message")
        val KEY_SESSION_ID = longPreferencesKey("reveal_session_id")
        val KEY_HEADLINE = stringPreferencesKey("reveal_headline")
        val KEY_DISTANCE_KM = doublePreferencesKey("reveal_distance_km")
        val KEY_NET_DURATION_SEC = intPreferencesKey("reveal_net_duration_sec")
        val KEY_ELEVATION_GAIN_M = doublePreferencesKey("reveal_elevation_gain_m")

        const val KIND_MESSAGE = "message"
        const val KIND_REVEAL = "reveal"
    }

    val outcome: Flow<DropboxSyncOutcome?> = dataStore.data.map { prefs ->
        when (prefs[KEY_KIND]) {
            KIND_MESSAGE -> prefs[KEY_MESSAGE]?.let { DropboxSyncOutcome.Message(it) }
            KIND_REVEAL -> {
                val sessionId = prefs[KEY_SESSION_ID]
                val headline = prefs[KEY_HEADLINE]
                val distanceKm = prefs[KEY_DISTANCE_KM]
                val netDurationSec = prefs[KEY_NET_DURATION_SEC]
                if (sessionId == null || headline == null || distanceKm == null || netDurationSec == null) {
                    null
                } else {
                    DropboxSyncOutcome.Reveal(
                        RideRevealContent(
                            sessionId = sessionId,
                            headline = headline,
                            distanceKm = distanceKm,
                            netDurationSec = netDurationSec,
                            elevationGainM = prefs[KEY_ELEVATION_GAIN_M]
                        )
                    )
                }
            }
            else -> null
        }
    }

    suspend fun publish(outcome: DropboxSyncOutcome) {
        dataStore.edit { prefs ->
            val nextRunId = maxOf(System.currentTimeMillis(), (prefs[KEY_RUN_ID] ?: 0L) + 1)
            prefs.clear()
            prefs[KEY_RUN_ID] = nextRunId
            when (outcome) {
                is DropboxSyncOutcome.Message -> {
                    prefs[KEY_KIND] = KIND_MESSAGE
                    prefs[KEY_MESSAGE] = outcome.text
                }
                is DropboxSyncOutcome.Reveal -> {
                    val c = outcome.content
                    prefs[KEY_KIND] = KIND_REVEAL
                    prefs[KEY_SESSION_ID] = c.sessionId
                    prefs[KEY_HEADLINE] = c.headline
                    prefs[KEY_DISTANCE_KM] = c.distanceKm
                    prefs[KEY_NET_DURATION_SEC] = c.netDurationSec
                    c.elevationGainM?.let { prefs[KEY_ELEVATION_GAIN_M] = it }
                }
            }
        }
    }

    suspend fun consume() {
        dataStore.edit { prefs ->
            val runId = prefs[KEY_RUN_ID]
            prefs.clear()
            // Keep the run id so it stays monotonic across consume().
            runId?.let { prefs[KEY_RUN_ID] = it }
        }
    }
}
