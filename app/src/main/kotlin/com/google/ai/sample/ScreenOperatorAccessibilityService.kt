package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Path
import android.graphics.Rect
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.ai.sample.util.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class ScreenOperatorAccessibilityService : AccessibilityService() {
    
    companion object {
        // Log tag
        private const val TAG = "ScreenOperatorService"
        
        // Flag to indicate if a screenshot should be taken
        private var shouldTakeScreenshot = false
        
        // Callback to be executed after screenshot is taken
        private var onScreenshotTaken: ((Uri?) -> Unit)? = null
        
        // Instance of the service
        @Volatile
        private var instance: ScreenOperatorAccessibilityService? = null
        
        // Service connection state
        @Volatile
        private var isServiceConnected = false
        
        // Last screenshot URI
        private var lastScreenshotUri: Uri? = null
        
        // Timestamp when screenshot was taken
        private var screenshotTimestamp: Long = 0
        
        // Debug mode for extra logging
        private var debugMode = true
        
        // Method to check if service is available
        fun isServiceAvailable(): Boolean {
            val available = instance != null && isServiceConnected
            Log.d(TAG, "Service availability check: $available")
            
            if (!available) {
                // Log detailed information about why service is not available
                if (instance == null) {
                    Log.e(TAG, "Service instance is null - service may not be running")
                }
                if (!isServiceConnected) {
                    Log.e(TAG, "Service is not connected - onServiceConnected may not have been called")
                }
            }
            
            return available
        }
        
        // Method to check if accessibility service is enabled in system settings
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val serviceName = context.packageName + "/" + ScreenOperatorAccessibilityService::class.java.canonicalName
            val isEnabled = enabledServices?.contains(serviceName) == true
            
            Log.d(TAG, "Accessibility service enabled check: $isEnabled")
            Log.d(TAG, "Service name: $serviceName")
            Log.d(TAG, "Enabled services: $enabledServices")
            
            if (!isEnabled) {
                // Show a notification to the user
                showGlobalNotification(
                    "Accessibility service is not enabled. Please enable it in Settings > Accessibility > Screen Operator.",
                    true
                )
                
                // Open accessibility settings if we have a MainActivity instance
                val mainActivity = MainActivity.getInstance()
                if (mainActivity != null) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            mainActivity.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
                        }
                    }
                }
            }
            
            return isEnabled
        }
        
        // Method to trigger screenshot from outside the service
        fun takeScreenshot(callback: (Uri?) -> Unit) {
            Log.d(TAG, "takeScreenshot called")
            shouldTakeScreenshot = true
            onScreenshotTaken = callback
            
            // Record the timestamp when screenshot was requested
            screenshotTimestamp = System.currentTimeMillis()
            
            // Check if service is available
            if (!isServiceAvailable()) {
                Log.e(TAG, "Accessibility service is not available for taking screenshot")
                showGlobalNotification("Accessibility service is not available. Please enable it in settings.", true)
                // Still call the callback to prevent blocking the UI
                Handler(Looper.getMainLooper()).postDelayed({
                    onScreenshotTaken?.invoke(null)
                    onScreenshotTaken = null
                }, 500)
                return
            }
            
            // If we have an instance, trigger the screenshot
            if (instance != null) {
                Log.d(TAG, "Instance available, triggering screenshot")
                instance?.performScreenshot()
            } else {
                Log.e(TAG, "No service instance available. Make sure the accessibility service is enabled in settings.")
                showGlobalNotification("No service instance available. Please enable accessibility service in settings.", true)
                // Still call the callback to prevent blocking the UI
                Handler(Looper.getMainLooper()).postDelayed({
                    onScreenshotTaken?.invoke(null)
                    onScreenshotTaken = null
                }, 500)
            }
        }
        
        // Get the latest screenshot URI directly from MediaStore
        fun getLatestScreenshotUri(): Uri? {
            // If we already have a URI from a recent screenshot, return it
            if (lastScreenshotUri != null) {
                Log.d(TAG, "Returning cached screenshot URI: $lastScreenshotUri")
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
                    Log.e(TAG, "Error getting image from MediaStore: ${e.message}")
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
                    Log.d(TAG, "Checking directory: $path")
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
                                Log.d(TAG, "Found newer file: ${mostRecent.absolutePath}, Modified: ${mostRecent.lastModified()}")
                            }
                        }
                    }
                }
            }
            
            if (latestFile != null) {
                Log.d(TAG, "Latest screenshot file: ${latestFile.absolutePath}")
                return latestFile
            }
            
            Log.e(TAG, "No screenshot files found in any location")
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
                    
                    Log.d(TAG, "Found image in MediaStore: $path, Date: $date")
                    
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore: ${e.message}")
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
                    
                    Log.d(TAG, "Found most recent image in MediaStore: $path")
                    
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore for most recent: ${e.message}")
            } finally {
                cursor?.close()
            }
            
            return null
        }
        
        // Global notification method that works even if instance is null
        private fun showGlobalNotification(message: String, isError: Boolean) {
            Handler(Looper.getMainLooper()).post {
                val activity = MainActivity.getInstance()
                if (activity != null) {
                    activity.updateStatusMessage(message, isError)
                } else {
                    // If activity is not available, at least log the message
                    if (isError) {
                        Log.e(TAG, message)
                    } else {
                        Log.d(TAG, message)
                    }
                }
            }
        }
        
        // Execute a command using the accessibility service with retry logic
        fun executeCommand(command: Command) {
            Log.d(TAG, "Command received: $command")
            
            // Show notification that a command is being attempted
            val commandType = when (command) {
                is Command.ClickButton -> "clickOnButton(\"${command.buttonText}\")"
                is Command.TapCoordinates -> "tapAtCoordinates(${command.x}, ${command.y})"
                is Command.TakeScreenshot -> "takeScreenshot()"
            }
            
            showGlobalNotification("Attempting to execute: $commandType", false)
            
            // Check if service is available
            if (!isServiceAvailable()) {
                val errorMsg = "Accessibility service is not available. Please enable it in settings."
                Log.e(TAG, errorMsg)
                showGlobalNotification(errorMsg, true)
                
                // Try to check if the service is enabled
                val context = MainActivity.getInstance()?.applicationContext
                if (context != null) {
                    val isEnabled = isAccessibilityServiceEnabled(context)
                    if (!isEnabled) {
                        showGlobalNotification("Accessibility service is not enabled. Please enable it in settings.", true)
                    } else {
                        showGlobalNotification("Accessibility service is enabled but not connected. Please restart the app.", true)
                    }
                }
                
                return
            }
            
            // Use a coroutine to handle potential waiting
            CoroutineScope(Dispatchers.Main).launch {
                // Try to wait for service to connect if it's not ready
                var retryCount = 0
                while (!isServiceConnected && retryCount < 5) {
                    Log.d(TAG, "Service not connected, waiting... (attempt ${retryCount + 1})")
                    showGlobalNotification("Service not connected, waiting... (attempt ${retryCount + 1})", false)
                    delay(500)
                    retryCount++
                }
                
                // Get the current instance
                val currentInstance = instance
                if (currentInstance == null) {
                    val errorMsg = "No service instance available after waiting. Make sure the accessibility service is enabled in settings."
                    Log.e(TAG, errorMsg)
                    showGlobalNotification(errorMsg, true)
                    return@launch
                }
                
                try {
                    when (command) {
                        is Command.ClickButton -> {
                            Log.d(TAG, "Executing clickOnButton command: ${command.buttonText}")
                            showGlobalNotification("Executing: clickOnButton(\"${command.buttonText}\")", false)
                            
                            // Add a small delay before attempting to find and click
                            delay(300)
                            
                            // Direct call to the method on the instance
                            val result = currentInstance.findAndClickButtonByText(command.buttonText)
                            Log.d(TAG, "Click result: $result")
                            
                            // Show a Toast to provide feedback
                            if (result) {
                                currentInstance.notifyUser("Button click successful: ${command.buttonText}", false)
                            } else {
                                // If direct click failed, try alternative methods
                                showGlobalNotification("Direct click failed, trying alternative methods...", false)
                                
                                // Try to find by content description
                                val contentDescResult = currentInstance.findAndClickButtonByContentDescription(command.buttonText)
                                if (contentDescResult) {
                                    currentInstance.notifyUser("Button click by content description successful: ${command.buttonText}", false)
                                } else {
                                    // Try to find by class name that contains the text
                                    val classNameResult = currentInstance.findAndClickButtonByClassName(command.buttonText)
                                    if (classNameResult) {
                                        currentInstance.notifyUser("Button click by class name successful: ${command.buttonText}", false)
                                    } else {
                                        currentInstance.notifyUser("All button click attempts failed: ${command.buttonText}", true)
                                    }
                                }
                            }
                        }
                        is Command.TapCoordinates -> {
                            Log.d(TAG, "Executing tapAtCoordinates command: ${command.x}, ${command.y}")
                            showGlobalNotification("Executing: tapAtCoordinates(${command.x}, ${command.y})", false)
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                // Add a small delay before attempting to tap
                                delay(300)
                                
                                // Direct call to the method on the instance
                                val result = currentInstance.tapAtCoordinates(command.x, command.y)
                                Log.d(TAG, "Tap result: $result")
                                
                                // Show a Toast to provide feedback
                                if (result) {
                                    currentInstance.notifyUser("Tap successful at: ${command.x}, ${command.y}", false)
                                } else {
                                    // If direct tap failed, try with longer duration
                                    showGlobalNotification("Direct tap failed, trying with longer duration...", false)
                                    val longTapResult = currentInstance.tapAtCoordinatesWithLongerDuration(command.x, command.y)
                                    if (longTapResult) {
                                        currentInstance.notifyUser("Long-duration tap successful at: ${command.x}, ${command.y}", false)
                                    } else {
                                        currentInstance.notifyUser("All tap attempts failed at: ${command.x}, ${command.y}", true)
                                    }
                                }
                            } else {
                                Log.e(TAG, "Tap at coordinates requires API level 24 or higher")
                                currentInstance.notifyUser("Tap requires Android 7.0+", true)
                            }
                        }
                        is Command.TakeScreenshot -> {
                            Log.d(TAG, "Executing takeScreenshot command")
                            showGlobalNotification("Executing: takeScreenshot()", false)
                            
                            // Take a screenshot and add it to the current conversation
                            takeScreenshotAndAddToConversation()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing command: ${e.message}", e)
                    currentInstance.notifyUser("Error executing command: ${e.message}", true)
                }
            }
        }
        
        // Take a screenshot and add it to the current conversation
        private fun takeScreenshotAndAddToConversation() {
            // Show a toast to indicate we're taking a screenshot
            instance?.notifyUser("Taking screenshot...", false)
            
            // Take the screenshot
            takeScreenshot { screenshotUri ->
                // This will be called after the screenshot is taken
                CoroutineScope(Dispatchers.Main).launch {
                    if (screenshotUri != null) {
                        Log.d(TAG, "Screenshot taken and will be added to conversation: $screenshotUri")
                        
                        // Show a toast to indicate the screenshot was added
                        instance?.notifyUser("Screenshot added to conversation", false)
                        
                        // Get the current activity context
                        val currentActivity = MainActivity.getInstance()
                        if (currentActivity != null) {
                            Log.d(TAG, "MainActivity instance found")
                            // Get the PhotoReasoningViewModel and add the screenshot to the conversation
                            val viewModel = currentActivity.getPhotoReasoningViewModel()
                            if (viewModel != null) {
                                Log.d(TAG, "PhotoReasoningViewModel found, adding screenshot to conversation")
                                // Add the screenshot to the conversation after 1 second
                                Handler(Looper.getMainLooper()).postDelayed({
                                    viewModel.addScreenshotToConversation(screenshotUri, currentActivity)
                                    Log.d(TAG, "Automatically sent screenshot to AI after 1 second")
                                }, 1000) // 1 second delay
                            } else {
                                Log.e(TAG, "PhotoReasoningViewModel is null")
                                instance?.notifyUser("Error: Could not find PhotoReasoningViewModel", true)
                            }
                        } else {
                            Log.e(TAG, "MainActivity instance is null")
                            instance?.notifyUser("Error: Could not find MainActivity instance", true)
                        }
                    } else {
                        Log.e(TAG, "Failed to take screenshot or get URI")
                        instance?.notifyUser("Failed to take screenshot", true)
                    }
                }
            }
        }
    }
    
    // Notify user with a message
    fun notifyUser(message: String, isError: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            applicationContext?.let { context ->
                val toastLength = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(context, message, toastLength).show()
                
                // Log the message
                if (isError) {
                    Log.e(TAG, message)
                } else {
                    Log.d(TAG, message)
                }
                
                // Optionally, update UI with status
                MainActivity.getInstance()?.updateStatusMessage(message, isError)
            }
        }
    }
    
    // Check permissions before performing operations
    private fun checkPermissionsBeforeOperation(): Boolean {
        val mainActivity = MainActivity.getInstance()
        if (mainActivity != null) {
            val permissionsGranted = mainActivity.areAllPermissionsGranted()
            if (!permissionsGranted) {
                Log.e(TAG, "Required permissions are not granted")
                notifyUser("Required permissions are not granted. Please grant all permissions in settings.", true)
            }
            return permissionsGranted
        }
        Log.e(TAG, "MainActivity instance is null, cannot check permissions")
        notifyUser("Cannot check permissions: MainActivity instance is null", true)
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log accessibility events for debugging
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    Log.d(TAG, "Accessibility event: View clicked")
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    Log.d(TAG, "Accessibility event: View focused")
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Accessibility event: Window state changed")
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    Log.d(TAG, "Accessibility event: Window content changed")
                }
                else -> {
                    // Handle all other event types
                    Log.d(TAG, "Accessibility event: Other event type: ${it.eventType}")
                }
            }
        }
    }

    override fun onInterrupt() {
        // Handle interruption of the accessibility service
        Log.d(TAG, "Accessibility service interrupted")
        isServiceConnected = false
        notifyUser("Accessibility service interrupted", true)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Service is connected, store instance
        instance = this
        isServiceConnected = true
        Log.d(TAG, "Accessibility service connected")
        
        // Notify that the service is ready
        notifyUser("Screen Operator Service Connected", false)
        
        // Set service capabilities
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        
        // Handle deprecated flag with version check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME
        } else {
            @Suppress("DEPRECATION")
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }
        
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        
        Log.d(TAG, "Service capabilities set")
        
        // Refresh the root node to ensure we have access to the current window
        refreshRootNode()
    }
    
    // Refresh the root node to ensure we have access to the current window
    private fun refreshRootNode() {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                Log.d(TAG, "Root node refreshed successfully")
                rootNode.recycle()
            } else {
                Log.e(TAG, "Root node is null after refresh attempt")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing root node: ${e.message}")
        }
    }
    
    // Method to take a screenshot using the global action
    fun performScreenshot() {
        if (!shouldTakeScreenshot) return
        
        // Check permissions first
        if (!checkPermissionsBeforeOperation()) {
            Log.e(TAG, "Cannot take screenshot: missing permissions")
            notifyUser("Cannot take screenshot: missing permissions", true)
            onScreenshotTaken?.invoke(null)
            onScreenshotTaken = null
            return
        }
        
        Log.d(TAG, "Taking screenshot...")
        
        // Play the camera shutter sound
        val sound = MediaActionSound()
        sound.play(MediaActionSound.SHUTTER_CLICK)
        
        // Reset the last screenshot URI
        lastScreenshotUri = null
        
        // Perform the screenshot action
        val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        Log.d(TAG, "Screenshot action result: $result")
        
        if (!result) {
            notifyUser("Failed to take screenshot", true)
            onScreenshotTaken?.invoke(null)
            onScreenshotTaken = null
            return
        }
        
        // Wait a moment for the screenshot to be saved
        Handler(Looper.getMainLooper()).postDelayed({
            // Reset the flag
            shouldTakeScreenshot = false
            
            // Get the latest screenshot URI
            val screenshotUri = getLatestScreenshotUri()
            
            // Execute the callback with the screenshot URI
            Log.d(TAG, "Executing screenshot callback with URI: $screenshotUri")
            onScreenshotTaken?.invoke(screenshotUri)
            onScreenshotTaken = null
            
            // Broadcast that a screenshot was taken to refresh media scanner
            screenshotUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // On Android 10+, use MediaScanner
                    val contentResolver = applicationContext.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    try {
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating media store: ${e.message}")
                    }
                } else {
                    // On older versions, use the deprecated method
                    @Suppress("DEPRECATION")
                    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    intent.data = uri
                    sendBroadcast(intent)
                }
            }
        }, 500) // Reduced wait time to 500ms
    }
    
    // Find and click a button by its text
    fun findAndClickButtonByText(buttonText: String): Boolean {
        Log.d(TAG, "Looking for button with text: $buttonText")
        notifyUser("Looking for button with text: $buttonText", false)
        
        // Refresh the root node to ensure we have the latest UI state
        refreshRootNode()
        
        // Get the root node with retry logic
        var rootNode: AccessibilityNodeInfo? = null
        var retryCount = 0
        while (rootNode == null && retryCount < 3) {
            rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.e(TAG, "Root node is null, retrying... (attempt ${retryCount + 1})")
                try {
                    Thread.sleep(300) // Short delay before retry
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Sleep interrupted: ${e.message}")
                }
                retryCount++
            }
        }
        
        if (rootNode == null) {
            Log.e(TAG, "Root node is still null after retries, cannot find button")
            notifyUser("Cannot find button: No active window after multiple attempts", true)
            return false
        }
        
        try {
            // Find the node with the specified text
            val node = findNodeByText(rootNode, buttonText)
            
            if (node != null) {
                Log.d(TAG, "Found node with text: $buttonText")
                notifyUser("Found button: $buttonText", false)
                
                // Check if the node is clickable
                if (node.isClickable) {
                    Log.d(TAG, "Node is clickable, performing click")
                    notifyUser("Button is clickable, performing click", false)
                    
                    // Add a small delay before clicking
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Sleep interrupted: ${e.message}")
                    }
                    
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $result")
                    
                    if (!result) {
                        notifyUser("Click action failed on button: $buttonText", true)
                        
                        // Try alternative click action
                        Log.d(TAG, "Trying alternative click action")
                        val altResult = node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                        Log.d(TAG, "Alternative click (select) result: $altResult")
                        
                        if (altResult) {
                            notifyUser("Alternative click action succeeded on button: $buttonText", false)
                            return true
                        }
                    } else {
                        notifyUser("Click action succeeded on button: $buttonText", false)
                    }
                    return result
                } else {
                    Log.d(TAG, "Node is not clickable, trying to find a clickable parent")
                    notifyUser("Button is not clickable, trying to find a clickable parent", false)
                    
                    // Try to find a clickable parent
                    var parent = node.parent
                    var parentFound = false
                    
                    while (parent != null) {
                        if (parent.isClickable) {
                            parentFound = true
                            Log.d(TAG, "Found clickable parent, performing click")
                            notifyUser("Found clickable parent, performing click", false)
                            
                            // Add a small delay before clicking
                            try {
                                Thread.sleep(200)
                            } catch (e: InterruptedException) {
                                Log.e(TAG, "Sleep interrupted: ${e.message}")
                            }
                            
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Click result: $result")
                            
                            if (!result) {
                                notifyUser("Click action failed on parent of button: $buttonText", true)
                                
                                // Try alternative click action
                                Log.d(TAG, "Trying alternative click action on parent")
                                val altResult = parent.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                                Log.d(TAG, "Alternative click (select) result on parent: $altResult")
                                
                                if (altResult) {
                                    notifyUser("Alternative click action succeeded on parent of button: $buttonText", false)
                                    return true
                                }
                            } else {
                                notifyUser("Click action succeeded on parent of button: $buttonText", false)
                            }
                            
                            // Clean up parent node
                            val tempParent = parent
                            parent = null
                            try {
                                tempParent.recycle()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error recycling parent node: ${e.message}")
                            }
                            
                            return result
                        }
                        
                        val tempParent = parent
                        parent = parent.parent
                        
                        // Recycle the previous parent if we're moving up the tree
                        if (parent != null) {
                            try {
                                tempParent.recycle()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error recycling intermediate parent node: ${e.message}")
                            }
                        }
                    }
                    
                    // If no clickable parent found, try to click at the node's center coordinates
                    if (!parentFound) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        val centerX = rect.exactCenterX()
                        val centerY = rect.exactCenterY()
                        Log.d(TAG, "No clickable parent found, tapping at center coordinates: $centerX, $centerY")
                        notifyUser("No clickable parent found, tapping at center coordinates: $centerX, $centerY", false)
                        
                        // Use tapAtCoordinates to click at the center of the node
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            return tapAtCoordinates(centerX, centerY)
                        } else {
                            notifyUser("Tap at coordinates requires Android 7.0+", true)
                            return false
                        }
                    }
                }
            } else {
                Log.e(TAG, "No node found with text: $buttonText")
                notifyUser("No button found with text: $buttonText", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding and clicking button: ${e.message}", e)
            notifyUser("Error finding button: ${e.message}", true)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling root node: ${e.message}")
            }
        }
        
        return false
    }
    
    // Find and click a button by its content description
    fun findAndClickButtonByContentDescription(contentDesc: String): Boolean {
        Log.d(TAG, "Looking for button with content description: $contentDesc")
        notifyUser("Looking for button with content description: $contentDesc", false)
        
        // Refresh the root node to ensure we have the latest UI state
        refreshRootNode()
        
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by content description")
            notifyUser("Cannot find button: No active window", true)
            return false
        }
        
        try {
            // Find the node with the specified content description
            val node = findNodeByContentDescription(rootNode, contentDesc)
            
            if (node != null) {
                Log.d(TAG, "Found node with content description: $contentDesc")
                notifyUser("Found button with content description: $contentDesc", false)
                
                // Check if the node is clickable
                if (node.isClickable) {
                    Log.d(TAG, "Node is clickable, performing click")
                    notifyUser("Button is clickable, performing click", false)
                    
                    // Add a small delay before clicking
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Sleep interrupted: ${e.message}")
                    }
                    
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $result")
                    
                    if (!result) {
                        notifyUser("Click action failed on button with content description: $contentDesc", true)
                    } else {
                        notifyUser("Click action succeeded on button with content description: $contentDesc", false)
                    }
                    return result
                } else {
                    // Similar logic as findAndClickButtonByText for non-clickable nodes
                    Log.d(TAG, "Node is not clickable, trying to find a clickable parent")
                    notifyUser("Button is not clickable, trying to find a clickable parent", false)
                    
                    // Try to find a clickable parent
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            Log.d(TAG, "Found clickable parent, performing click")
                            notifyUser("Found clickable parent, performing click", false)
                            
                            // Add a small delay before clicking
                            try {
                                Thread.sleep(200)
                            } catch (e: InterruptedException) {
                                Log.e(TAG, "Sleep interrupted: ${e.message}")
                            }
                            
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Click result: $result")
                            
                            if (!result) {
                                notifyUser("Click action failed on parent of button with content description: $contentDesc", true)
                            } else {
                                notifyUser("Click action succeeded on parent of button with content description: $contentDesc", false)
                            }
                            return result
                        }
                        parent = parent.parent
                    }
                    
                    // If no clickable parent found, try to click at the node's center coordinates
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val centerX = rect.exactCenterX()
                    val centerY = rect.exactCenterY()
                    Log.d(TAG, "No clickable parent found, tapping at center coordinates: $centerX, $centerY")
                    notifyUser("No clickable parent found, tapping at center coordinates: $centerX, $centerY", false)
                    
                    // Use tapAtCoordinates to click at the center of the node
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        return tapAtCoordinates(centerX, centerY)
                    } else {
                        notifyUser("Tap at coordinates requires Android 7.0+", true)
                        return false
                    }
                }
            } else {
                Log.e(TAG, "No node found with content description: $contentDesc")
                notifyUser("No button found with content description: $contentDesc", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding and clicking button by content description: ${e.message}", e)
            notifyUser("Error finding button by content description: ${e.message}", true)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling root node: ${e.message}")
            }
        }
        
        return false
    }
    
    // Find and click a button by its class name
    fun findAndClickButtonByClassName(className: String): Boolean {
        Log.d(TAG, "Looking for button with class name containing: $className")
        notifyUser("Looking for button with class name containing: $className", false)
        
        // Refresh the root node to ensure we have the latest UI state
        refreshRootNode()
        
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by class name")
            notifyUser("Cannot find button: No active window", true)
            return false
        }
        
        try {
            // Find the node with the specified class name
            val node = findNodeByClassName(rootNode, className)
            
            if (node != null) {
                Log.d(TAG, "Found node with class name containing: $className")
                notifyUser("Found button with class name containing: $className", false)
                
                // Similar logic as other find methods
                if (node.isClickable) {
                    Log.d(TAG, "Node is clickable, performing click")
                    notifyUser("Button is clickable, performing click", false)
                    
                    // Add a small delay before clicking
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Sleep interrupted: ${e.message}")
                    }
                    
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $result")
                    
                    if (!result) {
                        notifyUser("Click action failed on button with class name containing: $className", true)
                    } else {
                        notifyUser("Click action succeeded on button with class name containing: $className", false)
                    }
                    return result
                } else {
                    // Try to find a clickable parent
                    // Similar logic as other find methods
                    Log.d(TAG, "Node is not clickable, trying to find a clickable parent")
                    notifyUser("Button is not clickable, trying to find a clickable parent", false)
                    
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            Log.d(TAG, "Found clickable parent, performing click")
                            notifyUser("Found clickable parent, performing click", false)
                            
                            // Add a small delay before clicking
                            try {
                                Thread.sleep(200)
                            } catch (e: InterruptedException) {
                                Log.e(TAG, "Sleep interrupted: ${e.message}")
                            }
                            
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Click result: $result")
                            
                            if (!result) {
                                notifyUser("Click action failed on parent of button with class name containing: $className", true)
                            } else {
                                notifyUser("Click action succeeded on parent of button with class name containing: $className", false)
                            }
                            return result
                        }
                        parent = parent.parent
                    }
                    
                    // If no clickable parent found, try to click at the node's center coordinates
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val centerX = rect.exactCenterX()
                    val centerY = rect.exactCenterY()
                    Log.d(TAG, "No clickable parent found, tapping at center coordinates: $centerX, $centerY")
                    notifyUser("No clickable parent found, tapping at center coordinates: $centerX, $centerY", false)
                    
                    // Use tapAtCoordinates to click at the center of the node
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        return tapAtCoordinates(centerX, centerY)
                    } else {
                        notifyUser("Tap at coordinates requires Android 7.0+", true)
                        return false
                    }
                }
            } else {
                Log.e(TAG, "No node found with class name containing: $className")
                notifyUser("No button found with class name containing: $className", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding and clicking button by class name: ${e.message}", e)
            notifyUser("Error finding button by class name: ${e.message}", true)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling root node: ${e.message}")
            }
        }
        
        return false
    }
    
    // Find a node by its text
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Check if this node has the text we're looking for
        if (node.text != null && node.text.toString().contains(text, ignoreCase = true)) {
            return node
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            if (result != null) {
                return result
            }
            // Explicitly recycle child nodes we're done with
            try {
                child.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling child node: ${e.message}")
            }
        }
        
        return null
    }
    
    // Find a node by its content description
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, contentDesc: String): AccessibilityNodeInfo? {
        // Check if this node has the content description we're looking for
        if (node.contentDescription != null && 
            node.contentDescription.toString().contains(contentDesc, ignoreCase = true)) {
            return node
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, contentDesc)
            if (result != null) {
                return result
            }
            // Explicitly recycle child nodes we're done with
            try {
                child.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling child node: ${e.message}")
            }
        }
        
        return null
    }
    
    // Find a node by its class name
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        // Check if this node's class name contains the text we're looking for
        if (node.className != null && 
            node.className.toString().contains(className, ignoreCase = true)) {
            return node
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByClassName(child, className)
            if (result != null) {
                return result
            }
            // Explicitly recycle child nodes we're done with
            try {
                child.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling child node: ${e.message}")
            }
        }
        
        return null
    }
    
    // Tap at specific coordinates
    @RequiresApi(Build.VERSION_CODES.N)
    fun tapAtCoordinates(x: Float, y: Float): Boolean {
        Log.d(TAG, "Tapping at coordinates: $x, $y")
        notifyUser("Tapping at coordinates: $x, $y", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Tap at coordinates requires API level 24 or higher")
            notifyUser("Tap requires Android 7.0+", true)
            return false
        }
        
        try {
            // Create a path for the tap gesture
            val path = Path()
            path.moveTo(x, y)
            
            // Create a stroke description with longer duration for better recognition
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, 300)
            
            // Build the gesture with the stroke
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(strokeDescription)
            val gesture = gestureBuilder.build()
            
            // Create a callback to handle the result
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Tap gesture completed successfully at coordinates: $x, $y")
                    
                    // Show a toast to indicate the tap was performed
                    notifyUser("Tap performed successfully at: $x, $y", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Tap gesture cancelled at coordinates: $x, $y")
                    
                    // Show a toast to indicate the tap was cancelled
                    notifyUser("Tap cancelled at: $x, $y", true)
                }
            }
            
            // Dispatch the gesture with the callback
            val dispatchResult = dispatchGesture(gesture, callback, null)
            Log.d(TAG, "Gesture dispatch result: $dispatchResult")
            return dispatchResult
        } catch (e: Exception) {
            Log.e(TAG, "Error performing tap at coordinates: $x, $y", e)
            notifyUser("Error performing tap: ${e.message}", true)
            return false
        }
    }
    
    // Tap at specific coordinates with longer duration
    @RequiresApi(Build.VERSION_CODES.N)
    fun tapAtCoordinatesWithLongerDuration(x: Float, y: Float): Boolean {
        Log.d(TAG, "Tapping at coordinates with longer duration: $x, $y")
        notifyUser("Tapping at coordinates with longer duration: $x, $y", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Tap at coordinates requires API level 24 or higher")
            notifyUser("Tap requires Android 7.0+", true)
            return false
        }
        
        try {
            // Create a path for the tap gesture
            val path = Path()
            path.moveTo(x, y)
            
            // Create a stroke description with much longer duration for better recognition
            // 800ms duration should be recognized by most apps
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, 800)
            
            // Build the gesture with the stroke
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(strokeDescription)
            val gesture = gestureBuilder.build()
            
            // Create a callback to handle the result
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Long-duration tap gesture completed successfully at coordinates: $x, $y")
                    
                    // Show a toast to indicate the tap was performed
                    notifyUser("Long-duration tap performed successfully at: $x, $y", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Long-duration tap gesture cancelled at coordinates: $x, $y")
                    
                    // Show a toast to indicate the tap was cancelled
                    notifyUser("Long-duration tap cancelled at: $x, $y", true)
                }
            }
            
            // Dispatch the gesture with the callback
            val dispatchResult = dispatchGesture(gesture, callback, null)
            Log.d(TAG, "Long-duration gesture dispatch result: $dispatchResult")
            return dispatchResult
        } catch (e: Exception) {
            Log.e(TAG, "Error performing long-duration tap at coordinates: $x, $y", e)
            notifyUser("Error performing long-duration tap: ${e.message}", true)
            return false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
        isServiceConnected = false
        instance = null
        notifyUser("Accessibility service destroyed", true)
    }
}
