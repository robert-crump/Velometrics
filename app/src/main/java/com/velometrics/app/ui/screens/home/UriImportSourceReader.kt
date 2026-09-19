package com.velometrics.app.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.velometrics.app.domain.service.ImportSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin Android adapter: turns picked [Uri]s into [ImportSource]s so `RideLifecycle` never sees a
 * `Uri` or `Context`. Bytes are read lazily, one file at a time, when the batch reaches that file.
 */
class UriImportSourceReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun sourcesFor(uris: List<Uri>): List<ImportSource> = uris.map { uri ->
        ImportSource(fileName = displayName(uri) ?: "unknown.fit") {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }
}
