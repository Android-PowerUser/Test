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
                is Command.PressHome -> {
                    Log.d(TAG, "Pressing Home button")
                    showToast("Drücke Home-Taste", false)
                    serviceInstance?.pressHomeButton()
                }
                is Command.PressBack -> {
                    Log.d(TAG, "Pressing Back button")
                    showToast("Drücke Zurück-Taste", false)
                    serviceInstance?.pressBackButton()
                }
                is Command.ShowRecentApps -> {
                    Log.d(TAG, "Showing recent apps")
                    showToast("Zeige letzte Apps", false)
                    serviceInstance?.showRecentApps()
                }
                is Command.PullStatusBarDown -> {
                    Log.d(TAG, "Pulling status bar down")
                    showToast("Ziehe Statusleiste herunter", false)
                    serviceInstance?.pullStatusBarDown()
                }
                is Command.PullStatusBarDownTwice -> {
                    Log.d(TAG, "Pulling status bar down twice")
                    showToast("Ziehe Statusleiste zweimal herunter", false)
                    serviceInstance?.pullStatusBarDownTwice()
                }
                is Command.PushStatusBarUp -> {
                    Log.d(TAG, "Pushing status bar up")
                    showToast("Schiebe Statusleiste hoch", false)
                    serviceInstance?.pushStatusBarUp()
                }
                is Command.ScrollUp -> {
                    Log.d(TAG, "Scrolling up")
                    showToast("Scrolle nach oben", false)
                    serviceInstance?.scrollUp()
                }
                is Command.ScrollDown -> {
                    Log.d(TAG, "Scrolling down")
                    showToast("Scrolle nach unten", false)
                    serviceInstance?.scrollDown()
                }
                is Command.ScrollLeft -> {
                    Log.d(TAG, "Scrolling left")
                    showToast("Scrolle nach links", false)
                    serviceInstance?.scrollLeft()
                }
                is Command.ScrollRight -> {
                    Log.d(TAG, "Scrolling right")
                    showToast("Scrolle nach rechts", false)
                    serviceInstance?.scrollRight()
                }
                is Command.OpenApp -> {
                    Log.d(TAG, "Opening app: ${command.appName}")
                    showToast("Öffne App: ${command.appName}", false)
                    serviceInstance?.openApp(command.appName)
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
    
    // Screen dimensions
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    
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
        
        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
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
            showToast("Button mit Klasse \"$className\" nicht gefunden", true)
        }
    }
    
    /**
     * Find a node by text
     */
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
    
    /**
     * Find a node by content description
     */
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        // Check if this node has the content description we're looking for
        if (node.contentDescription != null && node.contentDescription.toString().contains(description, ignoreCase = true)) {
            return node
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, description)
            if (result != null) {
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Find a node by class name
     */
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        // Check if this node has the class name we're looking for
        if (node.className != null && node.className.toString() == className) {
            return node
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByClassName(child, className)
            if (result != null) {
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Perform a click on a node
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        try {
            // Check if the node is clickable
            if (node.isClickable) {
                // Perform the click action
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                Log.d(TAG, "Node is not clickable, trying to find clickable parent")
                
                // Try to find a clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        return result
                    }
                    val tempParent = parent.parent
                    parent.recycle()
                    parent = tempParent
                }
                
                Log.e(TAG, "Could not find clickable parent")
                return false
            }
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
            // Simulate pressing Power + Volume Down buttons to take a screenshot
            simulateScreenshotButtonCombination()
            
            // Wait a moment for the screenshot to be saved, then retrieve it
            handler.postDelayed({
                retrieveLatestScreenshot()
            }, 1000) // Wait 1 second for the screenshot to be saved
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            showToast("Fehler beim Aufnehmen des Screenshots: ${e.message}", true)
        }
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
     * Retrieve the latest screenshot from the standard screenshot folder
     */
    private fun retrieveLatestScreenshot() {
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
                
                // Capture screen information
                val screenInfo = captureScreenInformation()
                
                // Add the screenshot to the conversation
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
     * Capture information about interactive elements on the screen
     */
    private fun captureScreenInformation(): String {
        Log.d(TAG, "Capturing screen information")
        
        // Refresh the root node
        refreshRootNode()
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot capture screen information")
            return "Keine Bildschirminformationen verfügbar (Root-Knoten ist null)"
        }
        
        // Find all interactive elements
        val elements = findAllInteractiveElements(rootNode!!)
        
        // Format the information
        val sb = StringBuilder()
        sb.appendLine("Bildschirmelemente:")
        
        if (elements.isEmpty()) {
            sb.appendLine("Keine interaktiven Elemente gefunden")
        } else {
            elements.forEachIndexed { index, element ->
                sb.appendLine("Element ${index + 1}:")
                
                // Get button name or text
                val buttonName = getButtonName(element)
                if (buttonName.isNotEmpty()) {
                    sb.appendLine("Name: $buttonName")
                }
                
                // Get element text
                val text = element.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    sb.appendLine("Text: $text")
                }
                
                // Get content description
                val contentDesc = element.contentDescription?.toString() ?: ""
                if (contentDesc.isNotEmpty() && contentDesc != text) {
                    sb.appendLine("Beschreibung: $contentDesc")
                }
                
                // Get class name
                val className = element.className?.toString()?.substringAfterLast('.') ?: "View"
                sb.appendLine("Klasse: $className")
                
                // Get position
                val rect = Rect()
                element.getBoundsInScreen(rect)
                sb.appendLine("Position: (${rect.centerX()}, ${rect.centerY()})")
                
                // Get clickable status
                sb.appendLine("Klickbar: ${if (element.isClickable) "Ja" else "Nein"}")
                
                // Add separator between elements
                if (index < elements.size - 1) {
                    sb.appendLine("---")
                }
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Find all interactive elements in the accessibility tree
     */
    private fun findAllInteractiveElements(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // Check if this node is interactive
            if (node.isClickable || node.isLongClickable || node.isCheckable || 
                node.isScrollable || node.isFocusable) {
                elements.add(AccessibilityNodeInfo.obtain(node))
            }
            
            // Check child nodes
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
     * Get the ID of a node
     */
    private fun getNodeId(node: AccessibilityNodeInfo): String {
        return try {
            val viewIdResourceName = node.viewIdResourceName ?: ""
            if (viewIdResourceName.isNotEmpty()) {
                viewIdResourceName.substringAfterLast('/')
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Get the button name from a node
     */
    private fun getButtonName(node: AccessibilityNodeInfo): String {
        // Try to get text directly
        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) {
            return text
        }
        
        // Try to get content description
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (contentDesc.isNotEmpty()) {
            return contentDesc
        }
        
        // Try to get ID and convert to readable name
        val id = getNodeId(node)
        if (id.isNotEmpty()) {
            // Convert camelCase or snake_case to readable text
            val readableName = id
                .replace("_", " ")
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .lowercase()
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
            
            if (readableName.isNotEmpty()) {
                return readableName
            }
        }
        
        // Try to get text from parent
        val parent = node.parent
        if (parent != null) {
            val parentText = parent.text?.toString() ?: ""
            parent.recycle()
            if (parentText.isNotEmpty()) {
                return parentText
            }
        }
        
        // Special case for FAB (Floating Action Button)
        val className = node.className?.toString() ?: ""
        if (className.contains("FloatingActionButton") || className.contains("Fab")) {
            // Check position - FABs are often in the bottom right or bottom center
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            // If it's in the bottom right quadrant, it's likely a "New" or "Add" button
            if (rect.centerX() > screenWidth * 0.75 && rect.centerY() > screenHeight * 0.75) {
                return "New"
            }
            
            // If it's in the bottom center, it might be a "New" button
            if (rect.centerX() > screenWidth * 0.4 && rect.centerX() < screenWidth * 0.6 && 
                rect.centerY() > screenHeight * 0.75) {
                return "New"
            }
        }
        
        // Check if it's a circular button in the bottom right (often "New" or "Add")
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val width = rect.width()
        val height = rect.height()
        
        // If it's a square or circular button (width ≈ height) in the bottom right
        if (Math.abs(width - height) < 20 && 
            rect.centerX() > screenWidth * 0.75 && 
            rect.centerY() > screenHeight * 0.75) {
            return "New"
        }
        
        // If it's in the bottom left corner, it might be a "Home" button
        if (rect.centerX() < screenWidth * 0.25 && rect.centerY() > screenHeight * 0.75) {
            return "Home"
        }
        
        // Return empty string if no name found
        return ""
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
     * Add the screenshot to the conversation
     */
    private fun addScreenshotToConversation(screenshotUri: Uri, screenInfo: String = "") {
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
            
            // Add the screenshot to the conversation with screen information
            photoReasoningViewModel.addScreenshotToConversation(screenshotUri, applicationContext, screenInfo)
            
            Log.d(TAG, "Screenshot added to conversation")
            showToast("Screenshot zur Konversation hinzugefügt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding screenshot to conversation: ${e.message}")
            showToast("Fehler beim Hinzufügen des Screenshots zur Konversation: ${e.message}", true)
        }
    }
    
    /**
     * Press the Home button
     */
    fun pressHomeButton() {
        Log.d(TAG, "Pressing Home button")
        showToast("Drücke Home-Taste", false)
        
        try {
            val result = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (result) {
                Log.d(TAG, "Successfully pressed Home button")
                showToast("Home-Taste erfolgreich gedrückt", false)
            } else {
                Log.e(TAG, "Failed to press Home button")
                showToast("Fehler beim Drücken der Home-Taste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing Home button: ${e.message}")
            showToast("Fehler beim Drücken der Home-Taste: ${e.message}", true)
        }
    }
    
    /**
     * Press the Back button
     */
    fun pressBackButton() {
        Log.d(TAG, "Pressing Back button")
        showToast("Drücke Zurück-Taste", false)
        
        try {
            val result = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (result) {
                Log.d(TAG, "Successfully pressed Back button")
                showToast("Zurück-Taste erfolgreich gedrückt", false)
            } else {
                Log.e(TAG, "Failed to press Back button")
                showToast("Fehler beim Drücken der Zurück-Taste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing Back button: ${e.message}")
            showToast("Fehler beim Drücken der Zurück-Taste: ${e.message}", true)
        }
    }
    
    /**
     * Show recent apps
     */
    fun showRecentApps() {
        Log.d(TAG, "Showing recent apps")
        showToast("Zeige letzte Apps", false)
        
        try {
            val result = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            if (result) {
                Log.d(TAG, "Successfully showed recent apps")
                showToast("Letzte Apps erfolgreich angezeigt", false)
            } else {
                Log.e(TAG, "Failed to show recent apps")
                showToast("Fehler beim Anzeigen der letzten Apps", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recent apps: ${e.message}")
            showToast("Fehler beim Anzeigen der letzten Apps: ${e.message}", true)
        }
    }
    
    /**
     * Pull down the status bar (notifications)
     */
    fun pullStatusBarDown() {
        Log.d(TAG, "Pulling status bar down")
        showToast("Ziehe Statusleiste herunter", false)
        
        try {
            val result = performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            if (result) {
                Log.d(TAG, "Successfully pulled status bar down")
                showToast("Statusleiste erfolgreich heruntergezogen", false)
            } else {
                Log.e(TAG, "Failed to pull status bar down")
                showToast("Fehler beim Herunterziehen der Statusleiste", true)
                
                // Try using a gesture as fallback
                pullStatusBarDownWithGesture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down: ${e.message}")
            showToast("Fehler beim Herunterziehen der Statusleiste: ${e.message}", true)
            
            // Try using a gesture as fallback
            pullStatusBarDownWithGesture()
        }
    }
    
    /**
     * Pull down the status bar using a gesture
     */
    private fun pullStatusBarDownWithGesture() {
        Log.d(TAG, "Pulling status bar down with gesture")
        showToast("Versuche Statusleiste mit Geste herunterzuziehen", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe down gesture from top of screen
            val path = Path()
            path.moveTo(screenWidth / 2f, 0f)
            path.lineTo(screenWidth / 2f, screenHeight / 3f)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Pull down gesture completed")
                    showToast("Statusleiste erfolgreich heruntergezogen", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Pull down gesture cancelled")
                    showToast("Herunterziehen der Statusleiste abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch pull down gesture")
                showToast("Fehler beim Senden der Herunterzieh-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down with gesture: ${e.message}")
            showToast("Fehler beim Herunterziehen der Statusleiste mit Geste: ${e.message}", true)
        }
    }
    
    /**
     * Pull down the status bar twice (quick settings)
     */
    fun pullStatusBarDownTwice() {
        Log.d(TAG, "Pulling status bar down twice")
        showToast("Ziehe Statusleiste zweimal herunter", false)
        
        try {
            // First pull down to show notifications
            val result1 = performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            if (result1) {
                Log.d(TAG, "Successfully pulled status bar down once")
                
                // Wait a moment before pulling down again
                handler.postDelayed({
                    // Try to pull down again to show quick settings
                    // On some devices, we need to use GLOBAL_ACTION_QUICK_SETTINGS
                    // On others, we need to use a gesture
                    
                    // Try GLOBAL_ACTION_QUICK_SETTINGS first if available
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val result2 = performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                        if (result2) {
                            Log.d(TAG, "Successfully pulled status bar down twice using GLOBAL_ACTION_QUICK_SETTINGS")
                            showToast("Schnelleinstellungen erfolgreich geöffnet", false)
                            return@postDelayed
                        }
                    }
                    
                    // Fallback to using a gesture
                    pullStatusBarDownWithGesture()
                }, 500) // 500ms delay
            } else {
                Log.e(TAG, "Failed to pull status bar down once")
                showToast("Fehler beim ersten Herunterziehen der Statusleiste", true)
                
                // Try using a gesture as fallback
                pullStatusBarDownWithGesture()
                
                // Wait a moment before pulling down again
                handler.postDelayed({
                    pullStatusBarDownWithGesture()
                }, 500) // 500ms delay
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down twice: ${e.message}")
            showToast("Fehler beim zweimaligen Herunterziehen der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Push the status bar up (close notifications/quick settings)
     */
    fun pushStatusBarUp() {
        Log.d(TAG, "Pushing status bar up")
        showToast("Schiebe Statusleiste hoch", false)
        
        try {
            // Try using GLOBAL_ACTION_BACK to close notifications
            val result = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (result) {
                Log.d(TAG, "Successfully pushed status bar up using BACK action")
                showToast("Statusleiste erfolgreich hochgeschoben", false)
            } else {
                Log.e(TAG, "Failed to push status bar up using BACK action")
                showToast("Fehler beim Hochschieben der Statusleiste mit BACK-Aktion", true)
                
                // Try using a gesture as fallback
                pushStatusBarUpWithGesture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing status bar up: ${e.message}")
            showToast("Fehler beim Hochschieben der Statusleiste: ${e.message}", true)
            
            // Try using a gesture as fallback
            pushStatusBarUpWithGesture()
        }
    }
    
    /**
     * Push the status bar up using a gesture
     */
    private fun pushStatusBarUpWithGesture() {
        Log.d(TAG, "Pushing status bar up with gesture")
        showToast("Versuche Statusleiste mit Geste hochzuschieben", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe up gesture from middle to top of screen
            val path = Path()
            path.moveTo(screenWidth / 2f, screenHeight / 3f)
            path.lineTo(screenWidth / 2f, 0f)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Push up gesture completed")
                    showToast("Statusleiste erfolgreich hochgeschoben", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Push up gesture cancelled")
                    showToast("Hochschieben der Statusleiste abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch push up gesture")
                showToast("Fehler beim Senden der Hochschieb-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing status bar up with gesture: ${e.message}")
            showToast("Fehler beim Hochschieben der Statusleiste mit Geste: ${e.message}", true)
        }
    }
    
    /**
     * Scroll up
     */
    fun scrollUp() {
        Log.d(TAG, "Scrolling up")
        showToast("Scrolle nach oben", false)
        
        try {
            // Try to find a scrollable node and scroll it
            val scrollableNode = findScrollableNode(rootNode)
            if (scrollableNode != null) {
                val result = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                scrollableNode.recycle()
                
                if (result) {
                    Log.d(TAG, "Successfully scrolled up using node")
                    showToast("Erfolgreich nach oben gescrollt", false)
                    return
                } else {
                    Log.e(TAG, "Failed to scroll up using node")
                }
            }
            
            // Fallback to using a gesture
            scrollUpWithGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up: ${e.message}")
            showToast("Fehler beim Scrollen nach oben: ${e.message}", true)
            
            // Try using a gesture as fallback
            scrollUpWithGesture()
        }
    }
    
    /**
     * Scroll up using a gesture
     */
    private fun scrollUpWithGesture() {
        Log.d(TAG, "Scrolling up with gesture")
        showToast("Versuche mit Geste nach oben zu scrollen", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe down gesture (swipe down to scroll up)
            val path = Path()
            path.moveTo(screenWidth / 2f, screenHeight / 3f)
            path.lineTo(screenWidth / 2f, screenHeight * 2 / 3f)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll up gesture completed")
                    showToast("Erfolgreich nach oben gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll up gesture cancelled")
                    showToast("Scrollen nach oben abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll up gesture")
                showToast("Fehler beim Senden der Scroll-nach-oben-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up with gesture: ${e.message}")
            showToast("Fehler beim Scrollen nach oben mit Geste: ${e.message}", true)
        }
    }
    
    /**
     * Scroll down
     */
    fun scrollDown() {
        Log.d(TAG, "Scrolling down")
        showToast("Scrolle nach unten", false)
        
        try {
            // Try to find a scrollable node and scroll it
            val scrollableNode = findScrollableNode(rootNode)
            if (scrollableNode != null) {
                val result = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrollableNode.recycle()
                
                if (result) {
                    Log.d(TAG, "Successfully scrolled down using node")
                    showToast("Erfolgreich nach unten gescrollt", false)
                    return
                } else {
                    Log.e(TAG, "Failed to scroll down using node")
                }
            }
            
            // Fallback to using a gesture
            scrollDownWithGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down: ${e.message}")
            showToast("Fehler beim Scrollen nach unten: ${e.message}", true)
            
            // Try using a gesture as fallback
            scrollDownWithGesture()
        }
    }
    
    /**
     * Scroll down using a gesture
     */
    private fun scrollDownWithGesture() {
        Log.d(TAG, "Scrolling down with gesture")
        showToast("Versuche mit Geste nach unten zu scrollen", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe up gesture (swipe up to scroll down)
            val path = Path()
            path.moveTo(screenWidth / 2f, screenHeight * 2 / 3f)
            path.lineTo(screenWidth / 2f, screenHeight / 3f)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll down gesture completed")
                    showToast("Erfolgreich nach unten gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll down gesture cancelled")
                    showToast("Scrollen nach unten abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll down gesture")
                showToast("Fehler beim Senden der Scroll-nach-unten-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down with gesture: ${e.message}")
            showToast("Fehler beim Scrollen nach unten mit Geste: ${e.message}", true)
        }
    }
    
    /**
     * Scroll left
     */
    fun scrollLeft() {
        Log.d(TAG, "Scrolling left")
        showToast("Scrolle nach links", false)
        
        try {
            // Try to find a horizontally scrollable node and scroll it
            val scrollableNode = findHorizontallyScrollableNode(rootNode)
            if (scrollableNode != null) {
                val result = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                scrollableNode.recycle()
                
                if (result) {
                    Log.d(TAG, "Successfully scrolled left using node")
                    showToast("Erfolgreich nach links gescrollt", false)
                    return
                } else {
                    Log.e(TAG, "Failed to scroll left using node")
                }
            }
            
            // Fallback to using a gesture
            scrollLeftWithGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left: ${e.message}")
            showToast("Fehler beim Scrollen nach links: ${e.message}", true)
            
            // Try using a gesture as fallback
            scrollLeftWithGesture()
        }
    }
    
    /**
     * Scroll left using a gesture
     */
    private fun scrollLeftWithGesture() {
        Log.d(TAG, "Scrolling left with gesture")
        showToast("Versuche mit Geste nach links zu scrollen", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe right gesture (swipe right to scroll left)
            val path = Path()
            path.moveTo(screenWidth / 3f, screenHeight / 2f)
            path.lineTo(screenWidth * 2 / 3f, screenHeight / 2f)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll left gesture completed")
                    showToast("Erfolgreich nach links gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll left gesture cancelled")
                    showToast("Scrollen nach links abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll left gesture")
                showToast("Fehler beim Senden der Scroll-nach-links-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left with gesture: ${e.message}")
            showToast("Fehler beim Scrollen nach links mit Geste: ${e.message}", true)
        }
    }
    
    /**
     * Scroll right
     */
    fun scrollRight() {
        Log.d(TAG, "Scrolling right")
        showToast("Scrolle nach rechts", false)
        
        try {
            // Try to find a horizontally scrollable node and scroll it
            val scrollableNode = findHorizontallyScrollableNode(rootNode)
            if (scrollableNode != null) {
                val result = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrollableNode.recycle()
                
                if (result) {
                    Log.d(TAG, "Successfully scrolled right using node")
                    showToast("Erfolgreich nach rechts gescrollt", false)
                    return
                } else {
                    Log.e(TAG, "Failed to scroll right using node")
                }
            }
            
            // Fallback to using a gesture
            scrollRightWithGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right: ${e.message}")
            showToast("Fehler beim Scrollen nach rechts: ${e.message}", true)
            
            // Try using a gesture as fallback
            scrollRightWithGesture()
        }
    }
    
    /**
     * Scroll right using a gesture
     */
    private fun scrollRightWithGesture() {
        Log.d(TAG, "Scrolling right with gesture")
        showToast("Versuche mit Geste nach rechts zu scrollen", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe left gesture (swipe left to scroll right)
            val path = Path()
            path.moveTo(screenWidth * 2 / 3f, screenHeight / 2f)
            path.lineTo(screenWidth / 3f, screenHeight / 2f)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll right gesture completed")
                    showToast("Erfolgreich nach rechts gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll right gesture cancelled")
                    showToast("Scrollen nach rechts abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll right gesture")
                showToast("Fehler beim Senden der Scroll-nach-rechts-Geste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right with gesture: ${e.message}")
            showToast("Fehler beim Scrollen nach rechts mit Geste: ${e.message}", true)
        }
    }
    
    /**
     * Find a scrollable node in the accessibility tree
     */
    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        
        // Check if this node is scrollable
        if (node.isScrollable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Find a horizontally scrollable node in the accessibility tree
     */
    private fun findHorizontallyScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        
        // Check if this node is scrollable
        if (node.isScrollable) {
            // Try to determine if it's horizontally scrollable
            // This is a heuristic, as there's no direct way to check
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            // If the node is wider than it is tall, it's likely horizontally scrollable
            if (rect.width() > rect.height()) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        
        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findHorizontallyScrollableNode(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Open an app by name
     */
    fun openApp(appName: String) {
        Log.d(TAG, "Opening app: $appName")
        showToast("Öffne App: $appName", false)
        
        try {
            // Go to home screen first
            pressHomeButton()
            
            // Wait a moment for the home screen to appear
            handler.postDelayed({
                // Try to find and click the app icon
                findAndClickButtonByText(appName)
            }, 500) // 500ms delay
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}")
            showToast("Fehler beim Öffnen der App: ${e.message}", true)
        }
    }
}
