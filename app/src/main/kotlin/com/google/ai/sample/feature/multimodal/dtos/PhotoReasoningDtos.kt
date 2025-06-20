package com.google.ai.sample.feature.multimodal.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Note: The Gemini SDK's Content object can have a nullable 'role'.
// Parts are non-empty.

@Serializable
data class ContentDto(
    val role: String? = null, // e.g., "user", "model", "function"
    val parts: List<PartDto>
)

@Serializable
sealed interface PartDto

@Serializable
@SerialName("text") // This helps ensure the type is clearly identified in JSON if needed, good practice for sealed types
data class TextPartDto(val text: String) : PartDto

@Serializable
@SerialName("image")
data class ImagePartDto(
    val imageFilePath: String // Path to a temporary file holding the image
) : PartDto

// Placeholder for other Part types if they become necessary later.
// Example:
// @Serializable
// @SerialName("blob")
// data class BlobPartDto(
//     val mimeType: String,
//     val base64Data: String // Or ByteArray
// ) : PartDto
