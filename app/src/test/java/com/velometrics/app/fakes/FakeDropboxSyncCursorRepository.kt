package com.velometrics.app.fakes

import com.velometrics.app.domain.repository.DropboxSyncCursorRepository

class FakeDropboxSyncCursorRepository : DropboxSyncCursorRepository {
    var invalidated = false
        private set

    override fun invalidateSyncCursor() {
        invalidated = true
    }
}
