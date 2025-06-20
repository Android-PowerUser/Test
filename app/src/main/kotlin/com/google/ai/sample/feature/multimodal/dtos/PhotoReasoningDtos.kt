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
    val base64Image: String,
    // While the SDK's ImagePart takes a Bitmap, mimeType isn't directly part of its constructor.
    // We'll assume PNG for now or make it configurable if needed by the SDK upon reconstruction.
    // For simplicity, we'll just store the image data. The SDK might infer mimetype on load,
    // or the model might not need it explicitly if the image format is standard.
    // Let's keep it simple: just the base64 string. The reconstruction to Bitmap won't need a mimeType.
    // The SDK's `image(bitmap)` builder doesn't take a mimeType.
) : PartDto

// Placeholder for other Part types if they become necessary later.
// Example:
// @Serializable
// @SerialName("blob")
// data class BlobPartDto(
//     val mimeType: String,
//     val base64Data: String // Or ByteArray
// ) : PartDto
