package com.velometrics.app.data.dropbox

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dropbox.core.oauth.DbxCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the Dropbox refresh token (and associated access token) in
 * Android Keystore-backed [EncryptedSharedPreferences], separate from the
 * plain DataStore used for non-sensitive app settings.
 */
@Singleton
class DropboxCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_FILE_NAME = "dropbox_credentials"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_APP_KEY = "app_key"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredential(credential: DbxCredential) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, credential.accessToken)
            .putString(KEY_REFRESH_TOKEN, credential.refreshToken)
            .putLong(KEY_EXPIRES_AT, credential.expiresAt ?: 0L)
            .putString(KEY_APP_KEY, credential.appKey)
            .apply()
    }

    fun getCredential(): DbxCredential? {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val appKey = prefs.getString(KEY_APP_KEY, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it != 0L }
        return DbxCredential(accessToken, expiresAt, refreshToken, appKey)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isConnected(): Boolean = prefs.contains(KEY_REFRESH_TOKEN)
}
