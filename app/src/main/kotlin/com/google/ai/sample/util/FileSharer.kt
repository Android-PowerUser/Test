package com.google.ai.sample.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

fun shareTextFile(context: Context, fileName: String, content: String) {
    try {
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use {
            it.write(content.toByteArray())
        }

        val fileUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider", // Ensure this matches AndroidManifest.xml
            file
        )

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, fileUri)
            type = "text/plain" // Or "application/json" if specifically for JSON apps
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Entries"))
    } catch (e: Exception) {
        Log.e("FileSharer", "Error sharing file: $fileName", e)
        Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
