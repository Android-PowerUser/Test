package com.google.ai.sample.util // Or your chosen utility package

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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

    fun saveBitmapToTempFile(context: Context, bitmap: Bitmap): String? {
        return try {
            val cacheDir = File(context.cacheDir, "image_parts")
            cacheDir.mkdirs() // Ensure the directory exists

            // Create a unique filename
            val fileName = "temp_image_${System.currentTimeMillis()}.png"
            val tempFile = File(cacheDir, fileName)

            FileOutputStream(tempFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d("ImageUtils", "Bitmap saved to temp file: ${tempFile.absolutePath}")
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("ImageUtils", "Error saving bitmap to temp file: ${e.message}", e)
            null
        }
    }

    fun loadBitmapFromFile(filePath: String): Bitmap? {
        return try {
            if (!File(filePath).exists()) {
                Log.e("ImageUtils", "File not found for loading bitmap: $filePath")
                return null
            }
            BitmapFactory.decodeFile(filePath)
        } catch (e: Exception) {
            Log.e("ImageUtils", "Error loading bitmap from file: $filePath", e)
            null
        }
    }

    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d("ImageUtils", "Successfully deleted file: $filePath")
                } else {
                    Log.w("ImageUtils", "Failed to delete file: $filePath")
                }
                return deleted
            } else {
                Log.w("ImageUtils", "File not found for deletion: $filePath")
                return false // Or true if "not existing" means "successfully not there"
            }
        } catch (e: Exception) {
            Log.e("ImageUtils", "Error deleting file: $filePath", e)
            return false
        }
    }
}
