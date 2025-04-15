package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.ai.sample.util.AppNamePackageMapper
import com.google.ai.sample.util.Command
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
                is Command.ScrollDown -> {
                    Log.d(TAG, "Scrolling down")
                    showToast("Versuche nach unten zu scrollen", false)
                    serviceInstance?.scrollDown()
                }
                is Command.ScrollUp -> {
                    Log.d(TAG, "Scrolling up")
                    showToast("Versuche nach oben zu scrollen", false)
                    serviceInstance?.scrollUp()
                }
                is Command.ScrollLeft -> {
                    Log.d(TAG, "Scrolling left")
                    showToast("Versuche nach links zu scrollen", false)
                    serviceInstance?.scrollLeft()
                }
                is Command.ScrollRight -> {
                    Log.d(TAG, "Scrolling right")
                    showToast("Versuche nach rechts zu scrollen", false)
                    serviceInstance?.scrollRight()
                }
                is Command.ScrollDownFromCoordinates -> {
                    Log.d(TAG, "Scrolling down from coordinates (${command.x}, ${command.y}) with distance ${command.distance} and duration ${command.duration}ms")
                    showToast("Versuche von Position (${command.x}, ${command.y}) nach unten zu scrollen", false)
                    serviceInstance?.scrollDown(command.x, command.y, command.distance, command.duration)
                }
                is Command.ScrollUpFromCoordinates -> {
                    Log.d(TAG, "Scrolling up from coordinates (${command.x}, ${command.y}) with distance ${command.distance} and duration ${command.duration}ms")
                    showToast("Versuche von Position (${command.x}, ${command.y}) nach oben zu scrollen", false)
                    serviceInstance?.scrollUp(command.x, command.y, command.distance, command.duration)
                }
                is Command.ScrollLeftFromCoordinates -> {
                    Log.d(TAG, "Scrolling left from coordinates (${command.x}, ${command.y}) with distance ${command.distance} and duration ${command.duration}ms")
                    showToast("Versuche von Position (${command.x}, ${command.y}) nach links zu scrollen", false)
                    serviceInstance?.scrollLeft(command.x, command.y, command.distance, command.duration)
                }
                is Command.ScrollRightFromCoordinates -> {
                    Log.d(TAG, "Scrolling right from coordinates (${command.x}, ${command.y}) with distance ${command.distance} and duration ${command.duration}ms")
                    showToast("Versuche von Position (${command.x}, ${command.y}) nach rechts zu scrollen", false)
                    serviceInstance?.scrollRight(command.x, command.y, command.distance, command.duration)
                }
                is Command.OpenApp -> {
                    Log.d(TAG, "Opening app: ${command.packageName}")
                    showToast("Versuche App zu öffnen: ${command.packageName}", false)
                    serviceInstance?.openApp(command.packageName)
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
    
    // App name to package mapper
    private lateinit var appNamePackageMapper: AppNamePackageMapper
    
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
        
        // Initialize app name to package mapper
        appNamePackageMapper = AppNamePackageMapper(applicationContext)
        appNamePackageMapper.initializeCache()
        
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
        
        // Refresh the root node
        refreshRootNode()
        
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
            showToast("Button gefunden mit Beschreibung: \"$description\"", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button with description: $description")
                    showToast("Klick auf Button mit Beschreibung \"$description\" erfolgreich", false)
                } else {
                    Log.e(TAG, "Failed to click on button with description: $description")
                    showToast("Klick auf Button mit Beschreibung \"$description\" fehlgeschlagen, versuche alternative Methoden", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, description)
                }
                
                // Recycle the node
                node.recycle()
            }, 200) // 200ms delay
        } else {
            Log.e(TAG, "Could not find node with content description: $description")
            showToast("Button mit Beschreibung \"$description\" nicht gefunden", true)
        }
    }
    
    /**
     * Find a node by text recursively
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Check if this node has the text we're looking for
        if (node.text != null && node.text.toString().equals(text, ignoreCase = true)) {
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
        if (node.contentDescription != null && node.contentDescription.toString().equals(description, ignoreCase = true)) {
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
     * Open an app by name or package name
     * This improved version first checks if the input is an app name and converts it to a package name,
     * then tries multiple intent configurations if the first attempt fails
     */
    fun openApp(nameOrPackage: String) {
        Log.d(TAG, "Opening app with name or package: $nameOrPackage")
        showToast("Versuche App zu öffnen: $nameOrPackage", false)
        
        try {
            // First, try to resolve the app name to a package name if needed
            val packageName = appNamePackageMapper.getPackageName(nameOrPackage) ?: nameOrPackage
            
            // If the input was an app name, show the resolved package name
            if (packageName != nameOrPackage) {
                Log.d(TAG, "Resolved app name '$nameOrPackage' to package name '$packageName'")
                showToast("App-Name '$nameOrPackage' aufgelöst zu Paket-Name '$packageName'", false)
            }
            
            // Get the package manager
            val packageManager = applicationContext.packageManager
            
            // Get the app name for better user feedback
            val appName = try {
                appNamePackageMapper.getAppName(packageName)
            } catch (e: Exception) {
                packageName
            }
            
            // First try: Use getLaunchIntentForPackage (standard approach)
            var launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            
            if (launchIntent != null) {
                // Add flags to reuse existing instance if possible
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                try {
                    applicationContext.startActivity(launchIntent)
                    Log.d(TAG, "Successfully opened app using standard launch intent: $packageName")
                    showToast("App geöffnet: $appName", false)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting activity with standard launch intent: ${e.message}")
                    // Continue to alternative methods
                }
            } else {
                Log.d(TAG, "Standard launch intent is null for package: $packageName, trying alternative methods")
            }
            
            // Second try: Create intent with CATEGORY_LAUNCHER
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                intent.setPackage(packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                
                if (resolveInfos.isNotEmpty()) {
                    val resolveInfo = resolveInfos[0]
                    val activityInfo = resolveInfo.activityInfo
                    val componentName = ComponentName(activityInfo.applicationInfo.packageName, activityInfo.name)
                    
                    intent.component = componentName
                    applicationContext.startActivity(intent)
                    
                    Log.d(TAG, "Successfully opened app using CATEGORY_LAUNCHER: $packageName")
                    showToast("App geöffnet: $appName", false)
                    return
                } else {
                    Log.d(TAG, "No activities found with CATEGORY_LAUNCHER for package: $packageName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with CATEGORY_LAUNCHER approach: ${e.message}")
                // Continue to next method
            }
            
            // Third try: Create intent with CATEGORY_LEANBACK_LAUNCHER (for Android TV)
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                intent.setPackage(packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                
                if (resolveInfos.isNotEmpty()) {
                    val resolveInfo = resolveInfos[0]
                    val activityInfo = resolveInfo.activityInfo
                    val componentName = ComponentName(activityInfo.applicationInfo.packageName, activityInfo.name)
                    
                    intent.component = componentName
                    applicationContext.startActivity(intent)
                    
                    Log.d(TAG, "Successfully opened app using CATEGORY_LEANBACK_LAUNCHER: $packageName")
                    showToast("App geöffnet: $appName", false)
                    return
                } else {
                    Log.d(TAG, "No activities found with CATEGORY_LEANBACK_LAUNCHER for package: $packageName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with CATEGORY_LEANBACK_LAUNCHER approach: ${e.message}")
                // Continue to next method
            }
            
            // Fourth try: Find main activity directly
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.setPackage(packageName)
                
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                
                if (resolveInfos.isNotEmpty()) {
                    // Sort by priority
                    val sortedResolveInfos = resolveInfos.sortedByDescending { it.priority }
                    
                    for (resolveInfo in sortedResolveInfos) {
                        try {
                            val activityInfo = resolveInfo.activityInfo
                            val componentName = ComponentName(activityInfo.applicationInfo.packageName, activityInfo.name)
                            
                            val launchableIntent = Intent(Intent.ACTION_MAIN)
                            launchableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            launchableIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            launchableIntent.component = componentName
                            
                            applicationContext.startActivity(launchableIntent)
                            
                            Log.d(TAG, "Successfully opened app using direct activity approach: $packageName")
                            showToast("App geöffnet: $appName", false)
                            return
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting specific activity: ${e.message}")
                            // Try next activity in the list
                        }
                    }
                } else {
                    Log.d(TAG, "No activities found for package: $packageName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with direct activity approach: ${e.message}")
                // All methods failed
            }
            
            // If we get here, all methods failed
            Log.e(TAG, "All methods to open app failed for package: $packageName")
            showToast("Fehler: Keine App mit dem Namen oder Paket-Namen '$nameOrPackage' gefunden oder App kann nicht geöffnet werden", true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}", e)
            showToast("Fehler beim Öffnen der App: ${e.message}", true)
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
        // Implementation details omitted for brevity
        return "Screen information"
    }
    
    /**
     * Simulate pressing the Power + Volume Down buttons to take a screenshot
     */
    private fun simulateScreenshotButtonCombination() {
        // Implementation details omitted for brevity
    }
    
    /**
     * Retrieve the latest screenshot from the device
     */
    private fun retrieveLatestScreenshot(screenInfo: String) {
        // Implementation details omitted for brevity
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
     * Scroll down on the screen using gesture
     */
    fun scrollDown() {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll down from specific coordinates with custom distance and duration
     */
    fun scrollDown(x: Float, y: Float, distance: Float, duration: Long) {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll up on the screen using gesture
     */
    fun scrollUp() {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll up from specific coordinates with custom distance and duration
     */
    fun scrollUp(x: Float, y: Float, distance: Float, duration: Long) {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll left on the screen using gesture
     */
    fun scrollLeft() {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll left from specific coordinates with custom distance and duration
     */
    fun scrollLeft(x: Float, y: Float, distance: Float, duration: Long) {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll right on the screen using gesture
     */
    fun scrollRight() {
        // Implementation details omitted for brevity
    }
    
    /**
     * Scroll right from specific coordinates with custom distance and duration
     */
    fun scrollRight(x: Float, y: Float, distance: Float, duration: Long) {
        // Implementation details omitted for brevity
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
