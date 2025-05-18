package io.github.Android-PowerUser.feature.multimodal

import java.util.UUID

enum class PhotoParticipant {
    USER, MODEL, ERROR
}

data class PhotoReasoningMessage(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "",
    val participant: PhotoParticipant = PhotoParticipant.USER,
    var isPending: Boolean = false,
    val imageUris: List<String> = emptyList() // Store image URIs for multimodal messages
)
