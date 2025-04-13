package com.google.ai.sample.feature.multimodal

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the UI state for the PhotoReasoning feature
 */
class PhotoReasoningUiStateManager {
    private val _chatState = PhotoReasoningChatState()
    val chatState: PhotoReasoningChatState
        get() = _chatState
}
