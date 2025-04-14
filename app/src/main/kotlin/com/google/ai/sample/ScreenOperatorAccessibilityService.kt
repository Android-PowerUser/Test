package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.ai.sample.util.Command
import java.io.File
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
                is Command.PressHomeButton -> {
                    Log.d(TAG, "Pressing home button")
                    showToast("Versuche Home-Button zu drücken", false)
                    serviceInstance?.pressHomeButton()
                }
                is Command.PressBackButton -> {
                    Log.d(TAG, "Pressing back button")
                    showToast("Versuche Zurück-Button zu drücken", false)
                    serviceInstance?.pressBackButton()
                }
                is Command.ShowRecentApps -> {
                    Log.d(TAG, "Showing recent apps")
                    showToast("Versuche Übersicht der letzten Apps zu öffnen", false)
                    serviceInstance?.showRecentApps()
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
    
    // Handler for delayed operations
    private val handler = Handler(Looper.getMainLooper())
    
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
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        
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
     * Take a screenshot by simulating hardware button presses
     */
    fun takeScreenshot() {
        Log.d(TAG, "Taking screenshot by simulating hardware buttons")
        showToast("Nehme Screenshot auf durch Simulation der Hardware-Tasten...", false)
        
        try {
            // Capture screen information before taking the screenshot
            val screenInfo = captureScreenInformation()
            
            // Simulate pressing Power + Volume Down buttons to take a screenshot
            simulateScreenshotButtonCombination()
            
            // Wait a moment for the screenshot to be saved, then retrieve it
            handler.postDelayed({
                retrieveLatestScreenshot(screenInfo)
            }, 1000) // Wait 1 second for the screenshot to be saved
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            showToast("Fehler beim Aufnehmen des Screenshots: ${e.message}", true)
        }
    }
    
    /**
     * Capture information about all interactive elements on the screen
     */
    private fun captureScreenInformation(): String {
        Log.d(TAG, "Capturing screen information")
        
        // Refresh the root node to ensure we have the latest information
        refreshRootNode()
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot capture screen information")
            return "Keine Bildschirminformationen verfügbar (Root-Knoten ist null)"
        }
        
        // Build a string with information about all interactive elements
        val screenInfo = StringBuilder()
        screenInfo.append("Bildschirmelemente:\n")
        
        // Capture information about all interactive elements
        val interactiveElements = findAllInteractiveElements(rootNode!!)
        
        if (interactiveElements.isEmpty()) {
            screenInfo.append("Keine interaktiven Elemente gefunden.")
        } else {
            screenInfo.append("Gefundene interaktive Elemente (${interactiveElements.size}):\n\n")
            
            interactiveElements.forEachIndexed { index, element ->
                screenInfo.append("${index + 1}. ")
                
                // Get element ID if available
                val elementId = getNodeId(element)
                if (elementId.isNotEmpty()) {
                    screenInfo.append("ID: \"$elementId\" ")
                }
                
                // Add element text if available
                if (!element.text.isNullOrEmpty()) {
                    screenInfo.append("Text: \"${element.text}\" ")
                }
                
                // Add element content description if available
                if (!element.contentDescription.isNullOrEmpty()) {
                    screenInfo.append("Beschreibung: \"${element.contentDescription}\" ")
                }
                
                // Try to get the button name from the view hierarchy
                val buttonName = getButtonName(element)
                if (buttonName.isNotEmpty()) {
                    screenInfo.append("Name: \"$buttonName\" ")
                }
                
                // Add element class name
                screenInfo.append("Klasse: ${element.className} ")
                
                // Add element bounds
                val rect = Rect()
                element.getBoundsInScreen(rect)
                screenInfo.append("Position: (${rect.centerX()}, ${rect.centerY()}) ")
                
                // Add element clickable status
                screenInfo.append("Klickbar: ${if (element.isClickable) "Ja" else "Nein"}")
                
                screenInfo.append("\n")
                
                // Recycle the element to avoid memory leaks
                element.recycle()
            }
        }
        
        Log.d(TAG, "Screen information captured: ${screenInfo.length} characters")
        return screenInfo.toString()
    }
    
    /**
     * Get the ID of a node if available
     */
    private fun getNodeId(node: AccessibilityNodeInfo): String {
        try {
            val viewIdResourceName = node.viewIdResourceName
            if (!viewIdResourceName.isNullOrEmpty()) {
                // Extract the ID name from the resource name (package:id/name)
                val parts = viewIdResourceName.split("/")
                if (parts.size > 1) {
                    return parts[1]
                }
                return viewIdResourceName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting node ID: ${e.message}")
        }
        return ""
    }
    
    /**
     * Try to get the button name from various properties
     */
    private fun getButtonName(node: AccessibilityNodeInfo): String {
        try {
            // First check if the node has text
            if (!node.text.isNullOrEmpty()) {
                return node.text.toString()
            }
            
            // Then check content description
            if (!node.contentDescription.isNullOrEmpty()) {
                return node.contentDescription.toString()
            }
            
            // Get the node ID which might contain a name
            val nodeId = getNodeId(node)
            if (nodeId.isNotEmpty() && !nodeId.startsWith("android:")) {
                // Convert camelCase or snake_case to readable format
                val readableName = nodeId
                    .replace("_", " ")
                    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    .lowercase(Locale.getDefault())
                    .capitalize(Locale.getDefault())
                
                // If it contains common button names like "new", "add", etc., return it
                val commonButtonNames = listOf("new", "add", "edit", "delete", "save", "cancel", "ok", "send")
                for (buttonName in commonButtonNames) {
                    if (readableName.contains(buttonName, ignoreCase = true)) {
                        return readableName
                    }
                }
                
                // Return the readable ID name
                return readableName
            }
            
            // Check if it's a known button type by class name
            val className = node.className?.toString() ?: ""
            if (className.contains("Button", ignoreCase = true) || 
                className.contains("ImageButton", ignoreCase = true) ||
                className.contains("FloatingActionButton", ignoreCase = true)) {
                
                // For buttons without text, try to infer name from siblings or parent
                val parent = node.parent
                if (parent != null) {
                    // Check if parent has text that might describe this button
                    if (!parent.text.isNullOrEmpty()) {
                        val parentText = parent.text.toString()
                        parent.recycle()
                        return parentText
                    }
                    
                    // Check siblings for text that might be related
                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChild(i) ?: continue
                        if (sibling != node && !sibling.text.isNullOrEmpty()) {
                            val siblingText = sibling.text.toString()
                            sibling.recycle()
                            parent.recycle()
                            return siblingText
                        }
                        sibling.recycle()
                    }
                    
                    // Check if this is a FAB (Floating Action Button) which is often used as "New" or "Add"
                    if (className.contains("FloatingActionButton", ignoreCase = true)) {
                        parent.recycle()
                        return "New"
                    }
                    
                    parent.recycle()
                }
                
                // Special case for circular buttons at the bottom of the screen (likely navigation or action buttons)
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                
                // If it's a circular button near the bottom of the screen
                if (rect.height() == rect.width() && rect.height() < displayMetrics.densityDpi / 4 && 
                    rect.bottom > screenHeight * 0.8) {
                    
                    // Check if it's in the bottom left corner (often "New" or "Add")
                    if (rect.centerX() < displayMetrics.widthPixels * 0.3) {
                        return "New"
                    }
                }
                
                // If it's a button but we couldn't find a name, use a generic name
                return "Button"
            }
            
            // For EditText fields, try to get hint text
            if (className.contains("EditText", ignoreCase = true)) {
                // Try to get hint text using reflection (not always available)
                try {
                    val hintTextMethod = node.javaClass.getMethod("getHintText")
                    val hintText = hintTextMethod.invoke(node)?.toString()
                    if (!hintText.isNullOrEmpty()) {
                        return "Textfeld: $hintText"
                    }
                } catch (e: Exception) {
                    // Reflection failed, ignore
                }
                
                return "Textfeld"
            }
            
            // For specific view types that are commonly used as buttons
            if (className == "android.view.View" || className == "android.widget.ImageView") {
                // Check if it's in a position commonly used for specific buttons
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                val screenWidth = displayMetrics.widthPixels
                
                // Check if it's a small circular element at the bottom of the screen
                if (rect.width() == rect.height() && rect.width() < displayMetrics.densityDpi / 3 &&
                    rect.bottom > screenHeight * 0.9) {
                    
                    // Bottom left is often "New" or "Add"
                    if (rect.centerX() < screenWidth * 0.2) {
                        return "New"
                    }
                    
                    // Bottom right is often "Send" or "Next"
                    if (rect.centerX() > screenWidth * 0.8) {
                        return "Send"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting button name: ${e.message}")
        }
        return ""
    }
    
    /**
     * Find all interactive elements on the screen
     */
    private fun findAllInteractiveElements(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // Check if this node is interactive (clickable, long clickable, or focusable)
            if (node.isClickable || node.isLongClickable || node.isFocusable) {
                elements.add(AccessibilityNodeInfo.obtain(node))
            }
            
            // Check all child nodes
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                elements.addAll(findAllInteractiveElements(child))
                child.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding interactive elements: ${e.message}")
        }
        
        return elements
    }
    
    /**
     * Simulate pressing Power + Volume Down buttons to take a screenshot
     */
    private fun simulateScreenshotButtonCombination() {
        try {
            Log.d(TAG, "Simulating Power + Volume Down button combination")
            
            // First try using performGlobalAction if available (Android P and above)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                if (result) {
                    Log.d(TAG, "Successfully triggered screenshot using GLOBAL_ACTION_TAKE_SCREENSHOT")
                    showToast("Screenshot-Aktion ausgelöst", false)
                    return
                } else {
                    Log.e(TAG, "Failed to trigger screenshot using GLOBAL_ACTION_TAKE_SCREENSHOT")
                }
            }
            
            // Fallback to simulating key events
            Log.d(TAG, "Falling back to key event simulation")
            
            // Simulate Volume Down key press
            val volumeDownResult = performKeyPress(KeyEvent.KEYCODE_VOLUME_DOWN)
            
            // Simulate Power key press
            val powerResult = performKeyPress(KeyEvent.KEYCODE_POWER)
            
            if (volumeDownResult && powerResult) {
                Log.d(TAG, "Successfully simulated key presses")
                showToast("Tasten-Simulation erfolgreich", false)
            } else {
                Log.e(TAG, "Failed to simulate key presses")
                showToast("Fehler bei der Tasten-Simulation", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating button combination: ${e.message}")
            showToast("Fehler bei der Simulation der Tasten-Kombination: ${e.message}", true)
        }
    }
    
    /**
     * Perform a key press
     */
    private fun performKeyPress(keyCode: Int): Boolean {
        try {
            // Create key down event
            val downTime = System.currentTimeMillis()
            val keyDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val downResult = performGlobalAction(keyCode)
            
            // Small delay between down and up events
            Thread.sleep(100)
            
            // Create key up event
            val upTime = System.currentTimeMillis()
            val keyUpEvent = KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0)
            val upResult = performGlobalAction(keyCode)
            
            return downResult && upResult
        } catch (e: Exception) {
            Log.e(TAG, "Error performing key press: ${e.message}")
            return false
        }
    }
    
    /**
     * Press the home button
     */
    fun pressHomeButton() {
        Log.d(TAG, "Pressing home button")
        showToast("Drücke Home-Button...", false)
        
        try {
            // Use the global action to press the home button
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
            
            if (result) {
                Log.d(TAG, "Successfully pressed home button")
                showToast("Home-Button erfolgreich gedrückt", false)
            } else {
                Log.e(TAG, "Failed to press home button")
                showToast("Fehler beim Drücken des Home-Buttons", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing home button: ${e.message}")
            showToast("Fehler beim Drücken des Home-Buttons: ${e.message}", true)
        }
    }
    
    /**
     * Press the back button
     */
    fun pressBackButton() {
        Log.d(TAG, "Pressing back button")
        showToast("Drücke Zurück-Button...", false)
        
        try {
            // Use the global action to press the back button
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
            
            if (result) {
                Log.d(TAG, "Successfully pressed back button")
                showToast("Zurück-Button erfolgreich gedrückt", false)
            } else {
                Log.e(TAG, "Failed to press back button")
                showToast("Fehler beim Drücken des Zurück-Buttons", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing back button: ${e.message}")
            showToast("Fehler beim Drücken des Zurück-Buttons: ${e.message}", true)
        }
    }
    
    /**
     * Show recent apps overview
     */
    fun showRecentApps() {
        Log.d(TAG, "Showing recent apps")
        showToast("Öffne Übersicht der letzten Apps...", false)
        
        try {
            // Use the global action to show recent apps
            val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
            
            if (result) {
                Log.d(TAG, "Successfully showed recent apps")
                showToast("Übersicht der letzten Apps erfolgreich geöffnet", false)
            } else {
                Log.e(TAG, "Failed to show recent apps")
                showToast("Fehler beim Öffnen der Übersicht der letzten Apps", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recent apps: ${e.message}")
            showToast("Fehler beim Öffnen der Übersicht der letzten Apps: ${e.message}", true)
        }
    }
    
    /**
     * Retrieve the latest screenshot from the standard screenshot folder
     */
    private fun retrieveLatestScreenshot(screenInfo: String) {
        try {
            Log.d(TAG, "Retrieving latest screenshot")
            showToast("Suche nach dem aufgenommenen Screenshot...", false)
            
            // Check standard screenshot locations
            val screenshotFile = findLatestScreenshotFile()
            
            if (screenshotFile != null) {
                Log.d(TAG, "Found screenshot file: ${screenshotFile.absolutePath}")
                showToast("Screenshot gefunden: ${screenshotFile.name}", false)
                
                // Convert file to URI
                val screenshotUri = Uri.fromFile(screenshotFile)
                
                // Add the screenshot to the conversation with screen information
                addScreenshotToConversation(screenshotUri, screenInfo)
            } else {
                Log.e(TAG, "No screenshot file found")
                showToast("Kein Screenshot gefunden. Bitte prüfen Sie die Berechtigungen.", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving screenshot: ${e.message}")
            showToast("Fehler beim Abrufen des Screenshots: ${e.message}", true)
        }
    }
    
    /**
     * Find the latest screenshot file in standard locations
     */
    private fun findLatestScreenshotFile(): File? {
        try {
            // List of possible screenshot directories
            val possibleDirs = listOf(
                // Primary location on most devices
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
                // Secondary location on some devices
                File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
                // DCIM location used by some manufacturers
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots"),
                // App-specific location
                File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
            )
            
            // Find the most recent screenshot file
            var latestFile: File? = null
            var latestModified: Long = 0
            
            for (dir in possibleDirs) {
                if (dir.exists() && dir.isDirectory) {
                    Log.d(TAG, "Checking directory: ${dir.absolutePath}")
                    
                    val files = dir.listFiles { file ->
                        file.isFile && (file.name.startsWith("Screenshot") || 
                                        file.name.startsWith("screenshot")) &&
                        (file.name.endsWith(".png") || file.name.endsWith(".jpg") || 
                         file.name.endsWith(".jpeg"))
                    }
                    
                    files?.forEach { file ->
                        Log.d(TAG, "Found file: ${file.name}, modified: ${file.lastModified()}")
                        if (file.lastModified() > latestModified) {
                            latestFile = file
                            latestModified = file.lastModified()
                        }
                    }
                }
            }
            
            // Check if the file is recent (within the last 10 seconds)
            if (latestFile != null) {
                val currentTime = System.currentTimeMillis()
                val fileAge = currentTime - latestModified
                
                if (fileAge <= 10000) { // 10 seconds
                    Log.d(TAG, "Found recent screenshot: ${latestFile?.absolutePath}, age: ${fileAge}ms")
                    return latestFile
                } else {
                    Log.d(TAG, "Found screenshot is too old: ${latestFile?.absolutePath}, age: ${fileAge}ms")
                }
            }
            
            // If no recent file found, try to query MediaStore
            val latestMediaStoreFile = findLatestScreenshotViaMediaStore()
            if (latestMediaStoreFile != null) {
                return latestMediaStoreFile
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding latest screenshot file: ${e.message}")
            return null
        }
    }
    
    /**
     * Find the latest screenshot using MediaStore
     */
    private fun findLatestScreenshotViaMediaStore(): File? {
        try {
            val contentResolver = applicationContext.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED
            )
            
            // Query for recent screenshots
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%screenshot%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val dateColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    
                    val filePath = cursor.getString(dataColumnIndex)
                    val dateAdded = cursor.getLong(dateColumnIndex) * 1000 // Convert to milliseconds
                    
                    val file = File(filePath)
                    if (file.exists()) {
                        val currentTime = System.currentTimeMillis()
                        val fileAge = currentTime - dateAdded
                        
                        if (fileAge <= 10000) { // 10 seconds
                            Log.d(TAG, "Found recent screenshot via MediaStore: $filePath, age: ${fileAge}ms")
                            return file
                        } else {
                            Log.d(TAG, "Found screenshot via MediaStore is too old: $filePath, age: ${fileAge}ms")
                        }
                    }
                }
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding screenshot via MediaStore: ${e.message}")
            return null
        }
    }
    
    /**
     * Add the screenshot to the conversation with screen information
     */
    private fun addScreenshotToConversation(screenshotUri: Uri, screenInfo: String) {
        try {
            Log.d(TAG, "Adding screenshot to conversation with screen information: $screenshotUri")
            
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
            
            // Add the screenshot to the conversation with screen information
            photoReasoningViewModel.addScreenshotToConversation(screenshotUri, applicationContext, screenInfo)
            
            Log.d(TAG, "Screenshot added to conversation with screen information")
            showToast("Screenshot mit Bildschirminformationen zur Konversation hinzugefügt", false)
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
