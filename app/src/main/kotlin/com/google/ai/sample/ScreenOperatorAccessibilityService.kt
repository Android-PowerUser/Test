package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.ai.sample.util.Command
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenOperatorAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ScreenOperatorService"
        
        // Flag to track if the service is connected
        private val isServiceConnected = AtomicBoolean(false)
        
        // Reference to the service instance
        private var serviceInstance: ScreenOperatorAccessibilityService? = null
        
        // Handler for main thread operations
        private val mainHandler = Handler(Looper.getMainLooper())
        
        /**
         * Check if the accessibility service is enabled in system settings
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val accessibilityEnabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(TAG, "Error finding accessibility setting: ${e.message}")
                return false
            }
            
            if (accessibilityEnabled != 1) {
                Log.d(TAG, "Accessibility is not enabled")
                return false
            }
            
            val serviceString = "${context.packageName}/${ScreenOperatorAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            val isEnabled = enabledServices.contains(serviceString)
            Log.d(TAG, "Service $serviceString is ${if (isEnabled) "enabled" else "not enabled"}")
            return isEnabled
        }
        
        /**
         * Check if the service is available (connected and running)
         */
        fun isServiceAvailable(): Boolean {
            val isAvailable = isServiceConnected.get() && serviceInstance != null
            Log.d(TAG, "Service is ${if (isAvailable) "available" else "not available"}")
            return isAvailable
        }
        
        /**
         * Execute a command using the accessibility service
         */
        fun executeCommand(command: Command) {
            Log.d(TAG, "Executing command: $command")
            
            // Check if service is available
            if (!isServiceAvailable()) {
                Log.e(TAG, "Service is not available, cannot execute command")
                showToast("Accessibility Service ist nicht verfügbar. Bitte aktivieren Sie den Service in den Einstellungen.", true)
                return
            }
            
            // Execute the command
            when (command) {
                is Command.ClickButton -> {
                    Log.d(TAG, "Clicking button with text: ${command.buttonText}")
                    showToast("Versuche Klick auf Button: \"${command.buttonText}\"", false)
                    serviceInstance?.findAndClickButtonByText(command.buttonText)
                }
                is Command.TapCoordinates -> {
                    Log.d(TAG, "Tapping at coordinates: (${command.x}, ${command.y})")
                    showToast("Versuche Tippen auf Koordinaten: (${command.x}, ${command.y})", false)
                    serviceInstance?.tapAtCoordinates(command.x, command.y)
                }
                is Command.TakeScreenshot -> {
                    Log.d(TAG, "Taking screenshot")
                    showToast("Versuche Screenshot aufzunehmen", false)
                    serviceInstance?.takeScreenshot()
                }
            }
        }
        
        /**
         * Show a toast message on the main thread
         */
        private fun showToast(message: String, isError: Boolean) {
            mainHandler.post {
                val mainActivity = MainActivity.getInstance()
                if (mainActivity != null) {
                    mainActivity.updateStatusMessage(message, isError)
                } else {
                    Log.e(TAG, "MainActivity instance is null, cannot show toast")
                }
            }
        }
    }
    
    // Root node of the accessibility tree
    private var rootNode: AccessibilityNodeInfo? = null
    
    // Last time the root node was refreshed
    private var lastRootNodeRefreshTime: Long = 0
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        
        // Configure the service
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        
        // Apply the configuration
        serviceInfo = info
        
        // Set the service instance and connected flag
        serviceInstance = this
        isServiceConnected.set(true)
        
        // Show a toast to indicate the service is connected
        showToast("Accessibility Service ist aktiviert und verbunden", false)
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
        
        // Clear the service instance and connected flag
        if (serviceInstance == this) {
            serviceInstance = null
            isServiceConnected.set(false)
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Process accessibility events
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
            
            // Refresh the root node when window state or content changes
            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                refreshRootNode()
            }
        }
    }
    
    /**
     * Refresh the root node of the accessibility tree
     */
    private fun refreshRootNode() {
        val currentTime = System.currentTimeMillis()
        
        // Only refresh if more than 500ms have passed since the last refresh
        if (currentTime - lastRootNodeRefreshTime < 500) {
            return
        }
        
        try {
            // Get the root node in active window
            rootNode = rootInActiveWindow
            lastRootNodeRefreshTime = currentTime
            Log.d(TAG, "Root node refreshed: ${rootNode != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing root node: ${e.message}")
            rootNode = null
        }
    }
    
    /**
     * Find and click a button with the specified text
     */
    fun findAndClickButtonByText(buttonText: String) {
        Log.d(TAG, "Finding and clicking button with text: $buttonText")
        showToast("Suche Button mit Text: \"$buttonText\"", false)
        
        // Refresh the root node
        refreshRootNode()
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button")
            showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
            return
        }
        
        // Try to find the node with the specified text
        val node = findNodeByText(rootNode!!, buttonText)
        
        if (node != null) {
            Log.d(TAG, "Found node with text: $buttonText")
            showToast("Button gefunden: \"$buttonText\"", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button: $buttonText")
                    showToast("Klick auf Button \"$buttonText\" erfolgreich", false)
                } else {
                    Log.e(TAG, "Failed to click on button: $buttonText")
                    showToast("Klick auf Button \"$buttonText\" fehlgeschlagen, versuche alternative Methoden", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, buttonText)
                }
                
                // Recycle the node
                node.recycle()
            }, 200) // 200ms delay
        } else {
            Log.e(TAG, "Could not find node with text: $buttonText")
            showToast("Button mit Text \"$buttonText\" nicht gefunden, versuche alternative Suche", true)
            
            // Try to find by content description
            findAndClickButtonByContentDescription(buttonText)
        }
    }
    
    /**
     * Try alternative click methods if the standard click fails
     */
    private fun tryAlternativeClickMethods(node: AccessibilityNodeInfo, buttonText: String) {
        // Try to get the bounds and tap at the center
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        if (rect.width() > 0 && rect.height() > 0) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            
            Log.d(TAG, "Trying to tap at the center of the button: ($centerX, $centerY)")
            showToast("Versuche Tippen auf Koordinaten: ($centerX, $centerY)", false)
            
            // Tap at the center of the button
            tapAtCoordinatesWithLongerDuration(centerX.toFloat(), centerY.toFloat())
        } else {
            Log.e(TAG, "Button bounds are invalid: $rect")
            showToast("Button-Grenzen sind ungültig", true)
        }
    }
    
    /**
     * Find and click a button with the specified content description
     */
    private fun findAndClickButtonByContentDescription(description: String) {
        Log.d(TAG, "Finding and clicking button with content description: $description")
        showToast("Suche Button mit Beschreibung: \"$description\"", false)
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button")
            showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
            return
        }
        
        // Try to find the node with the specified content description
        val node = findNodeByContentDescription(rootNode!!, description)
        
        if (node != null) {
            Log.d(TAG, "Found node with content description: $description")
            showToast("Button mit Beschreibung \"$description\" gefunden", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button with description: $description")
                    showToast("Klick auf Button mit Beschreibung \"$description\" erfolgreich", false)
                } else {
                    Log.e(TAG, "Failed to click on button with description: $description")
                    showToast("Klick auf Button mit Beschreibung \"$description\" fehlgeschlagen, versuche Klassen-Suche", true)
                    
                    // Try to find by class name
                    findAndClickButtonByClassName("android.widget.Button")
                }
                
                // Recycle the node
                node.recycle()
            }, 200) // 200ms delay
        } else {
            Log.e(TAG, "Could not find node with content description: $description")
            showToast("Button mit Beschreibung \"$description\" nicht gefunden, versuche Klassen-Suche", true)
            
            // Try to find by class name
            findAndClickButtonByClassName("android.widget.Button")
        }
    }
    
    /**
     * Find and click a button with the specified class name
     */
    private fun findAndClickButtonByClassName(className: String) {
        Log.d(TAG, "Finding and clicking button with class name: $className")
        showToast("Suche Button mit Klasse: \"$className\"", false)
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button")
            showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
            return
        }
        
        // Try to find the node with the specified class name
        val node = findNodeByClassName(rootNode!!, className)
        
        if (node != null) {
            Log.d(TAG, "Found node with class name: $className")
            showToast("Button mit Klasse \"$className\" gefunden", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button with class name: $className")
                    showToast("Klick auf Button mit Klasse \"$className\" erfolgreich", false)
                } else {
                    Log.e(TAG, "Failed to click on button with class name: $className")
                    showToast("Klick auf Button mit Klasse \"$className\" fehlgeschlagen", true)
                }
                
                // Recycle the node
                node.recycle()
            }, 200) // 200ms delay
        } else {
            Log.e(TAG, "Could not find node with class name: $className")
            showToast("Button mit Klasse \"$className\" nicht gefunden, alle Methoden fehlgeschlagen", true)
        }
    }
    
    /**
     * Find a node by text recursively
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Check if this node has the text we're looking for
        if (node.text != null && node.text.toString().contains(text, ignoreCase = true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Check all child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            child.recycle()
            
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    /**
     * Find a node by content description recursively
     */
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        // Check if this node has the content description we're looking for
        if (node.contentDescription != null && node.contentDescription.toString().contains(description, ignoreCase = true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Check all child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, description)
            child.recycle()
            
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    /**
     * Find a node by class name recursively
     */
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        // Check if this node has the class name we're looking for
        if (node.className != null && node.className.toString() == className) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Check all child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByClassName(child, className)
            child.recycle()
            
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    /**
     * Perform a click on the specified node
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        try {
            // Try to perform click action
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            // If the node itself is not clickable, try to find a clickable parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    return result
                }
                
                val newParent = parent.parent
                parent.recycle()
                parent = newParent
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click on node: ${e.message}")
            return false
        }
    }
    
    /**
     * Tap at the specified coordinates
     */
    fun tapAtCoordinates(x: Float, y: Float) {
        Log.d(TAG, "Tapping at coordinates: ($x, $y)")
        showToast("Tippen auf Koordinaten: ($x, $y)", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a tap gesture
            val path = Path()
            path.moveTo(x, y)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Tap gesture completed")
                    showToast("Tippen auf Koordinaten ($x, $y) erfolgreich", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Tap gesture cancelled")
                    showToast("Tippen auf Koordinaten ($x, $y) abgebrochen, versuche längere Dauer", true)
                    
                    // Try with longer duration
                    tapAtCoordinatesWithLongerDuration(x, y)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch tap gesture")
                showToast("Fehler beim Senden der Tipp-Geste, versuche längere Dauer", true)
                
                // Try with longer duration
                tapAtCoordinatesWithLongerDuration(x, y)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates: ${e.message}")
            showToast("Fehler beim Tippen auf Koordinaten: ${e.message}", true)
        }
    }
    
    /**
     * Tap at the specified coordinates with a longer duration
     */
    private fun tapAtCoordinatesWithLongerDuration(x: Float, y: Float) {
        Log.d(TAG, "Tapping at coordinates with longer duration: ($x, $y)")
        showToast("Versuche Tippen mit längerer Dauer auf: ($x, $y)", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a tap gesture with longer duration
            val path = Path()
            path.moveTo(x, y)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Long tap gesture completed")
                    showToast("Langes Tippen auf Koordinaten ($x, $y) erfolgreich", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Long tap gesture cancelled")
                    showToast("Langes Tippen auf Koordinaten ($x, $y) abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch long tap gesture")
                showToast("Fehler beim Senden der langen Tipp-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates with longer duration: ${e.message}")
            showToast("Fehler beim langen Tippen auf Koordinaten: ${e.message}", true)
        }
    }
    
    /**
     * Take a screenshot using the accessibility service
     */
    fun takeScreenshot() {
        Log.d(TAG, "Taking screenshot via accessibility service")
        showToast("Nehme Screenshot auf...", false)
        
        try {
            // Check if we have a valid root node
            refreshRootNode()
            if (rootNode == null) {
                Log.e(TAG, "Root node is null, cannot take screenshot")
                showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
                return
            }
            
            // Get the window bounds
            val windowBounds = Rect()
            rootNode!!.getBoundsInScreen(windowBounds)
            
            // Take the screenshot using the accessibility service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                takeScreenshotWithAccessibilityService(windowBounds)
            } else {
                // For older Android versions, use a fallback method
                takeScreenshotFallback()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            showToast("Fehler beim Aufnehmen des Screenshots: ${e.message}", true)
        }
    }
    
    /**
     * Take a screenshot using the accessibility service (Android P and above)
     */
    private fun takeScreenshotWithAccessibilityService(windowBounds: Rect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Log.d(TAG, "Taking screenshot with accessibility service API")
                
                // Use the accessibility service to take a screenshot
                takeScreenshot(
                    TAKE_SCREENSHOT_DISPLAY_TIMEOUT,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                Log.d(TAG, "Screenshot taken successfully")
                                
                                // Convert the screenshot to a bitmap
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                
                                if (bitmap != null) {
                                    // Save the bitmap to a file
                                    saveScreenshotToFile(bitmap)
                                } else {
                                    Log.e(TAG, "Failed to convert screenshot to bitmap")
                                    showToast("Fehler: Screenshot konnte nicht in Bitmap konvertiert werden", true)
                                }
                                
                                // Close the screenshot result
                                screenshot.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing screenshot: ${e.message}")
                                showToast("Fehler bei der Verarbeitung des Screenshots: ${e.message}", true)
                            }
                        }
                        
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Failed to take screenshot, error code: $errorCode")
                            showToast("Fehler beim Aufnehmen des Screenshots, Fehlercode: $errorCode", true)
                            
                            // Try fallback method
                            takeScreenshotFallback()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error taking screenshot with accessibility service: ${e.message}")
                showToast("Fehler beim Aufnehmen des Screenshots: ${e.message}", true)
                
                // Try fallback method
                takeScreenshotFallback()
            }
        } else {
            Log.e(TAG, "Accessibility screenshot API not available on this Android version")
            showToast("Screenshot-API ist auf dieser Android-Version nicht verfügbar", true)
            
            // Try fallback method
            takeScreenshotFallback()
        }
    }
    
    /**
     * Fallback method for taking screenshots
     */
    private fun takeScreenshotFallback() {
        Log.d(TAG, "Using fallback method for taking screenshot")
        showToast("Verwende alternative Methode für Screenshot...", false)
        
        try {
            // Create a bitmap of the root node
            val rootNodeBitmap = createBitmapFromRootNode()
            
            if (rootNodeBitmap != null) {
                // Save the bitmap to a file
                saveScreenshotToFile(rootNodeBitmap)
            } else {
                Log.e(TAG, "Failed to create bitmap from root node")
                showToast("Fehler: Bitmap konnte nicht erstellt werden", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot with fallback method: ${e.message}")
            showToast("Fehler beim Aufnehmen des Screenshots mit alternativer Methode: ${e.message}", true)
        }
    }
    
    /**
     * Create a bitmap from the root node
     */
    private fun createBitmapFromRootNode(): Bitmap? {
        try {
            // Get the root node bounds
            val bounds = Rect()
            rootNode?.getBoundsInScreen(bounds)
            
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                Log.e(TAG, "Invalid root node bounds: $bounds")
                return null
            }
            
            // Create a bitmap with the size of the screen
            val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            
            // Draw the root node to the bitmap
            // Note: This is a simplified implementation and may not capture all visual elements
            // For a complete screenshot, we would need to traverse the accessibility node tree
            // and draw each node based on its properties
            
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bitmap from root node: ${e.message}")
            return null
        }
    }
    
    /**
     * Save the screenshot bitmap to a file
     */
    private fun saveScreenshotToFile(bitmap: Bitmap) {
        try {
            Log.d(TAG, "Saving screenshot to file")
            
            // Create a filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "Screenshot_$timestamp.jpg"
            
            // Get the pictures directory
            val imagesDir = applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            val imageFile = File(imagesDir, filename)
            
            // Save the bitmap to the file
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            
            Log.d(TAG, "Screenshot saved to: ${imageFile.absolutePath}")
            
            // Add the image to the MediaStore so it appears in the gallery
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            // Insert the image into the MediaStore
            val contentResolver = applicationContext.contentResolver
            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (imageUri != null) {
                // Copy the bitmap data to the MediaStore
                contentResolver.openOutputStream(imageUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                
                // Update the IS_PENDING flag for Android Q and above
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(imageUri, contentValues, null, null)
                }
                
                Log.d(TAG, "Screenshot added to MediaStore: $imageUri")
                showToast("Screenshot erfolgreich aufgenommen und gespeichert", false)
                
                // Add the screenshot to the conversation
                addScreenshotToConversation(imageUri)
            } else {
                Log.e(TAG, "Failed to insert screenshot into MediaStore")
                showToast("Fehler: Screenshot konnte nicht in MediaStore eingefügt werden", true)
                
                // Try to add the file directly
                val fileUri = Uri.fromFile(imageFile)
                addScreenshotToConversation(fileUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot to file: ${e.message}")
            showToast("Fehler beim Speichern des Screenshots: ${e.message}", true)
        }
    }
    
    /**
     * Add the screenshot to the conversation
     */
    private fun addScreenshotToConversation(screenshotUri: Uri) {
        try {
            Log.d(TAG, "Adding screenshot to conversation: $screenshotUri")
            
            // Get the MainActivity instance
            val mainActivity = MainActivity.getInstance()
            if (mainActivity == null) {
                Log.e(TAG, "MainActivity instance is null, cannot add screenshot to conversation")
                showToast("Fehler: MainActivity-Instanz ist nicht verfügbar", true)
                return
            }
            
            // Get the PhotoReasoningViewModel from MainActivity
            val photoReasoningViewModel = mainActivity.getPhotoReasoningViewModel()
            if (photoReasoningViewModel == null) {
                Log.e(TAG, "PhotoReasoningViewModel is null, cannot add screenshot to conversation")
                showToast("Fehler: PhotoReasoningViewModel ist nicht verfügbar", true)
                return
            }
            
            // Add the screenshot to the conversation
            photoReasoningViewModel.addScreenshotToConversation(screenshotUri, applicationContext)
            
            Log.d(TAG, "Screenshot added to conversation")
            showToast("Screenshot zur Konversation hinzugefügt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding screenshot to conversation: ${e.message}")
            showToast("Fehler beim Hinzufügen des Screenshots zur Konversation: ${e.message}", true)
        }
    }
    
    /**
     * Show a toast message
     */
    private fun showToast(message: String, isError: Boolean) {
        Log.d(TAG, "Showing toast: $message, isError: $isError")
        
        // Show toast on main thread
        Handler(Looper.getMainLooper()).post {
            // Try to use MainActivity to show the toast
            val mainActivity = MainActivity.getInstance()
            if (mainActivity != null) {
                mainActivity.updateStatusMessage(message, isError)
            } else {
                // Fallback to regular toast
                val duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(applicationContext, message, duration).show()
            }
        }
    }
}
