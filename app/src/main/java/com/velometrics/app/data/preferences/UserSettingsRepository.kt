package com.velometrics.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.velometrics.app.util.CyclingConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_settings",
    produceMigrations = { listOf(SplitSystemWeightMigration) }
)

private val KEY_SYSTEM_WEIGHT_KG = intPreferencesKey("system_weight_kg")
private val KEY_RIDER_WEIGHT_KG = intPreferencesKey("rider_weight_kg")
private val KEY_BIKE_KIT_WEIGHT_KG = intPreferencesKey("bike_kit_weight_kg")

/**
 * #237: the single system weight (#228) becomes rider weight = system − bike/kit default, bike/kit = default.
 * An unset system weight stays unset, so the assumed default still applies.
 */
internal object SplitSystemWeightMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData.contains(KEY_SYSTEM_WEIGHT_KG)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val system = prefs.remove(KEY_SYSTEM_WEIGHT_KG) ?: return prefs
        val bikeKit = CyclingConstants.DEFAULT_BIKE_KIT_WEIGHT_KG
        prefs[KEY_RIDER_WEIGHT_KG] = system - bikeKit
        prefs[KEY_BIKE_KIT_WEIGHT_KG] = bikeKit
        return prefs
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class UserSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LegacyFtpStore {
    companion object {
        private val KEY_FTP = intPreferencesKey("ftp")
        private val KEY_HOME_LAT = doublePreferencesKey("home_lat")
        private val KEY_HOME_LON = doublePreferencesKey("home_lon")
        private val KEY_HOME_DISPLAY_NAME = stringPreferencesKey("home_display_name")
        private val KEY_DROPBOX_SYNC_FOLDER = stringPreferencesKey("dropbox_sync_folder")
        private val KEY_MAX_HR = intPreferencesKey("max_hr")
        private val KEY_SPEED_IQ_SHOW_ON_MAP = booleanPreferencesKey("speed_iq_show_on_map")
    }

    val homeLat: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOME_LAT] ?: CyclingConstants.HOME_LAT
    }

    val homeLon: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOME_LON] ?: CyclingConstants.HOME_LON
    }

    val homeDisplayName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOME_DISPLAY_NAME] ?: ""
    }

    val dropboxSyncFolder: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DROPBOX_SYNC_FOLDER] ?: CyclingConstants.DEFAULT_DROPBOX_SYNC_FOLDER
    }

    val maxHr: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAX_HR] ?: CyclingConstants.DEFAULT_MAX_HR
    }

    /** Body weight (#237); null until set. */
    val riderWeightKg: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[KEY_RIDER_WEIGHT_KG]
    }

    val bikeKitWeightKg: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIKE_KIT_WEIGHT_KG] ?: CyclingConstants.DEFAULT_BIKE_KIT_WEIGHT_KG
    }

    /** Rider + bike + kit for Speed IQ (#228, #237), stored per ride at import; null while the rider weight is unset. */
    val systemWeightKg: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[KEY_RIDER_WEIGHT_KG]?.let { it + (prefs[KEY_BIKE_KIT_WEIGHT_KG] ?: CyclingConstants.DEFAULT_BIKE_KIT_WEIGHT_KG) }
    }

    /** Speed IQ "Show on map" toggle (#230): one switch for every ride, off until turned on. */
    val speedIqShowOnMap: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SPEED_IQ_SHOW_ON_MAP] ?: false
    }

    // FTP now lives in FtpHistoryRepository (#218); this reads and clears the old single setting once.
    override suspend fun takeLegacyFtp(): Int? {
        var legacy: Int? = null
        context.dataStore.edit { prefs ->
            legacy = prefs[KEY_FTP]
            prefs.remove(KEY_FTP)
        }
        return legacy
    }

    suspend fun saveHomeLocation(lat: Double, lon: Double, displayName: String = "") {
        context.dataStore.edit { prefs ->
            prefs[KEY_HOME_LAT] = lat
            prefs[KEY_HOME_LON] = lon
            prefs[KEY_HOME_DISPLAY_NAME] = displayName
        }
    }

    suspend fun saveDropboxSyncFolder(path: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DROPBOX_SYNC_FOLDER] = path
        }
    }

    suspend fun saveRiderWeightKg(kg: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RIDER_WEIGHT_KG] = kg
        }
    }

    suspend fun saveBikeKitWeightKg(kg: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BIKE_KIT_WEIGHT_KG] = kg
        }
    }

    suspend fun saveSpeedIqShowOnMap(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SPEED_IQ_SHOW_ON_MAP] = show
        }
    }

    suspend fun saveMaxHr(maxHr: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAX_HR] = maxHr
        }
    }
}
