package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.MediaActionSound
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import java.io.File

class ScreenOperatorAccessibilityService : AccessibilityService() {
    private val TAG = "ScreenOperatorService"
    
    companion object {
        // Flag to indicate if a screenshot should be taken
        private var shouldTakeScreenshot = false
        
        // Callback to be executed after screenshot is taken
        private var onScreenshotTaken: (() -> Unit)? = null
        
        // Instance of the service
        private var instance: ScreenOperatorAccessibilityService? = null
        
        // Last screenshot file
        private var lastScreenshotFile: File? = null
        
        // Method to trigger screenshot from outside the service
        fun takeScreenshot(callback: () -> Unit) {
            Log.d("ScreenOperatorService", "takeScreenshot called")
            shouldTakeScreenshot = true
            onScreenshotTaken = callback
            
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
        
        // Get the most recent screenshot from the Screenshots directory
        fun getLatestScreenshot(): File? {
            val screenshotsDir = File("/sdcard/Pictures/Screenshots")
            if (!screenshotsDir.exists() || !screenshotsDir.isDirectory) {
                Log.e("ScreenOperatorService", "Screenshots directory does not exist")
                return null
            }
            
            val files = screenshotsDir.listFiles()
            if (files == null || files.isEmpty()) {
                Log.e("ScreenOperatorService", "No files found in Screenshots directory")
                return null
            }
            
            val latestFile = files.maxByOrNull { it.lastModified() }
            Log.d("ScreenOperatorService", "Latest screenshot: ${latestFile?.absolutePath}, Modified: ${latestFile?.lastModified()}")
            
            // Store the last screenshot file
            lastScreenshotFile = latestFile
            return latestFile
        }
        
        // Get the URI for the latest screenshot
        fun getLatestScreenshotUri(): Uri? {
            val file = lastScreenshotFile ?: getLatestScreenshot()
            return if (file != null && file.exists()) {
                Uri.fromFile(file)
            } else {
                null
            }
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
        
        // Perform the screenshot action
        val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        Log.d(TAG, "Screenshot action result: $result")
        
        // Wait a moment for the screenshot to be saved
        Handler(Looper.getMainLooper()).postDelayed({
            // Reset the flag
            shouldTakeScreenshot = false
            
            // Get the latest screenshot and store it
            getLatestScreenshot()
            
            // Execute the callback
            Log.d(TAG, "Executing screenshot callback")
            onScreenshotTaken?.invoke()
            onScreenshotTaken = null
            
            // Broadcast that a screenshot was taken to refresh media scanner
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            lastScreenshotFile?.let { file ->
                Log.d(TAG, "Screenshot taken: ${file.absolutePath}")
                intent.data = Uri.fromFile(file)
                sendBroadcast(intent)
            }
        }, 2000) // Wait 2 seconds for the screenshot to be saved
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
