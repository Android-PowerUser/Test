package com.google.ai.sample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ScreenOperatorAccessibilityService : AccessibilityService() {
    private val TAG = "ScreenOperatorService"
    
    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Window manager for overlay
    private var windowManager: WindowManager? = null
    
    // Overlay view
    private var overlayView: View? = null
    
    // Overlay parameters
    private var overlayParams: WindowManager.LayoutParams? = null
    
    // Display metrics
    private var displayMetrics: DisplayMetrics? = null
    
    // Screen width and height
    private var screenWidth = 0
    private var screenHeight = 0
    
    // Static instance for access from other components
    companion object {
        private var instance: ScreenOperatorAccessibilityService? = null
        
        /**
         * Check if the service is available
         */
        fun isServiceAvailable(): Boolean {
            return instance != null
        }
        
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
                0
            }
            
            if (accessibilityEnabled == 1) {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                
                return enabledServices.contains(context.packageName + "/" + ScreenOperatorAccessibilityService::class.java.name)
            }
            
            return false
        }
        
        /**
         * Execute a command
         */
        fun executeCommand(command: Command) {
            if (instance == null) {
                Log.e("ScreenOperatorService", "Service instance is null, cannot execute command")
                return
            }
            
            when (command) {
                is Command.ClickButton -> instance?.clickButton(command.buttonText)
                is Command.TapCoordinates -> instance?.tapCoordinates(command.x, command.y)
                is Command.TakeScreenshot -> instance?.takeScreenshot()
                is Command.OpenApp -> instance?.openApp(command.appName)
                is Command.PressBack -> instance?.pressBack()
                is Command.PressHome -> instance?.pressHome()
                is Command.PullStatusBarDown -> instance?.pullStatusBarDown()
                is Command.PullStatusBarDownTwice -> instance?.pullStatusBarDownTwice()
                is Command.PushStatusBarUp -> instance?.pushStatusBarUp()
                is Command.ScrollDown -> instance?.scrollDown()
                is Command.ScrollUp -> instance?.scrollUp()
                is Command.ScrollLeft -> instance?.scrollLeft()
                is Command.ScrollRight -> instance?.scrollRight()
                is Command.ShowRecentApps -> instance?.showRecentApps()
            }
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.d(TAG, "Service connected")
        
        // Store the instance
        instance = this
        
        // Initialize window manager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Initialize display metrics
        displayMetrics = resources.displayMetrics
        
        // Get screen dimensions
        if (displayMetrics != null) {
            screenWidth = displayMetrics!!.widthPixels
            screenHeight = displayMetrics!!.heightPixels
        } else {
            // Fallback values if display metrics are not available
            screenWidth = 1080
            screenHeight = 1920
        }
        
        Log.d(TAG, "Screen dimensions: $screenWidth x $screenHeight")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used in this implementation
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        Log.d(TAG, "Service destroyed")
        
        // Clear the instance
        instance = null
    }
    
    /**
     * Show a toast message
     */
    private fun showToast(message: String, isError: Boolean) {
        mainHandler.post {
            try {
                val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
                toast.show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing toast: ${e.message}")
            }
        }
    }
    
    /**
     * Click a button with the given text
     */
    private fun clickButton(buttonText: String) {
        Log.d(TAG, "Clicking button: $buttonText")
        
        try {
            // Get the root node
            val rootNode = rootInActiveWindow ?: return
            
            // Find nodes with the given text
            val nodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
            
            if (nodes.isEmpty()) {
                Log.e(TAG, "No button found with text: $buttonText")
                showToast("Kein Button mit Text \"$buttonText\" gefunden", true)
                rootNode.recycle()
                return
            }
            
            // Find the first clickable node
            var clickableNode: AccessibilityNodeInfo? = null
            
            for (node in nodes) {
                if (node.isClickable) {
                    clickableNode = node
                    break
                }
                
                // Check if any parent is clickable
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        clickableNode = parent
                        break
                    }
                    
                    val temp = parent.parent
                    parent.recycle()
                    parent = temp
                }
                
                if (clickableNode != null) {
                    break
                }
            }
            
            if (clickableNode == null) {
                Log.e(TAG, "No clickable node found for button: $buttonText")
                showToast("Kein klickbarer Button mit Text \"$buttonText\" gefunden", true)
                rootNode.recycle()
                return
            }
            
            // Get the node bounds
            val rect = Rect()
            clickableNode.getBoundsInScreen(rect)
            
            // Click the node
            val result = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            if (result) {
                Log.d(TAG, "Button clicked: $buttonText")
                showToast("Button \"$buttonText\" geklickt", false)
                
                // Show tap indicator
                showTapIndicator(rect.centerX(), rect.centerY())
            } else {
                Log.e(TAG, "Failed to click button: $buttonText")
                showToast("Fehler beim Klicken auf Button \"$buttonText\"", true)
                
                // Try tapping the coordinates as a fallback
                tapCoordinates(rect.centerX(), rect.centerY())
            }
            
            // Recycle nodes
            clickableNode.recycle()
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking button: ${e.message}")
            showToast("Fehler beim Klicken auf Button \"$buttonText\": ${e.message}", true)
        }
    }
    
    /**
     * Tap at the given coordinates
     */
    private fun tapCoordinates(x: Int, y: Int) {
        Log.d(TAG, "Tapping coordinates: ($x, $y)")
        
        try {
            // Ensure coordinates are within screen bounds
            val boundedX = max(0, min(x, screenWidth))
            val boundedY = max(0, min(y, screenHeight))
            
            // Create a path for the gesture
            val path = Path()
            path.moveTo(boundedX.toFloat(), boundedY.toFloat())
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            // Dispatch the gesture
            val result = dispatchGesture(gestureDescription, null, null)
            
            if (result) {
                Log.d(TAG, "Coordinates tapped: ($boundedX, $boundedY)")
                showToast("Koordinaten ($boundedX, $boundedY) getippt", false)
                
                // Show tap indicator
                showTapIndicator(boundedX, boundedY)
            } else {
                Log.e(TAG, "Failed to tap coordinates: ($boundedX, $boundedY)")
                showToast("Fehler beim Tippen auf Koordinaten ($boundedX, $boundedY)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping coordinates: ${e.message}")
            showToast("Fehler beim Tippen auf Koordinaten ($x, $y): ${e.message}", true)
        }
    }
    
    /**
     * Show a tap indicator at the given coordinates
     */
    private fun showTapIndicator(x: Int, y: Int) {
        try {
            // Remove any existing overlay
            removeOverlay()
            
            // Create a new overlay view
            overlayView = FrameLayout(applicationContext).apply {
                // Create a circular shape
                val shape = ShapeDrawable(OvalShape())
                shape.paint.color = Color.RED
                shape.alpha = 128
                
                // Set the background
                background = shape
                
                // Set the size
                layoutParams = FrameLayout.LayoutParams(100, 100)
            }
            
            // Create overlay parameters
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
                        
                        // Get screen information
                        val screenInfo = getScreenInfo()
                        
                        // Try to use direct bitmap approach first
                        try {
                            // Load the bitmap directly
                            val bitmap = MediaStore.Images.Media.getBitmap(
                                applicationContext.contentResolver,
                                Uri.fromFile(latestScreenshot)
                            )
                            
                            // Add the screenshot bitmap directly to the conversation
                            addScreenshotToConversationWithBitmap(bitmap, screenInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading bitmap directly, falling back to URI: ${e.message}")
                            
                            // Fallback to URI approach
                            try {
                                // Get the URI for the file using content URI instead of FileProvider
                                val screenshotUri = Uri.fromFile(screenshotFile)
                                
                                // Add the screenshot to the conversation
                                addScreenshotToConversation(screenshotUri, screenInfo)
                            } catch (e2: Exception) {
                                Log.e(TAG, "Error with URI fallback: ${e2.message}")
                                throw e2
                            }
                        }
                        
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
            }, 1000) // Wait 1 second for the system to save the screenshot
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
            
            // Method 2: Use a broadcast intent (some devices support this)
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
                            if (file.isFile && (file.name.contains("screenshot", ignoreCase = true) || 
                                               file.name.startsWith("Screenshot_", ignoreCase = true))) {
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
     * Add a screenshot bitmap directly to the conversation
     */
    private fun addScreenshotToConversationWithBitmap(screenshot: Bitmap, screenInfo: String) {
        try {
            // Get the PhotoReasoningViewModel from MainActivity
            val photoReasoningViewModel = MainActivity.getInstance()?.getPhotoReasoningViewModel()
            
            if (photoReasoningViewModel == null) {
                Log.e(TAG, "PhotoReasoningViewModel is null, cannot add screenshot to conversation")
                showToast("Fehler: PhotoReasoningViewModel ist nicht verfügbar", true)
                return
            }
            
            // Add the screenshot bitmap to the conversation with screen information
            photoReasoningViewModel.addScreenshotToConversation(screenshot, screenInfo)
            
            Log.d(TAG, "Screenshot bitmap added to conversation")
            showToast("Screenshot zur Konversation hinzugefügt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding screenshot bitmap to conversation: ${e.message}")
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
                    showToast("Fehler: Launch Intent für App \"$appName\" nicht gefunden", true)
                }
            } else {
                Log.e(TAG, "App not found: $appName")
                showToast("App \"$appName\" nicht gefunden", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}")
            showToast("Fehler beim Öffnen der App \"$appName\": ${e.message}", true)
        }
    }
    
    /**
     * Press the back button
     */
    private fun pressBack() {
        Log.d(TAG, "Pressing back button")
        
        try {
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
            
            if (result) {
                Log.d(TAG, "Back button pressed")
                showToast("Zurück-Taste gedrückt", false)
            } else {
                Log.e(TAG, "Failed to press back button")
                showToast("Fehler beim Drücken der Zurück-Taste", true)
            }
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
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
            
            if (result) {
                Log.d(TAG, "Home button pressed")
                showToast("Home-Taste gedrückt", false)
            } else {
                Log.e(TAG, "Failed to press home button")
                showToast("Fehler beim Drücken der Home-Taste", true)
            }
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
            val result = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            
            if (result) {
                Log.d(TAG, "Status bar pulled down")
                showToast("Statusleiste heruntergezogen", false)
            } else {
                Log.e(TAG, "Failed to pull status bar down")
                showToast("Fehler beim Herunterziehen der Statusleiste", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling status bar down: ${e.message}")
            showToast("Fehler beim Herunterziehen der Statusleiste: ${e.message}", true)
        }
    }
    
    /**
     * Pull the status bar down twice
     */
    private fun pullStatusBarDownTwice() {
        Log.d(TAG, "Pulling status bar down twice")
        
        try {
            // Pull down once
            val result1 = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            
            if (result1) {
                // Wait a moment
                Thread.sleep(500)
                
                // Pull down again
                val result2 = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                
                if (result2) {
                    Log.d(TAG, "Status bar pulled down twice")
                    showToast("Statusleiste zweimal heruntergezogen", false)
                } else {
                    Log.e(TAG, "Failed to pull status bar down second time")
                    showToast("Fehler beim zweiten Herunterziehen der Statusleiste", true)
                }
            } else {
                Log.e(TAG, "Failed to pull status bar down first time")
                showToast("Fehler beim ersten Herunterziehen der Statusleiste", true)
            }
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
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
            
            if (result) {
                Log.d(TAG, "Status bar pushed up")
                showToast("Statusleiste hochgeschoben", false)
            } else {
                Log.e(TAG, "Failed to push status bar up")
                showToast("Fehler beim Hochschieben der Statusleiste", true)
            }
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
            path.moveTo(screenWidth / 2f, screenHeight * 0.7f)
            path.lineTo(screenWidth / 2f, screenHeight * 0.3f)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            
            // Dispatch the gesture
            val result = dispatchGesture(gestureDescription, null, null)
            
            if (result) {
                Log.d(TAG, "Scrolled down")
                showToast("Nach unten gescrollt", false)
            } else {
                Log.e(TAG, "Failed to scroll down")
                showToast("Fehler beim Scrollen nach unten", true)
            }
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
            path.moveTo(screenWidth / 2f, screenHeight * 0.3f)
            path.lineTo(screenWidth / 2f, screenHeight * 0.7f)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            
            // Dispatch the gesture
            val result = dispatchGesture(gestureDescription, null, null)
            
            if (result) {
                Log.d(TAG, "Scrolled up")
                showToast("Nach oben gescrollt", false)
            } else {
                Log.e(TAG, "Failed to scroll up")
                showToast("Fehler beim Scrollen nach oben", true)
            }
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
            path.moveTo(screenWidth * 0.3f, screenHeight / 2f)
            path.lineTo(screenWidth * 0.7f, screenHeight / 2f)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            
            // Dispatch the gesture
            val result = dispatchGesture(gestureDescription, null, null)
            
            if (result) {
                Log.d(TAG, "Scrolled left")
                showToast("Nach links gescrollt", false)
            } else {
                Log.e(TAG, "Failed to scroll left")
                showToast("Fehler beim Scrollen nach links", true)
            }
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
            path.moveTo(screenWidth * 0.7f, screenHeight / 2f)
            path.lineTo(screenWidth * 0.3f, screenHeight / 2f)
            
            // Create a gesture description
            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            
            // Dispatch the gesture
            val result = dispatchGesture(gestureDescription, null, null)
            
            if (result) {
                Log.d(TAG, "Scrolled right")
                showToast("Nach rechts gescrollt", false)
            } else {
                Log.e(TAG, "Failed to scroll right")
                showToast("Fehler beim Scrollen nach rechts", true)
            }
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
            val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
            
            if (result) {
                Log.d(TAG, "Recent apps shown")
                showToast("Letzte Apps angezeigt", false)
            } else {
                Log.e(TAG, "Failed to show recent apps")
                showToast("Fehler beim Anzeigen der letzten Apps", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recent apps: ${e.message}")
            showToast("Fehler beim Anzeigen der letzten Apps: ${e.message}", true)
        }
    }
}
