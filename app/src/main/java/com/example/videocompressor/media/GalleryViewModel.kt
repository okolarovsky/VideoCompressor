package com.example.videocompressor.media

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class GalleryUiState {
    /** Nothing granted yet, or the user denied. Shows the explanation and the grant button. */
    data object Initial : GalleryUiState()

    /** Only the user-selected subset is visible (Android 14+ partial access). */
    data class LimitedAccess(val items: List<MediaItem>) : GalleryUiState()

    /** Whole library visible. */
    data class FullAccess(val items: List<MediaItem>) : GalleryUiState()
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _uiState = MutableStateFlow<GalleryUiState>(GalleryUiState.Initial)
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    /** Called after every permission result and on ON_RESUME. Loads items only when some access is granted. */
    fun refresh(level: AccessLevel) {
        when (level) {
            AccessLevel.FULL -> viewModelScope.launch {
                _uiState.value = GalleryUiState.FullAccess(repository.loadAll())
            }
            AccessLevel.LIMITED -> viewModelScope.launch {
                _uiState.value = GalleryUiState.LimitedAccess(repository.loadAll())
            }
            AccessLevel.NONE -> _uiState.value = GalleryUiState.Initial
        }
    }
}
