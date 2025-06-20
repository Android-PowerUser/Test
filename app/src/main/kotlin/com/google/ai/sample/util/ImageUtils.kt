package com.google.ai.sample.util // Or your chosen utility package

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        // Ensure quality is within a reasonable range (0-100)
        val safeQuality = quality.coerceIn(0, 100)
        val outputStream = ByteArrayOutputStream()
        // Compress format can be PNG for lossless (but larger) or JPEG for lossy (smaller)
        // PNG is generally safer for AI models if exact pixel data matters.
        // Let's use PNG as it's lossless and doesn't require quality for compression itself in the same way JPEG does.
        // For PNG, the 'quality' parameter is ignored by compress().
        // If JPEG was used: bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, outputStream)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream) // Quality is ignored for PNG
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP) // NO_WRAP is important for compact string
    }

    fun base64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: IllegalArgumentException) {
            // Log error or handle - Base64 string might be invalid
            android.util.Log.e("ImageUtils", "Error decoding Base64 string to Bitmap: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Unexpected error decoding Base64 string to Bitmap: ${e.message}")
            null
        }
    }
}
