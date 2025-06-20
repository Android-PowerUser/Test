package com.google.ai.sample.feature.multimodal.dtos

import android.graphics.Bitmap // Required for SDK ImagePart
import com.google.ai.sample.util.ImageUtils // Our Base64<->Bitmap utils
import com.google.ai.client.generativeai.type.Content // SDK Content
import com.google.ai.client.generativeai.type.Part // SDK Part
import com.google.ai.client.generativeai.type.TextPart // SDK TextPart
import com.google.ai.client.generativeai.type.ImagePart // SDK ImagePart
import com.google.ai.client.generativeai.type.content // SDK content builder

// --- SDK to DTO Mappers ---

fun Part.toDto(): PartDto {
    return when (this) {
        is TextPart -> TextPartDto(text = this.text)
        is ImagePart -> {
            // Assuming this.image is a Bitmap. The SDK's ImagePart constructor takes a Bitmap.
            val base64Image = ImageUtils.bitmapToBase64(this.image)
            ImagePartDto(base64Image = base64Image)
        }
        // Add other SDK Part types here if they become relevant
        // e.g., is BlobPart -> BlobPartDto(...)
        else -> throw IllegalArgumentException("Unsupported SDK Part type for DTO conversion: ${this.javaClass.name}")
    }
}

fun Content.toDto(): ContentDto {
    return ContentDto(
        role = this.role, // SDK Content has a nullable 'role' (String)
        parts = this.parts.map { it.toDto() } // parts is List<Part>
    )
}

// --- DTO to SDK Mappers ---

fun PartDto.toSdk(): Part {
    return when (this) {
        is TextPartDto -> TextPart(text = this.text)
        is ImagePartDto -> {
            val bitmap: Bitmap? = ImageUtils.base64ToBitmap(this.base64Image)
            // The SDK's image builder part of `content { image(bitmap) }` expects a non-null Bitmap.
            // If bitmap is null (due to bad Base64), we might throw an error or return a placeholder/skip.
            // For now, let's throw an error, as a null bitmap in an ImagePart usually indicates a problem.
            bitmap?.let { ImagePart(it) }
                ?: throw IllegalArgumentException("Failed to convert Base64 to Bitmap for ImagePartDto, or Base64 was invalid.")
        }
        // Add other PartDto types here
        // else -> throw IllegalArgumentException("Unsupported PartDto type for SDK conversion: ${this.javaClass.name}")
    }
}

fun ContentDto.toSdk(): Content {
    // The SDK's `content` builder is convenient here.
    // It takes a role (optional) and a block to define parts.
    val sdkParts = this.parts.map { it.toSdk() }
    return content(this.role) {
        sdkParts.forEach { sdkPart ->
            when (sdkPart) {
                is TextPart -> text(sdkPart.text)
                is ImagePart -> image(sdkPart.image) // sdkPart.image is the Bitmap
                // Add other SDK Part types here if they become relevant
                else -> throw IllegalArgumentException("Unsupported SDK Part type during ContentDto.toSdk() parts mapping: ${sdkPart.javaClass.name}")
            }
        }
    }
}
