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
        
        // Method to check if service is available
        fun isServiceAvailable(): Boolean {
            return instance != null && isServiceConnected
        }
        
        // Method to trigger screenshot from outside the service
        fun takeScreenshot(callback: (Uri?) -> Unit) {
            Log.d(TAG, "takeScreenshot called")
            shouldTakeScreenshot = true
            onScreenshotTaken = callback
            
            // Record the timestamp when screenshot was requested
            screenshotTimestamp = System.currentTimeMillis()
            
            // If we have an instance, trigger the screenshot
            if (instance != null) {
                Log.d(TAG, "Instance available, triggering screenshot")
                instance?.performScreenshot()
            } else {
                Log.e(TAG, "No service instance available. Make sure the accessibility service is enabled in settings.")
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
        
        // Execute a command using the accessibility service with retry logic
        fun executeCommand(command: Command) {
            Log.d(TAG, "Command received: $command")
            
            // Use a coroutine to handle potential waiting
            CoroutineScope(Dispatchers.Main).launch {
                // Try to wait for service to connect if it's not ready
                var retryCount = 0
                while (!isServiceConnected && retryCount < 5) {
                    Log.d(TAG, "Service not connected, waiting... (attempt ${retryCount + 1})")
                    delay(500)
                    retryCount++
                }
                
                // Get the current instance
                val currentInstance = instance
                if (currentInstance == null) {
                    Log.e(TAG, "No service instance available after waiting. Make sure the accessibility service is enabled in settings.")
                    return@launch
                }
                
                try {
                    when (command) {
                        is Command.ClickButton -> {
                            Log.d(TAG, "Executing clickOnButton command: ${command.buttonText}")
                            // Direct call to the method on the instance
                            val result = currentInstance.findAndClickButtonByText(command.buttonText)
                            Log.d(TAG, "Click result: $result")
                            
                            // Show a Toast to provide feedback
                            currentInstance.notifyUser("Button click attempted: ${command.buttonText}", !result)
                        }
                        is Command.TapCoordinates -> {
                            Log.d(TAG, "Executing tapAtCoordinates command: ${command.x}, ${command.y}")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                // Direct call to the method on the instance
                                val result = currentInstance.tapAtCoordinates(command.x, command.y)
                                Log.d(TAG, "Tap result: $result")
                                
                                // Show a Toast to provide feedback
                                currentInstance.notifyUser("Tap attempted at: ${command.x}, ${command.y}", !result)
                            } else {
                                Log.e(TAG, "Tap at coordinates requires API level 24 or higher")
                                currentInstance.notifyUser("Tap requires Android 7.0+", true)
                            }
                        }
                        is Command.TakeScreenshot -> {
                            Log.d(TAG, "Executing takeScreenshot command")
                            // Take a screenshot and add it to the current conversation
                            takeScreenshotAndAddToConversation()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing command: ${e.message}", e)
                    currentInstance.notifyUser("Error: ${e.message}", true)
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
    private fun notifyUser(message: String, isError: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            applicationContext?.let { context ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                
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
            return mainActivity.areAllPermissionsGranted()
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events
        // Not needed for basic click functionality
    }

    override fun onInterrupt() {
        // Handle interruption of the accessibility service
        Log.d(TAG, "Accessibility service interrupted")
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
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button")
            notifyUser("Cannot find button: No active window", true)
            return false
        }
        
        try {
            // Find the node with the specified text
            val node = findNodeByText(rootNode, buttonText)
            
            if (node != null) {
                Log.d(TAG, "Found node with text: $buttonText")
                
                // Check if the node is clickable
                if (node.isClickable) {
                    Log.d(TAG, "Node is clickable, performing click")
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $result")
                    if (!result) {
                        notifyUser("Click action failed on button: $buttonText", true)
                    }
                    return result
                } else {
                    Log.d(TAG, "Node is not clickable, trying to find a clickable parent")
                    
                    // Try to find a clickable parent
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            Log.d(TAG, "Found clickable parent, performing click")
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Click result: $result")
                            if (!result) {
                                notifyUser("Click action failed on parent of button: $buttonText", true)
                            }
                            return result
                        }
                        val temp = parent
                        parent = parent.parent
                        // No need to explicitly recycle in modern Android
                    }
                    
                    // If no clickable parent found, try to click at the node's center coordinates
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val centerX = rect.exactCenterX()
                    val centerY = rect.exactCenterY()
                    Log.d(TAG, "No clickable parent found, tapping at center coordinates: $centerX, $centerY")
                    
                    // Use tapAtCoordinates to click at the center of the node
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        return tapAtCoordinates(centerX, centerY)
                    } else {
                        notifyUser("Tap at coordinates requires Android 7.0+", true)
                        return false
                    }
                }
            } else {
                Log.e(TAG, "No node found with text: $buttonText")
                notifyUser("No button found with text: $buttonText", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding and clicking button: ${e.message}", e)
            notifyUser("Error finding button: ${e.message}", true)
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
            // No need to explicitly recycle in modern Android
        }
        
        return null
    }
    
    // Tap at specific coordinates
    @RequiresApi(Build.VERSION_CODES.N)
    fun tapAtCoordinates(x: Float, y: Float): Boolean {
        Log.d(TAG, "Tapping at coordinates: $x, $y")
        
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
                    notifyUser("Tap performed at: $x, $y", false)
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
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
        isServiceConnected = false
        instance = null
    }
}
