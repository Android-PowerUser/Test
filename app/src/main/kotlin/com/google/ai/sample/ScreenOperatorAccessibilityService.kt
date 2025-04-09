package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaActionSound
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

class ScreenOperatorAccessibilityService : AccessibilityService() {
    private val TAG = "ScreenOperatorService"
    
    companion object {
        // Flag to indicate if a screenshot should be taken
        private var shouldTakeScreenshot = false
        
        // Callback to be executed after screenshot is taken
        private var onScreenshotTaken: (() -> Unit)? = null
        
        // Instance of the service
        private var instance: ScreenOperatorAccessibilityService? = null
        
        // Last screenshot URI
        private var lastScreenshotUri: Uri? = null
        
        // Timestamp when screenshot was taken
        private var screenshotTimestamp: Long = 0
        
        // Method to trigger screenshot from outside the service
        fun takeScreenshot(callback: () -> Unit) {
            Log.d("ScreenOperatorService", "takeScreenshot called")
            shouldTakeScreenshot = true
            onScreenshotTaken = callback
            
            // Record the timestamp when screenshot was requested
            screenshotTimestamp = System.currentTimeMillis()
            
            // If we have an instance, trigger the screenshot
            if (instance != null) {
                Log.d("ScreenOperatorService", "Instance available, triggering screenshot")
                instance?.performScreenshot()
            } else {
                Log.e("ScreenOperatorService", "No service instance available. Make sure the accessibility service is enabled in settings.")
                // Still call the callback to prevent blocking the UI
                Handler(Looper.getMainLooper()).postDelayed({
                    onScreenshotTaken?.invoke()
                    onScreenshotTaken = null
                }, 500)
            }
        }
        
        // Get the latest screenshot URI directly from MediaStore
        fun getLatestScreenshotUri(): Uri? {
            // If we already have a URI from a recent screenshot, return it
            if (lastScreenshotUri != null) {
                Log.d("ScreenOperatorService", "Returning cached screenshot URI: $lastScreenshotUri")
                return lastScreenshotUri
            }
            
            // Try to get the latest image from MediaStore
            val context = instance?.applicationContext
            if (context != null) {
                try {
                    val uri = getLatestImageFromMediaStore(context)
                    if (uri != null) {
                        lastScreenshotUri = uri
                        return uri
                    }
                } catch (e: Exception) {
                    Log.e("ScreenOperatorService", "Error getting image from MediaStore: ${e.message}")
                }
            }
            
            // If MediaStore approach failed, try direct file access
            val screenshotFile = findLatestScreenshotFile()
            if (screenshotFile != null && screenshotFile.exists()) {
                val uri = Uri.fromFile(screenshotFile)
                lastScreenshotUri = uri
                return uri
            }
            
            return null
        }
        
        // Find the latest screenshot file from various possible locations
        private fun findLatestScreenshotFile(): File? {
            // Try multiple possible screenshot locations
            val possiblePaths = listOf(
                "/sdcard/Pictures/Screenshots",
                "/storage/emulated/0/Pictures/Screenshots",
                "/storage/emulated/0/DCIM/Screenshots",
                "/storage/emulated/0/Pictures",
                "/storage/emulated/0/DCIM",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath
            )
            
            var latestFile: File? = null
            var latestModified: Long = 0
            
            for (path in possiblePaths) {
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    Log.d("ScreenOperatorService", "Checking directory: $path")
                    val files = dir.listFiles()
                    if (files != null && files.isNotEmpty()) {
                        // Filter for image files
                        val imageFiles = files.filter { 
                            it.name.lowercase().endsWith(".jpg") || 
                            it.name.lowercase().endsWith(".jpeg") || 
                            it.name.lowercase().endsWith(".png") 
                        }
                        
                        if (imageFiles.isNotEmpty()) {
                            // Find the most recently modified file
                            val mostRecent = imageFiles.maxByOrNull { it.lastModified() }
                            if (mostRecent != null && mostRecent.lastModified() > latestModified) {
                                latestFile = mostRecent
                                latestModified = mostRecent.lastModified()
                                Log.d("ScreenOperatorService", "Found newer file: ${mostRecent.absolutePath}, Modified: ${mostRecent.lastModified()}")
                            }
                        }
                    }
                }
            }
            
            if (latestFile != null) {
                Log.d("ScreenOperatorService", "Latest screenshot file: ${latestFile.absolutePath}")
                return latestFile
            }
            
            Log.e("ScreenOperatorService", "No screenshot files found in any location")
            return null
        }
        
        // Get the latest image from MediaStore, focusing on images created after screenshot was taken
        private fun getLatestImageFromMediaStore(context: Context): Uri? {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
            
            // Look for images created after our screenshot timestamp
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf((screenshotTimestamp / 1000).toString()) // Convert to seconds for MediaStore
            
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )
                
                if (cursor != null && cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn)
                    val date = cursor.getLong(dateColumn)
                    
                    Log.d("ScreenOperatorService", "Found image in MediaStore: $path, Date: $date")
                    
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            } catch (e: Exception) {
                Log.e("ScreenOperatorService", "Error querying MediaStore: ${e.message}")
            } finally {
                cursor?.close()
            }
            
            // If no images found after screenshot timestamp, try getting the most recent image
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )
                
                if (cursor != null && cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn)
                    
                    Log.d("ScreenOperatorService", "Found most recent image in MediaStore: $path")
                    
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            } catch (e: Exception) {
                Log.e("ScreenOperatorService", "Error querying MediaStore for most recent: ${e.message}")
            } finally {
                cursor?.close()
            }
            
            return null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Handle accessibility events here
        Log.d(TAG, "Received accessibility event: ${event.eventType}")
    }

    override fun onInterrupt() {
        // Handle interruption of the accessibility service
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Service is connected, store instance
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }
    
    // Method to take a screenshot using the global action
    fun performScreenshot() {
        if (!shouldTakeScreenshot) return
        
        Log.d(TAG, "Taking screenshot...")
        
        // Play the camera shutter sound
        val sound = MediaActionSound()
        sound.play(MediaActionSound.SHUTTER_CLICK)
        
        // Reset the last screenshot URI
        lastScreenshotUri = null
        
        // Perform the screenshot action
        val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        Log.d(TAG, "Screenshot action result: $result")
        
        // Wait a moment for the screenshot to be saved
        Handler(Looper.getMainLooper()).postDelayed({
            // Reset the flag
            shouldTakeScreenshot = false
            
            // Execute the callback
            Log.d(TAG, "Executing screenshot callback")
            onScreenshotTaken?.invoke()
            onScreenshotTaken = null
            
            // Broadcast that a screenshot was taken to refresh media scanner
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            getLatestScreenshotUri()?.let { uri ->
                Log.d(TAG, "Screenshot URI: $uri")
                intent.data = uri
                sendBroadcast(intent)
            }
        }, 500) // Reduced wait time to 500ms
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
