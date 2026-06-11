package com.velometrics.app.data.dropbox

import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.NetworkIOException
import com.dropbox.core.RetryException
import com.dropbox.core.ServerException
import com.dropbox.core.oauth.DbxOAuthError
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.v2.auth.AuthError
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxSyncServiceTest {

    @Test
    fun `invalid access token is a persistent auth failure`() {
        val e = InvalidAccessTokenException("req-id", "invalid token", AuthError.INVALID_ACCESS_TOKEN)
        assertTrue(DropboxSyncService.isPersistentAuthFailure(e))
    }

    @Test
    fun `revoked refresh token is a persistent auth failure`() {
        val e = DbxOAuthException("req-id", DbxOAuthError(DbxOAuthError.INVALID_GRANT, "revoked"))
        assertTrue(DropboxSyncService.isPersistentAuthFailure(e))
    }

    @Test
    fun `other oauth errors are not persistent auth failures`() {
        val e = DbxOAuthException("req-id", DbxOAuthError(DbxOAuthError.INVALID_REQUEST, "bad request"))
        assertFalse(DropboxSyncService.isPersistentAuthFailure(e))
    }

    @Test
    fun `network failure is not a persistent auth failure`() {
        val e = NetworkIOException(IOException("no network"))
        assertFalse(DropboxSyncService.isPersistentAuthFailure(e))
    }

    @Test
    fun `rate limiting is not a persistent auth failure`() {
        val e = RetryException("req-id", "rate limited")
        assertFalse(DropboxSyncService.isPersistentAuthFailure(e))
    }

    @Test
    fun `server error is not a persistent auth failure`() {
        val e = ServerException("req-id", "server error")
        assertFalse(DropboxSyncService.isPersistentAuthFailure(e))
    }
}
