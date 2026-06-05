package com.cyclegraph.app.ui.intent

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class GpxIntentViewModel @Inject constructor() : ViewModel() {
    private val _pendingGpxUri = MutableStateFlow<Uri?>(null)
    val pendingGpxUri: StateFlow<Uri?> = _pendingGpxUri.asStateFlow()

    fun setPendingUri(uri: Uri) { _pendingGpxUri.value = uri }
    fun consumePendingUri() { _pendingGpxUri.value = null }
}
