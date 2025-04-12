package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ContentUris
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
        
        // Last screenshot URI
        private var lastScreenshotUri: Uri? = null
        
        // Timestamp when screenshot was taken
        private var screenshotTimestamp: Long = 0
        
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
        
        // Execute a command using the accessibility service
        fun executeCommand(command: Command) {
            Log.d(TAG, "Command received: $command")
            
            // Wichtig: Führen Sie die Befehle auf dem Hauptthread aus
            Handler(Looper.getMainLooper()).post {
                Log.d(TAG, "Executing command on main thread: $command")
                
                // Holen Sie die aktuelle Instanz
                val currentInstance = instance
                if (currentInstance == null) {
                    Log.e(TAG, "No service instance available. Make sure the accessibility service is enabled in settings.")
                    return@post
                }
                
                try {
                    when (command) {
                        is Command.ClickButton -> {
                            Log.d(TAG, "Executing clickOnButton command: ${command.buttonText}")
                            // Direkter Aufruf der Methode auf der Instanz
                            val result = currentInstance.findAndClickButtonByText(command.buttonText)
                            Log.d(TAG, "Click result: $result")
                            
                            // Zeigen Sie ein Toast an, um Feedback zu geben
                            currentInstance.applicationContext?.let { context ->
                                Toast.makeText(context, "Button click attempted: ${command.buttonText}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is Command.TapCoordinates -> {
                            Log.d(TAG, "Executing tapAtCoordinates command: ${command.x}, ${command.y}")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                // Direkter Aufruf der Methode auf der Instanz
                                val result = currentInstance.tapAtCoordinates(command.x, command.y)
                                Log.d(TAG, "Tap result: $result")
                                
                                // Zeigen Sie ein Toast an, um Feedback zu geben
                                currentInstance.applicationContext?.let { context ->
                                    Toast.makeText(context, "Tap attempted at: ${command.x}, ${command.y}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Log.e(TAG, "Tap at coordinates requires API level 24 or higher")
                                currentInstance.applicationContext?.let { context ->
                                    Toast.makeText(context, "Tap requires Android 7.0+", Toast.LENGTH_SHORT).show()
                                }
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
                    currentInstance.applicationContext?.let { context ->
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        // Take a screenshot and add it to the current conversation
        private fun takeScreenshotAndAddToConversation() {
            // Show a toast to indicate we're taking a screenshot
            instance?.applicationContext?.let { context ->
                Toast.makeText(context, "Taking screenshot...", Toast.LENGTH_SHORT).show()
            }
            
            // Take the screenshot
            takeScreenshot { screenshotUri ->
                // This will be called after the screenshot is taken
                CoroutineScope(Dispatchers.Main).launch {
                    if (screenshotUri != null) {
                        Log.d(TAG, "Screenshot taken and will be added to conversation: $screenshotUri")
                        
                        // Show a toast to indicate the screenshot was added
                        instance?.applicationContext?.let { context ->
                            Toast.makeText(context, "Screenshot added to conversation", Toast.LENGTH_SHORT).show()
                            
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
                                        viewModel.addScreenshotToConversation(screenshotUri, context)
                                        Log.d(TAG, "Automatically sent screenshot to AI after 1 second")
                                    }, 1000) // 1 second delay
                                } else {
                                    Log.e(TAG, "PhotoReasoningViewModel is null")
                                }
                            } else {
                                Log.e(TAG, "MainActivity instance is null")
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to take screenshot or get URI")
                        
                        // Show a toast to indicate the failure
                        instance?.applicationContext?.let { context ->
                            Toast.makeText(context, "Failed to take screenshot", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
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
        
        // Notify that the service is ready
        applicationContext?.let { context ->
            Toast.makeText(context, "Screen Operator Service Connected", Toast.LENGTH_SHORT).show()
        }
        
        // Set service capabilities
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        
        Log.d(TAG, "Service capabilities set")
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
            
            // Get the latest screenshot URI
            val screenshotUri = getLatestScreenshotUri()
            
            // Execute the callback with the screenshot URI
            Log.d(TAG, "Executing screenshot callback with URI: $screenshotUri")
            onScreenshotTaken?.invoke(screenshotUri)
            onScreenshotTaken = null
            
            // Broadcast that a screenshot was taken to refresh media scanner
            screenshotUri?.let { uri ->
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = uri
                sendBroadcast(intent)
            }
        }, 500) // Reduced wait time to 500ms
    }
    
    // Find and click a button by its text
    fun findAndClickButtonByText(buttonText: String): Boolean {
        Log.d(TAG, "Looking for button with text: $buttonText")
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button")
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
                            return result
                        }
                        val temp = parent
                        parent = parent.parent
                        temp.recycle()
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
                    }
                }
            } else {
                Log.e(TAG, "No node found with text: $buttonText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding and clicking button: ${e.message}", e)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling root node: ${e.message}", e)
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
            child.recycle()
        }
        
        return null
    }
    
    // Tap at specific coordinates
    @RequiresApi(Build.VERSION_CODES.N)
    fun tapAtCoordinates(x: Float, y: Float): Boolean {
        Log.d(TAG, "Tapping at coordinates: $x, $y")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Tap at coordinates requires API level 24 or higher")
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
                    Handler(Looper.getMainLooper()).post {
                        applicationContext?.let { context ->
                            Toast.makeText(context, "Tap performed at: $x, $y", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Tap gesture cancelled at coordinates: $x, $y")
                    
                    // Show a toast to indicate the tap was cancelled
                    Handler(Looper.getMainLooper()).post {
                        applicationContext?.let { context ->
                            Toast.makeText(context, "Tap cancelled at: $x, $y", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            // Dispatch the gesture with the callback
            val dispatchResult = dispatchGesture(gesture, callback, null)
            Log.d(TAG, "Gesture dispatch result: $dispatchResult")
            return dispatchResult
        } catch (e: Exception) {
            Log.e(TAG, "Error performing tap at coordinates: $x, $y", e)
            return false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
        instance = null
    }
}
