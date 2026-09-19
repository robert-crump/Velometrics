package com.velometrics.app.data.dropbox

import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DbxUserFilesRequests
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderResult
import com.dropbox.core.DbxDownloader
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeFitImportService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.OutputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The cursor-safety rule (#203): the saved delta cursor must never advance past a page containing
 * an error, or that file becomes permanently unretryable. Runs against a fake [FitImportService]
 * and a mocked Dropbox SDK client.
 */
class DropboxSyncServiceCursorTest {

    private val folder = "/Rides"
    private val sessions = FakeCyclingSessionRepository()
    private val importer = FakeFitImportService(sessions)
    private val store = mockk<DropboxCredentialStore>(relaxed = true)
    private val files = mockk<DbxUserFilesRequests>()

    init {
        every { store.getCredential() } returns mockk<DbxCredential>()
        every { store.getSyncCursor(folder) } returns null
    }

    private fun service(): DropboxSyncService {
        val client = mockk<DbxClientV2>()
        every { client.files() } returns files
        val settings = mockk<UserSettingsRepository>()
        every { settings.dropboxSyncFolder } returns flowOf(folder)
        val factory = mockk<DropboxClientFactory>()
        every { factory.create(any()) } returns client
        return DropboxSyncService(store, factory, importer, settings, sessions)
    }

    private fun entry(fileName: String): FileMetadata {
        val downloader = mockk<DbxDownloader<FileMetadata>>()
        val metadata = mockk<FileMetadata>()
        every { metadata.name } returns fileName
        every { metadata.pathLower } returns "$folder/${fileName.lowercase()}"
        every { downloader.download(any<OutputStream>()) } returns metadata
        every { files.download("$folder/${fileName.lowercase()}") } returns downloader
        return metadata
    }

    private fun page(cursor: String, hasMore: Boolean, vararg names: String): ListFolderResult {
        val result = mockk<ListFolderResult>()
        every { result.entries } returns names.map { entry(it) }
        every { result.cursor } returns cursor
        every { result.hasMore } returns hasMore
        return result
    }

    @Test
    fun `the cursor is saved once a page is fully imported`() = runBlocking {
        every { files.listFolder(folder) } returns page("c1", hasMore = false, "a.fit", "b.fit")

        service().sync()

        verify(exactly = 1) { store.saveSyncCursor(folder, "c1") }
    }

    @Test
    fun `the cursor is not advanced past a page containing an error`() = runBlocking {
        importer.errors += "bad.fit"
        every { files.listFolder(folder) } returns page("c1", hasMore = false, "a.fit", "bad.fit")

        service().sync()

        verify(exactly = 0) { store.saveSyncCursor(any(), any()) }
    }

    @Test
    fun `a later page's error keeps the cursor at the last clean page`() = runBlocking {
        importer.errors += "bad.fit"
        every { files.listFolder(folder) } returns page("c1", hasMore = true, "a.fit")
        every { files.listFolderContinue("c1") } returns page("c2", hasMore = false, "bad.fit")

        service().sync()

        verify(exactly = 1) { store.saveSyncCursor(folder, "c1") }
        verify(exactly = 0) { store.saveSyncCursor(folder, "c2") }
    }
}
