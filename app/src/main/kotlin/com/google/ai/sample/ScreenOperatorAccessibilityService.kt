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
                    serviceInstance?.pressHome()
                }
                is Command.PressBack -> {
                    Log.d(TAG, "Pressing Back button")
                    showToast("Drücke Zurück-Taste", false)
                    serviceInstance?.pressBack()
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
                    showToast("Öffne App: \"${command.appName}\"", false)
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
     * Press the Home button
     */
    fun pressHome() {
        Log.d(TAG, "Pressing Home button")
        showToast("Drücke Home-Taste", false)
        
        try {
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
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
    fun pressBack() {
        Log.d(TAG, "Pressing Back button")
        showToast("Drücke Zurück-Taste", false)
        
        try {
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
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
            val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
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
     * Pull down the status bar to show notifications
     */
    fun pullStatusBarDown() {
        Log.d(TAG, "Pulling status bar down")
        showToast("Ziehe Statusleiste herunter", false)
        
        try {
            // First try using the global action if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val result = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                if (result) {
                    Log.d(TAG, "Successfully pulled status bar down using global action")
                    showToast("Statusleiste erfolgreich heruntergezogen", false)
                    return
                } else {
                    Log.e(TAG, "Failed to pull status bar down using global action")
                }
            }
            
            // Fallback to gesture
            performStatusBarDownGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down: ${e.message}")
            showToast("Fehler beim Herunterziehen der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Pull down the status bar twice to show quick settings
     */
    fun pullStatusBarDownTwice() {
        Log.d(TAG, "Pulling status bar down twice")
        showToast("Ziehe Statusleiste zweimal herunter", false)
        
        try {
            // First try using the global action if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val result = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                if (result) {
                    Log.d(TAG, "Successfully pulled status bar down twice using global action")
                    showToast("Schnelleinstellungen erfolgreich geöffnet", false)
                    return
                } else {
                    Log.e(TAG, "Failed to pull status bar down twice using global action")
                }
            }
            
            // Fallback to gesture
            performStatusBarDownTwiceGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down twice: ${e.message}")
            showToast("Fehler beim Öffnen der Schnelleinstellungen: ${e.message}", true)
        }
    }
    
    /**
     * Push the status bar up to close notifications/quick settings
     */
    fun pushStatusBarUp() {
        Log.d(TAG, "Pushing status bar up")
        showToast("Schiebe Statusleiste hoch", false)
        
        try {
            // First try using the global action if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Try to press back as a way to close notifications
                val result = performGlobalAction(GLOBAL_ACTION_BACK)
                if (result) {
                    Log.d(TAG, "Successfully pushed status bar up using back action")
                    showToast("Statusleiste erfolgreich hochgeschoben", false)
                    return
                } else {
                    Log.e(TAG, "Failed to push status bar up using back action")
                }
            }
            
            // Fallback to gesture
            performStatusBarUpGesture()
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing status bar up: ${e.message}")
            showToast("Fehler beim Hochschieben der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Perform a gesture to pull down the status bar
     */
    private fun performStatusBarDownGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe down gesture from top of screen
            val path = Path()
            path.moveTo(screenWidth / 2f, 0f) // Start from middle top
            path.lineTo(screenWidth / 2f, screenHeight / 3f) // Swipe down to 1/3 of screen
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Status bar down gesture completed")
                    showToast("Statusleiste erfolgreich heruntergezogen", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Status bar down gesture cancelled")
                    showToast("Geste zum Herunterziehen der Statusleiste abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch status bar down gesture")
                showToast("Fehler beim Senden der Geste zum Herunterziehen der Statusleiste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing status bar down gesture: ${e.message}")
            showToast("Fehler bei der Geste zum Herunterziehen der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Perform a gesture to pull down the status bar twice
     */
    private fun performStatusBarDownTwiceGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // First pull down the status bar
            performStatusBarDownGesture()
            
            // Then pull down again after a delay
            handler.postDelayed({
                // Create a second swipe down gesture
                val path = Path()
                path.moveTo(screenWidth / 2f, screenHeight / 6f) // Start from middle of notification shade
                path.lineTo(screenWidth / 2f, screenHeight / 2f) // Swipe down to middle of screen
                
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                    .build()
                
                // Dispatch the gesture
                val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Status bar down twice gesture completed")
                        showToast("Schnelleinstellungen erfolgreich geöffnet", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Status bar down twice gesture cancelled")
                        showToast("Geste zum Öffnen der Schnelleinstellungen abgebrochen", true)
                    }
                }, null)
                
                if (!dispatchResult) {
                    Log.e(TAG, "Failed to dispatch status bar down twice gesture")
                    showToast("Fehler beim Senden der Geste zum Öffnen der Schnelleinstellungen", true)
                }
            }, 500) // Wait 500ms between gestures
        } catch (e: Exception) {
            Log.e(TAG, "Error performing status bar down twice gesture: ${e.message}")
            showToast("Fehler bei der Geste zum Öffnen der Schnelleinstellungen: ${e.message}", true)
        }
    }
    
    /**
     * Perform a gesture to push the status bar up
     */
    private fun performStatusBarUpGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesten-API ist auf dieser Android-Version nicht verfügbar", true)
            return
        }
        
        try {
            // Create a swipe up gesture
            val path = Path()
            path.moveTo(screenWidth / 2f, screenHeight / 3f) // Start from 1/3 of screen
            path.lineTo(screenWidth / 2f, 0f) // Swipe up to top
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Status bar up gesture completed")
                    showToast("Statusleiste erfolgreich hochgeschoben", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Status bar up gesture cancelled")
                    showToast("Geste zum Hochschieben der Statusleiste abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch status bar up gesture")
                showToast("Fehler beim Senden der Geste zum Hochschieben der Statusleiste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing status bar up gesture: ${e.message}")
            showToast("Fehler bei der Geste zum Hochschieben der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Scroll up
     */
    fun scrollUp() {
        Log.d(TAG, "Scrolling up")
        showToast("Scrolle nach oben", false)
        
        try {
            // Create a swipe up gesture
            val path = Path()
            path.moveTo(screenWidth / 2f, screenHeight * 0.7f) // Start from lower middle
            path.lineTo(screenWidth / 2f, screenHeight * 0.3f) // Swipe up to upper middle
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll up gesture completed")
                    showToast("Nach oben gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll up gesture cancelled")
                    showToast("Geste zum Scrollen nach oben abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll up gesture")
                showToast("Fehler beim Senden der Geste zum Scrollen nach oben", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up: ${e.message}")
            showToast("Fehler beim Scrollen nach oben: ${e.message}", true)
        }
    }
    
    /**
     * Scroll down
     */
    fun scrollDown() {
        Log.d(TAG, "Scrolling down")
        showToast("Scrolle nach unten", false)
        
        try {
            // Create a swipe down gesture
            val path = Path()
            path.moveTo(screenWidth / 2f, screenHeight * 0.3f) // Start from upper middle
            path.lineTo(screenWidth / 2f, screenHeight * 0.7f) // Swipe down to lower middle
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll down gesture completed")
                    showToast("Nach unten gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll down gesture cancelled")
                    showToast("Geste zum Scrollen nach unten abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll down gesture")
                showToast("Fehler beim Senden der Geste zum Scrollen nach unten", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down: ${e.message}")
            showToast("Fehler beim Scrollen nach unten: ${e.message}", true)
        }
    }
    
    /**
     * Scroll left
     */
    fun scrollLeft() {
        Log.d(TAG, "Scrolling left")
        showToast("Scrolle nach links", false)
        
        try {
            // Create a swipe left gesture
            val path = Path()
            path.moveTo(screenWidth * 0.7f, screenHeight / 2f) // Start from right middle
            path.lineTo(screenWidth * 0.3f, screenHeight / 2f) // Swipe left to left middle
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll left gesture completed")
                    showToast("Nach links gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll left gesture cancelled")
                    showToast("Geste zum Scrollen nach links abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll left gesture")
                showToast("Fehler beim Senden der Geste zum Scrollen nach links", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left: ${e.message}")
            showToast("Fehler beim Scrollen nach links: ${e.message}", true)
        }
    }
    
    /**
     * Scroll right
     */
    fun scrollRight() {
        Log.d(TAG, "Scrolling right")
        showToast("Scrolle nach rechts", false)
        
        try {
            // Create a swipe right gesture
            val path = Path()
            path.moveTo(screenWidth * 0.3f, screenHeight / 2f) // Start from left middle
            path.lineTo(screenWidth * 0.7f, screenHeight / 2f) // Swipe right to right middle
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Scroll right gesture completed")
                    showToast("Nach rechts gescrollt", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Scroll right gesture cancelled")
                    showToast("Geste zum Scrollen nach rechts abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch scroll right gesture")
                showToast("Fehler beim Senden der Geste zum Scrollen nach rechts", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right: ${e.message}")
            showToast("Fehler beim Scrollen nach rechts: ${e.message}", true)
        }
    }
    
    /**
     * Open an app by name
     */
    fun openApp(appName: String) {
        Log.d(TAG, "Opening app: $appName")
        showToast("Versuche App zu öffnen: \"$appName\"", false)
        
        try {
            // Try to find the app in the launcher
            refreshRootNode()
            
            // First try to find by exact name
            var node = findNodeByText(rootNode!!, appName)
            
            // If not found, try to find by partial match
            if (node == null) {
                Log.d(TAG, "App not found by exact name, trying partial match")
                node = findNodeByPartialText(rootNode!!, appName)
            }
            
            if (node != null) {
                Log.d(TAG, "Found app icon: $appName")
                showToast("App-Icon gefunden: \"$appName\"", false)
                
                // Perform click on the app icon
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on app icon: $appName")
                    showToast("App \"$appName\" erfolgreich geöffnet", false)
                } else {
                    Log.e(TAG, "Failed to click on app icon: $appName")
                    showToast("Fehler beim Klicken auf App-Icon, versuche alternative Methoden", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, appName)
                }
                
                // Recycle the node
                node.recycle()
            } else {
                Log.e(TAG, "Could not find app: $appName")
                showToast("App \"$appName\" nicht gefunden, versuche Launcher zu öffnen", true)
                
                // Try to go to home screen first
                pressHome()
                
                // Wait a moment for the home screen to appear
                handler.postDelayed({
                    // Refresh the root node
                    refreshRootNode()
                    
                    // Try again to find the app
                    val homeNode = findNodeByText(rootNode!!, appName)
                    
                    if (homeNode != null) {
                        Log.d(TAG, "Found app icon on home screen: $appName")
                        showToast("App-Icon auf Startbildschirm gefunden: \"$appName\"", false)
                        
                        // Perform click on the app icon
                        val clickResult = performClickOnNode(homeNode)
                        
                        if (clickResult) {
                            Log.d(TAG, "Successfully clicked on app icon: $appName")
                            showToast("App \"$appName\" erfolgreich geöffnet", false)
                        } else {
                            Log.e(TAG, "Failed to click on app icon: $appName")
                            showToast("Fehler beim Klicken auf App-Icon", true)
                        }
                        
                        // Recycle the node
                        homeNode.recycle()
                    } else {
                        Log.e(TAG, "Could not find app on home screen: $appName")
                        showToast("App \"$appName\" nicht auf Startbildschirm gefunden", true)
                        
                        // Try to launch via intent as last resort
                        launchAppViaIntent(appName)
                    }
                }, 1000) // Wait 1 second for home screen to appear
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}")
            showToast("Fehler beim Öffnen der App: ${e.message}", true)
        }
    }
    
    /**
     * Launch an app via intent
     */
    private fun launchAppViaIntent(appName: String) {
        try {
            Log.d(TAG, "Trying to launch app via intent: $appName")
            showToast("Versuche App über Intent zu starten: \"$appName\"", false)
            
            // Get the package manager
            val packageManager = applicationContext.packageManager
            
            // Get a list of installed apps
            val installedApps = packageManager.getInstalledApplications(0)
            
            // Find the app by name
            val app = installedApps.find { 
                it.loadLabel(packageManager).toString().equals(appName, ignoreCase = true) ||
                it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true)
            }
            
            if (app != null) {
                Log.d(TAG, "Found app package: ${app.packageName}")
                showToast("App-Paket gefunden: ${app.packageName}", false)
                
                // Get the launch intent for the app
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                
                if (launchIntent != null) {
                    // Add flags to start as a new task
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                    // Start the app
                    applicationContext.startActivity(launchIntent)
                    
                    Log.d(TAG, "Successfully launched app via intent: $appName")
                    showToast("App \"$appName\" erfolgreich über Intent gestartet", false)
                } else {
                    Log.e(TAG, "No launch intent found for app: $appName")
                    showToast("Kein Start-Intent für App \"$appName\" gefunden", true)
                }
            } else {
                Log.e(TAG, "Could not find app package for: $appName")
                showToast("App-Paket für \"$appName\" nicht gefunden", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app via intent: ${e.message}")
            showToast("Fehler beim Starten der App über Intent: ${e.message}", true)
        }
    }
    
    /**
     * Find a node by partial text match recursively
     */
    private fun findNodeByPartialText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Check if this node has text that contains the search text
        if (node.text != null && node.text.toString().contains(text, ignoreCase = true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Check all child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByPartialText(child, text)
            child.recycle()
            
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    // Include all existing methods from the original service...
    
    /**
     * Find and click a button with the specified text
     */
    fun findAndClickButtonByText(buttonText: String) {
        // Implementation from original service
    }
    
    /**
     * Try alternative click methods if the standard click fails
     */
    private fun tryAlternativeClickMethods(node: AccessibilityNodeInfo, buttonText: String) {
        // Implementation from original service
    }
    
    /**
     * Find and click a button with the specified content description
     */
    private fun findAndClickButtonByContentDescription(description: String) {
        // Implementation from original service
    }
    
    /**
     * Find and click a button with the specified class name
     */
    private fun findAndClickButtonByClassName(className: String) {
        // Implementation from original service
    }
    
    /**
     * Find a node by text recursively
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Implementation from original service
    }
    
    /**
     * Find a node by content description recursively
     */
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        // Implementation from original service
    }
    
    /**
     * Find a node by class name recursively
     */
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        // Implementation from original service
    }
    
    /**
     * Perform a click on the specified node
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        // Implementation from original service
    }
    
    /**
     * Tap at the specified coordinates
     */
    fun tapAtCoordinates(x: Float, y: Float) {
        // Implementation from original service
    }
    
    /**
     * Tap at the specified coordinates with a longer duration
     */
    private fun tapAtCoordinatesWithLongerDuration(x: Float, y: Float) {
        // Implementation from original service
    }
    
    /**
     * Take a screenshot by simulating hardware button presses
     */
    fun takeScreenshot() {
        // Implementation from original service
    }
    
    /**
     * Simulate pressing Power + Volume Down buttons to take a screenshot
     */
    private fun simulateScreenshotButtonCombination() {
        // Implementation from original service
    }
    
    /**
     * Perform a key press
     */
    private fun performKeyPress(keyCode: Int): Boolean {
        // Implementation from original service
    }
    
    /**
     * Retrieve the latest screenshot from the standard screenshot folder
     */
    private fun retrieveLatestScreenshot() {
        // Implementation from original service
    }
    
    /**
     * Find the latest screenshot file in standard locations
     */
    private fun findLatestScreenshotFile(): File? {
        // Implementation from original service
    }
    
    /**
     * Find the latest screenshot using MediaStore
     */
    private fun findLatestScreenshotViaMediaStore(): File? {
        // Implementation from original service
    }
    
    /**
     * Add the screenshot to the conversation
     */
    private fun addScreenshotToConversation(screenshotUri: Uri) {
        // Implementation from original service
    }
    
    /**
     * Show a toast message
     */
    private fun showToast(message: String, isError: Boolean) {
        // Implementation from original service
    }
}
