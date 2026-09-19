package com.velometrics.app.data.dropbox

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import javax.inject.Inject

/** Builds the Dropbox SDK client; a seam so `DropboxSyncService` can be unit-tested against a mocked client. */
class DropboxClientFactory @Inject constructor(
    private val requestConfig: DbxRequestConfig
) {
    fun create(credential: DbxCredential): DbxClientV2 = DbxClientV2(requestConfig, credential)
}
