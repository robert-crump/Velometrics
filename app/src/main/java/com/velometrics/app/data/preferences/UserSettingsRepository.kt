package com.velometrics.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

@Singleton
class UserSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_FTP = intPreferencesKey("ftp")
        private val KEY_HOME_LAT = doublePreferencesKey("home_lat")
        private val KEY_HOME_LON = doublePreferencesKey("home_lon")
        private val KEY_HOME_DISPLAY_NAME = stringPreferencesKey("home_display_name")
        private val KEY_DROPBOX_SYNC_FOLDER = stringPreferencesKey("dropbox_sync_folder")
        private val KEY_MAX_HR = intPreferencesKey("max_hr")
    }

    val ftp: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_FTP] ?: CyclingConstants.DEFAULT_FTP
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

    suspend fun saveFtp(ftp: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FTP] = ftp
        }
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

    suspend fun saveMaxHr(maxHr: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAX_HR] = maxHr
        }
    }
}
