package com.google.ai.sample.feature.multimodal

import androidx.compose.runtime.toMutableStateList

class PhotoReasoningChatState(
    messages: List<PhotoReasoningMessage> = emptyList()
) {
    private val _messages: MutableList<PhotoReasoningMessage> = messages.toMutableStateList()
    val messages: List<PhotoReasoningMessage> = _messages

    fun addMessage(msg: PhotoReasoningMessage) {
        _messages.add(msg)
    }

    fun replaceLastPendingMessage() {
        val lastMessage = _messages.lastOrNull()
        lastMessage?.let {
            val newMessage = lastMessage.apply { isPending = false }
            _messages.removeLast()
            _messages.add(newMessage)
        }
    }
    
    fun clearMessages() {
        _messages.clear()
    }
}
