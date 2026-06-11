package com.velometrics.app.data.dropbox

import android.content.Context
import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.v2.DbxClientV2
import com.velometrics.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Dropbox OAuth2 PKCE authorization flow and the connection
 * status of the linked Dropbox account.
 */
@Singleton
class DropboxAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: DropboxCredentialStore,
    private val requestConfig: DbxRequestConfig
) {
    companion object {
        private val SCOPES = listOf("files.metadata.read", "files.content.read")
    }

    private val _isConnected = MutableStateFlow(credentialStore.isConnected())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _needsReauth = MutableStateFlow(credentialStore.needsReauth())

    /** True if a persistent auth failure was detected and the user needs to reconnect Dropbox. */
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    /** Launches the browser/Custom Tab authorization flow. */
    fun startAuthFlow() {
        Auth.startOAuth2PKCE(context, BuildConfig.DROPBOX_APP_KEY, requestConfig, SCOPES)
    }

    /**
     * Checks for a completed authorization result. Should be called from
     * the host activity's onResume, since the Dropbox SDK delivers the
     * result via a static field rather than an activity result callback.
     */
    fun handleAuthResult() {
        val credential = Auth.getDbxCredential() ?: return
        credentialStore.saveCredential(credential)
        credentialStore.setNeedsReauth(false)
        _needsReauth.value = false
        _isConnected.value = true
    }

    /** Revokes the token remotely (best-effort) and clears local credentials. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val credential = credentialStore.getCredential()
        if (credential != null) {
            try {
                DbxClientV2(requestConfig, credential).auth().tokenRevoke()
            } catch (_: DbxException) {
                // Token may already be invalid/expired - still clear local state.
            }
        }
        credentialStore.clear()
        _isConnected.value = false
        _needsReauth.value = false
    }

    /** Marks the connection as needing reauthorization after a persistent auth failure during sync. */
    fun markNeedsReauth() {
        credentialStore.setNeedsReauth(true)
        _needsReauth.value = true
    }
}
