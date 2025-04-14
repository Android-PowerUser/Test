package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel
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
        private var instance: ScreenOperatorAccessibilityService? = null
        private val isServiceAvailable = AtomicBoolean(false)
        
        /**
         * Check if the accessibility service is enabled
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val accessibilityEnabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(TAG, "Error finding setting, default accessibility to not found: ${e.message}")
                return false
            }
            
            if (accessibilityEnabled != 1) {
                Log.v(TAG, "Accessibility is disabled")
                return false
            }
            
            val serviceString = "${context.packageName}/${ScreenOperatorAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            return enabledServices.split(':').any { it.equals(serviceString, ignoreCase = true) }
        }
        
        /**
         * Open accessibility settings
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        
        /**
         * Check if the service instance is available
         */
        fun isServiceAvailable(): Boolean {
            return isServiceAvailable.get() && instance != null
        }
        
        /**
         * Execute a command
         */
        fun executeCommand(command: Command) {
            if (!isServiceAvailable()) {
                Log.e(TAG, "Service is not available")
                return
            }
            
            val service = instance ?: return
            
            when (command) {
                is Command.ClickButton -> service.clickButton(command.buttonText)
                is Command.TapCoordinates -> service.tapCoordinates(command.x, command.y)
                is Command.TakeScreenshot -> service.takeScreenshot()
                is Command.OpenApp -> service.openApp(command.appName)
                is Command.PressBack -> service.pressBack()
                is Command.PressHome -> service.pressHome()
                is Command.PullStatusBarDown -> service.pullStatusBarDown()
                is Command.PullStatusBarDownTwice -> service.pullStatusBarDownTwice()
                is Command.PushStatusBarUp -> service.pushStatusBarUp()
                is Command.ScrollDown -> service.scrollDown()
                is Command.ScrollUp -> service.scrollUp()
                is Command.ScrollLeft -> service.scrollLeft()
                is Command.ScrollRight -> service.scrollRight()
                is Command.ShowRecentApps -> service.showRecentApps()
            }
        }
    }
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        
        instance = this
        isServiceAvailable.set(true)
        
        // Get display metrics
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.let {
                Point(it.bounds.width(), it.bounds.height())
            }
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getSize(point)
            point
        }
        
        display?.let {
            displayWidth = it.x
            displayHeight = it.y
        } ?: run {
            // Fallback values if display is null
            displayWidth = 1080
            displayHeight = 1920
        }
        
        // Show toast
        showToast("Screen Operator Service aktiviert", false)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Not used in this implementation
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        instance = null
        isServiceAvailable.set(false)
        
        // Remove overlay if it exists
        removeOverlay()
    }
    
    /**
     * Click a button with the given text
     */
    private fun clickButton(buttonText: String) {
        Log.d(TAG, "Clicking button: $buttonText")
        
        try {
            // Get the root node
            val rootNode = rootInActiveWindow ?: return
            
            // Find the button node
            val buttonNode = findButtonNode(rootNode, buttonText)
            
            if (buttonNode != null) {
                // Get the button bounds
                val rect = Rect()
                buttonNode.getBoundsInScreen(rect)
                
                // Click the center of the button
                val x = rect.centerX().toFloat()
                val y = rect.centerY().toFloat()
                
                // Perform the click
                tapCoordinates(x, y)
                
                // Show toast
                showToast("Button \"$buttonText\" geklickt", false)
                
                // Recycle the node
                buttonNode.recycle()
            } else {
                Log.e(TAG, "Button not found: $buttonText")
                showToast("Button \"$buttonText\" nicht gefunden", true)
            }
            
            // Recycle the root node
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking button: ${e.message}")
            showToast("Fehler beim Klicken auf Button: ${e.message}", true)
        }
    }
    
    /**
     * Find a button node with the given text
     */
    private fun findButtonNode(rootNode: AccessibilityNodeInfo, buttonText: String): AccessibilityNodeInfo? {
        // Try to find by exact text
        var nodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
        
        if (nodes.isEmpty()) {
            // Try to find by partial text (case insensitive)
            val lowerButtonText = buttonText.lowercase()
            nodes = rootNode.findAccessibilityNodeInfosByText(lowerButtonText)
        }
        
        // Filter for clickable nodes
        val clickableNodes = nodes.filter { it.isClickable }
        
        return if (clickableNodes.isNotEmpty()) {
            clickableNodes.first()
        } else if (nodes.isNotEmpty()) {
            // If no clickable nodes found, return the first node
            nodes.first()
        } else {
            null
        }
    }
    
    /**
     * Tap at the given coordinates
     */
    private fun tapCoordinates(x: Float, y: Float) {
        Log.d(TAG, "Tapping coordinates: ($x, $y)")
        
        try {
            // Create a path for the gesture
            val path = Path()
            path.moveTo(x, y)
            
            // Create a stroke description
            val stroke = StrokeDescription(path, 0, 100)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            // Dispatch the gesture
            dispatchGesture(gestureDescription, null, null)
            
            // Show toast
            showToast("Koordinaten ($x, $y) getippt", false)
            
            // Show tap indicator
            showTapIndicator(x.toInt(), y.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping coordinates: ${e.message}")
            showToast("Fehler beim Tippen auf Koordinaten: ${e.message}", true)
        }
    }
    
    /**
     * Show a tap indicator at the given coordinates
     */
    private fun showTapIndicator(x: Int, y: Int) {
        try {
            // Remove any existing overlay
            removeOverlay()
            
            // Create a new overlay
            val inflater = LayoutInflater.from(this)
            // Erstelle einen einfachen View anstatt R.layout.tap_indicator zu verwenden
            val circleView = View(this)
            circleView.setBackgroundResource(android.R.drawable.radiobutton_on_background)
            overlayView = circleView
            
            // Create layout parameters
            overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            // Set the position
            overlayParams?.gravity = Gravity.TOP or Gravity.START
            overlayParams?.x = x - 50 // Center the 100dp wide indicator
            overlayParams?.y = y - 50 // Center the 100dp tall indicator
            
            // Add the view to the window
            windowManager?.addView(overlayView, overlayParams)
            
            // Remove the overlay after a delay
            mainHandler.postDelayed({
                removeOverlay()
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing tap indicator: ${e.message}")
        }
    }
    
    /**
     * Remove the overlay
     */
    private fun removeOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }
    }
    
    /**
     * Take a screenshot
     */
    private fun takeScreenshot() {
        Log.d(TAG, "Taking screenshot")
        
        try {
            // Create a file to save the screenshot
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val fileName = "Screenshot_$timestamp.png"
            
            val screenshotsDir = File(applicationContext.getExternalFilesDir(null), "Screenshots")
            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs()
            }
            
            val screenshotFile = File(screenshotsDir, fileName)
            
            // Simulate hardware key combination for screenshot
            val success = simulateScreenshotKeyPress()
            
            // Wait for the screenshot to be taken by the system
            mainHandler.postDelayed({
                // Try to find the latest screenshot in the system's screenshot directory
                val latestScreenshot = findLatestScreenshot()
                
                if (latestScreenshot != null) {
                    try {
                        // Copy the screenshot to our app's directory
                        latestScreenshot.copyTo(screenshotFile, overwrite = true)
                        
                        // Get the URI for the file
                        val screenshotUri = FileProvider.getUriForFile(
                            applicationContext,
                            "${applicationContext.packageName}.fileprovider",
                            screenshotFile
                        )
                        
                        // Get screen information
                        val screenInfo = getScreenInfo()
                        
                        // Add the screenshot to the conversation
                        addScreenshotToConversation(screenshotUri, screenInfo)
                        
                        // Show toast
                        showToast("Screenshot aufgenommen", false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot: ${e.message}")
                        showToast("Fehler beim Verarbeiten des Screenshots: ${e.message}", true)
                    }
                } else {
                    Log.e(TAG, "Screenshot not found in system directory")
                    showToast("Screenshot nicht gefunden", true)
                }
            }, 2000) // Wait 2 seconds for the system to save the screenshot
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            showToast("Fehler beim Aufnehmen des Screenshots: ${e.message}", true)
        }
    }
    
    /**
     * Simulate pressing the hardware key combination for screenshot
     */
    private fun simulateScreenshotKeyPress(): Boolean {
        return try {
            // Different devices use different key combinations
            // Try the most common ones: POWER + VOLUME_DOWN or just VOLUME_DOWN + VOLUME_UP
            
            // Method 1: Use AccessibilityService to perform global action (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Try to use the GLOBAL_ACTION_TAKE_SCREENSHOT action if available
                    return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } catch (e: Exception) {
                    Log.e(TAG, "Error using GLOBAL_ACTION_TAKE_SCREENSHOT: ${e.message}")
                }
            }
            
            // Method 2: Use performGlobalAction for key combinations
            // Since we can't directly inject key events in AccessibilityService,
            // we'll use alternative approaches
            
            // Try using a broadcast intent (some devices support this)
            try {
                val intent = Intent("android.intent.action.SCREENSHOT")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.sendBroadcast(intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error sending screenshot broadcast: ${e.message}")
            }
            
            // If all methods failed, return false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating screenshot key press: ${e.message}")
            false
        }
    }
    
    /**
     * Find the latest screenshot in the system's screenshot directory
     */
    private fun findLatestScreenshot(): File? {
        try {
            // Common screenshot directories
            val directories = listOf(
                // Primary external storage
                File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
                File(Environment.getExternalStorageDirectory(), "DCIM/Screenshots"),
                // Samsung
                File(Environment.getExternalStorageDirectory(), "Pictures/ScreenCapture"),
                // Xiaomi
                File(Environment.getExternalStorageDirectory(), "MIUI/Gallery/Screenshots"),
                // Huawei
                File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
                // OnePlus
                File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
                // Google
                File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots")
            )
            
            // Find all screenshot files
            val screenshotFiles = mutableListOf<File>()
            
            for (dir in directories) {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (file.isFile && file.name.contains("screenshot", ignoreCase = true)) {
                                screenshotFiles.add(file)
                            }
                        }
                    }
                }
            }
            
            // Return the most recent file
            return screenshotFiles.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding latest screenshot: ${e.message}")
            return null
        }
    }
    
    /**
     * Get information about the current screen
     */
    private fun getScreenInfo(): String {
        try {
            // Get the root node
            val rootNode = rootInActiveWindow ?: return ""
            
            // Get the package name
            val packageName = rootNode.packageName?.toString() ?: "Unknown"
            
            // Get the class name
            val className = rootNode.className?.toString() ?: "Unknown"
            
            // Get the window title (safely)
            val windowTitle = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    rootNode.windowTitle?.toString() ?: "Unknown"
                } else {
                    "Unknown"
                }
            } catch (e: Exception) {
                "Unknown"
            }
            
            // Get the content description
            val contentDescription = rootNode.contentDescription?.toString() ?: "None"
            
            // Get the number of child nodes
            val childCount = rootNode.childCount
            
            // Get the clickable elements
            val clickableElements = getClickableElements(rootNode)
            
            // Build the screen info
            val screenInfo = StringBuilder()
            screenInfo.append("App: $packageName\n")
            screenInfo.append("Screen: $className\n")
            screenInfo.append("Title: $windowTitle\n")
            screenInfo.append("Description: $contentDescription\n")
            screenInfo.append("Child Count: $childCount\n\n")
            
            if (clickableElements.isNotEmpty()) {
                screenInfo.append("Clickable Elements:\n")
                clickableElements.forEachIndexed { index, element ->
                    screenInfo.append("${index + 1}. $element\n")
                }
            } else {
                screenInfo.append("No clickable elements found")
            }
            
            // Recycle the root node
            rootNode.recycle()
            
            return screenInfo.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen info: ${e.message}")
            return "Error getting screen info: ${e.message}"
        }
    }
    
    /**
     * Get a list of clickable elements on the screen
     */
    private fun getClickableElements(rootNode: AccessibilityNodeInfo): List<String> {
        val clickableElements = mutableListOf<String>()
        
        try {
            // Recursively find all clickable elements
            findClickableElements(rootNode, clickableElements)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding clickable elements: ${e.message}")
        }
        
        return clickableElements
    }
    
    /**
     * Recursively find all clickable elements
     */
    private fun findClickableElements(node: AccessibilityNodeInfo, clickableElements: MutableList<String>) {
        try {
            // Check if the node is clickable
            if (node.isClickable) {
                // Get the node text
                val text = node.text?.toString() ?: ""
                
                // Get the node content description
                val contentDescription = node.contentDescription?.toString() ?: ""
                
                // Get the node class name
                val className = node.className?.toString() ?: ""
                
                // Get the node bounds
                val rect = Rect()
                node.getBoundsInScreen(rect)
                
                // Build the element description
                val elementDescription = buildString {
                    if (text.isNotEmpty()) {
                        append("\"$text\"")
                    } else if (contentDescription.isNotEmpty()) {
                        append("\"$contentDescription\"")
                    } else {
                        append(className.substringAfterLast('.'))
                    }
                    
                    append(" at (${rect.centerX()}, ${rect.centerY()})")
                }
                
                // Add the element to the list
                clickableElements.add(elementDescription)
            }
            
            // Recursively check child nodes
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i) ?: continue
                findClickableElements(childNode, clickableElements)
                childNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding clickable elements: ${e.message}")
        }
    }
    
    /**
     * Add a screenshot to the conversation
     */
    private fun addScreenshotToConversation(screenshotUri: Uri, screenInfo: String) {
        try {
            // Get the PhotoReasoningViewModel from MainActivity
            val photoReasoningViewModel = MainActivity.getInstance()?.getPhotoReasoningViewModel()
            
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
     * Open an app with the given name
     */
    private fun openApp(appName: String) {
        Log.d(TAG, "Opening app: $appName")
        
        try {
            // Get the package manager
            val packageManager = applicationContext.packageManager
            
            // Get a list of installed apps
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            // Find the app by name
            val app = installedApps.find { 
                packageManager.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
            }
            
            if (app != null) {
                // Get the launch intent for the app
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                
                if (launchIntent != null) {
                    // Add flags to the intent
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                    // Start the app
                    applicationContext.startActivity(launchIntent)
                    
                    // Show toast
                    showToast("App \"$appName\" geöffnet", false)
                } else {
                    Log.e(TAG, "Launch intent not found for app: $appName")
                    showToast("Launch Intent für App \"$appName\" nicht gefunden", true)
                }
            } else {
                Log.e(TAG, "App not found: $appName")
                showToast("App \"$appName\" nicht gefunden", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}")
            showToast("Fehler beim Öffnen der App: ${e.message}", true)
        }
    }
    
    /**
     * Press the back button
     */
    private fun pressBack() {
        Log.d(TAG, "Pressing back button")
        
        try {
            // Perform global action
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // Show toast
            showToast("Zurück-Taste gedrückt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing back button: ${e.message}")
            showToast("Fehler beim Drücken der Zurück-Taste: ${e.message}", true)
        }
    }
    
    /**
     * Press the home button
     */
    private fun pressHome() {
        Log.d(TAG, "Pressing home button")
        
        try {
            // Perform global action
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // Show toast
            showToast("Home-Taste gedrückt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing home button: ${e.message}")
            showToast("Fehler beim Drücken der Home-Taste: ${e.message}", true)
        }
    }
    
    /**
     * Pull the status bar down
     */
    private fun pullStatusBarDown() {
        Log.d(TAG, "Pulling status bar down")
        
        try {
            // Perform global action
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            
            // Show toast
            showToast("Statusleiste heruntergezogen", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down: ${e.message}")
            showToast("Fehler beim Herunterziehen der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Pull the status bar down twice (for quick settings)
     */
    private fun pullStatusBarDownTwice() {
        Log.d(TAG, "Pulling status bar down twice")
        
        try {
            // Perform global action
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            
            // Show toast
            showToast("Statusleiste zweimal heruntergezogen", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down twice: ${e.message}")
            showToast("Fehler beim zweimaligen Herunterziehen der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Push the status bar up
     */
    private fun pushStatusBarUp() {
        Log.d(TAG, "Pushing status bar up")
        
        try {
            // Press back to close the notification shade
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // Show toast
            showToast("Statusleiste hochgeschoben", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing status bar up: ${e.message}")
            showToast("Fehler beim Hochschieben der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Scroll down
     */
    private fun scrollDown() {
        Log.d(TAG, "Scrolling down")
        
        try {
            // Create a path for the gesture
            val path = Path()
            path.moveTo(displayWidth / 2f, displayHeight * 0.7f)
            path.lineTo(displayWidth / 2f, displayHeight * 0.3f)
            
            // Create a stroke description
            val stroke = StrokeDescription(path, 0, 300)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            // Dispatch the gesture
            dispatchGesture(gestureDescription, null, null)
            
            // Show toast
            showToast("Nach unten gescrollt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down: ${e.message}")
            showToast("Fehler beim Scrollen nach unten: ${e.message}", true)
        }
    }
    
    /**
     * Scroll up
     */
    private fun scrollUp() {
        Log.d(TAG, "Scrolling up")
        
        try {
            // Create a path for the gesture
            val path = Path()
            path.moveTo(displayWidth / 2f, displayHeight * 0.3f)
            path.lineTo(displayWidth / 2f, displayHeight * 0.7f)
            
            // Create a stroke description
            val stroke = StrokeDescription(path, 0, 300)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            // Dispatch the gesture
            dispatchGesture(gestureDescription, null, null)
            
            // Show toast
            showToast("Nach oben gescrollt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up: ${e.message}")
            showToast("Fehler beim Scrollen nach oben: ${e.message}", true)
        }
    }
    
    /**
     * Scroll left
     */
    private fun scrollLeft() {
        Log.d(TAG, "Scrolling left")
        
        try {
            // Create a path for the gesture
            val path = Path()
            path.moveTo(displayWidth * 0.3f, displayHeight / 2f)
            path.lineTo(displayWidth * 0.7f, displayHeight / 2f)
            
            // Create a stroke description
            val stroke = StrokeDescription(path, 0, 300)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            // Dispatch the gesture
            dispatchGesture(gestureDescription, null, null)
            
            // Show toast
            showToast("Nach links gescrollt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left: ${e.message}")
            showToast("Fehler beim Scrollen nach links: ${e.message}", true)
        }
    }
    
    /**
     * Scroll right
     */
    private fun scrollRight() {
        Log.d(TAG, "Scrolling right")
        
        try {
            // Create a path for the gesture
            val path = Path()
            path.moveTo(displayWidth * 0.7f, displayHeight / 2f)
            path.lineTo(displayWidth * 0.3f, displayHeight / 2f)
            
            // Create a stroke description
            val stroke = StrokeDescription(path, 0, 300)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            // Dispatch the gesture
            dispatchGesture(gestureDescription, null, null)
            
            // Show toast
            showToast("Nach rechts gescrollt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right: ${e.message}")
            showToast("Fehler beim Scrollen nach rechts: ${e.message}", true)
        }
    }
    
    /**
     * Show recent apps
     */
    private fun showRecentApps() {
        Log.d(TAG, "Showing recent apps")
        
        try {
            // Perform global action
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            
            // Show toast
            showToast("Letzte Apps angezeigt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recent apps: ${e.message}")
            showToast("Fehler beim Anzeigen der letzten Apps: ${e.message}", true)
        }
    }
    
    /**
     * Show a toast message
     */
    private fun showToast(message: String, isError: Boolean) {
        mainHandler.post {
            try {
                Toast.makeText(
                    applicationContext,
                    message,
                    if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing toast: ${e.message}")
            }
        }
    }
}
