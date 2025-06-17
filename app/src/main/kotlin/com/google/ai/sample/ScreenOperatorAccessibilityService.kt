package com.google.ai.sample

import com.google.ai.sample.MainActivity // Added import
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import com.google.ai.sample.util.AppNamePackageMapper
import com.google.ai.sample.util.Command
import java.io.File
import java.text.SimpleDateFormat
import com.google.ai.sample.GenerativeViewModelFactory
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.NumberFormatException
import java.util.LinkedList

class ScreenOperatorAccessibilityService : AccessibilityService() {
    private val commandQueue = LinkedList<Command>()
    private val isProcessingQueue = AtomicBoolean(false)
    // private val handler = Handler(Looper.getMainLooper()) // Already exists at the class level

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
            Log.d(TAG, "Queueing command: $command")

            if (!isServiceAvailable()) { // isServiceAvailable() is a static fun in companion
                Log.e(TAG, "Service is not available, cannot queue command")
                // Use Companion's mainHandler to post UI updates from static context
                mainHandler.post {
                    val mainActivity = MainActivity.getInstance()
                    if (mainActivity != null) {
                        mainActivity.updateStatusMessage("Accessibility Service is not available. Please enable the service in settings.", true)
                    } else {
                        Log.e(TAG, "MainActivity instance is null, cannot show toast for service unavailable status.")
                    }
                }
                return
            }

            // serviceInstance is the static reference to the service
            serviceInstance!!.commandQueue.add(command)
            Log.d(TAG, "Command $command added to queue. Queue size: ${serviceInstance!!.commandQueue.size}")

            // Ensure processCommandQueue is called on the service's handler thread
            serviceInstance!!.handler.post {
                serviceInstance!!.processCommandQueue()
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
    private val handler = Handler(Looper.getMainLooper()) // Instance handler

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
        showToast("Accessibility Service is enabled and connected", false)
    }

    private fun scheduleNextCommandProcessing() {
        val nextCommandDelay = if (commandQueue.peek() is Command.TakeScreenshot) {
            Log.d(TAG, "Next command in queue is TakeScreenshot, scheduling with 50ms delay.")
            50L
        } else {
            500L
        }

        handler.postDelayed({
            isProcessingQueue.set(false) // Release the lock before the next cycle
            processCommandQueue()        // Try to process the next command
        }, nextCommandDelay)
    }

    private fun executeSingleCommand(command: Command): Boolean {
        val displayMetrics = this.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Execute the command
        return when (command) {
            is Command.ClickButton -> {
                Log.d(TAG, "Clicking button with text: ${command.buttonText}")
                this.showToast("Trying to click button: \"${command.buttonText}\"", false)
                this.findAndClickButtonByText(command.buttonText)
                true // Asynchronous
            }
            is Command.TapCoordinates -> {
                val xPx = this.convertCoordinate(command.x, screenWidth)
                val yPx = this.convertCoordinate(command.y, screenHeight)
                Log.d(TAG, "Tapping at coordinates: (${command.x} -> $xPx, ${command.y} -> $yPx)")
                this.showToast("Trying to tap coordinates: ($xPx, $yPx)", false)
                this.tapAtCoordinates(xPx, yPx)
                true // Asynchronous
            }
            is Command.TakeScreenshot -> {
                Log.d(TAG, "Command.TakeScreenshot: Capturing screen info and sending request broadcast to MainActivity.")
                this.showToast("Preparing screenshot...", false) // Updated toast message

                val screenInfo = captureScreenInformation() // Capture fresh screen info

                val intent = Intent(MainActivity.ACTION_REQUEST_MEDIAPROJECTION_SCREENSHOT).apply {
                    putExtra(MainActivity.EXTRA_SCREEN_INFO, screenInfo)
                    // Set package to ensure only our app's receiver gets it
                    `package` = applicationContext.packageName
                }
                applicationContext.sendBroadcast(intent)
                Log.d(TAG, "Sent broadcast ACTION_REQUEST_MEDIAPROJECTION_SCREENSHOT to MainActivity with screenInfo.")

                // The command is considered "handled" once the broadcast is sent.
                // MainActivity and ScreenCaptureService will handle the rest asynchronously.
                // Return false to allow the command queue to proceed immediately.
                false
            }
            is Command.PressHomeButton -> {
                Log.d(TAG, "Pressing home button")
                this.showToast("Trying to press Home button", false)
                this.pressHomeButton()
                false // Synchronous
            }
            is Command.PressBackButton -> {
                Log.d(TAG, "Pressing back button")
                this.showToast("Trying to press Back button", false)
                this.pressBackButton()
                false // Synchronous
            }
            is Command.ShowRecentApps -> {
                Log.d(TAG, "Showing recent apps")
                this.showToast("Trying to open recent apps overview", false)
                this.showRecentApps()
                false // Synchronous
            }
            is Command.ScrollDown -> {
                Log.d(TAG, "Scrolling down")
                this.showToast("Trying to scroll down", false)
                this.scrollDown()
                true // Asynchronous
            }
            is Command.ScrollUp -> {
                Log.d(TAG, "Scrolling up")
                this.showToast("Trying to scroll up", false)
                this.scrollUp()
                true // Asynchronous
            }
            is Command.ScrollLeft -> {
                Log.d(TAG, "Scrolling left")
                this.showToast("Trying to scroll left", false)
                this.scrollLeft()
                true // Asynchronous
            }
            is Command.ScrollRight -> {
                Log.d(TAG, "Scrolling right")
                this.showToast("Trying to scroll right", false)
                this.scrollRight()
                true // Asynchronous
            }
            is Command.ScrollDownFromCoordinates -> {
                Log.d(TAG, "ScrollDownFromCoordinates: Original inputs x='${command.x}', y='${command.y}', distance='${command.distance}', duration='${command.duration}'")
                val xPx = this.convertCoordinate(command.x, screenWidth)
                val yPx = this.convertCoordinate(command.y, screenHeight)
                val distancePx = this.convertCoordinate(command.distance, screenHeight)
                this.showToast("Trying to scroll down from position ($xPx, $yPx)", false)
                this.scrollDown(xPx, yPx, distancePx, command.duration)
                true // Asynchronous
            }
            is Command.ScrollUpFromCoordinates -> {
                Log.d(TAG, "ScrollUpFromCoordinates: Original inputs x='${command.x}', y='${command.y}', distance='${command.distance}', duration='${command.duration}'")
                val xPx = this.convertCoordinate(command.x, screenWidth)
                val yPx = this.convertCoordinate(command.y, screenHeight)
                val distancePx = this.convertCoordinate(command.distance, screenHeight)
                this.showToast("Trying to scroll up from position ($xPx, $yPx)", false)
                this.scrollUp(xPx, yPx, distancePx, command.duration)
                true // Asynchronous
            }
            is Command.ScrollLeftFromCoordinates -> {
                Log.d(TAG, "ScrollLeftFromCoordinates: Original inputs x='${command.x}', y='${command.y}', distance='${command.distance}', duration='${command.duration}'")
                val xPx = this.convertCoordinate(command.x, screenWidth)
                val yPx = this.convertCoordinate(command.y, screenHeight)
                val distancePx = this.convertCoordinate(command.distance, screenWidth)
                this.showToast("Trying to scroll left from position ($xPx, $yPx)", false)
                this.scrollLeft(xPx, yPx, distancePx, command.duration)
                true // Asynchronous
            }
            is Command.ScrollRightFromCoordinates -> {
                Log.d(TAG, "ScrollRightFromCoordinates: Original inputs x='${command.x}', y='${command.y}', distance='${command.distance}', duration='${command.duration}'")
                val xPx = this.convertCoordinate(command.x, screenWidth)
                val yPx = this.convertCoordinate(command.y, screenHeight)
                val distancePx = this.convertCoordinate(command.distance, screenWidth)
                this.showToast("Trying to scroll right from position ($xPx, $yPx)", false)
                this.scrollRight(xPx, yPx, distancePx, command.duration)
                true // Asynchronous
            }
            is Command.OpenApp -> {
                Log.d(TAG, "Opening app: ${command.packageName}")
                this.showToast("Trying to open app: ${command.packageName}", false)
                this.openApp(command.packageName)
                false // Synchronous
            }
            is Command.WriteText -> {
                Log.d(TAG, "Writing text: ${command.text}")
                this.showToast("Trying to write text: \"${command.text}\"", false)
                this.writeText(command.text)
                false // Synchronous for now
            }
            is Command.UseHighReasoningModel -> {
                Log.d(TAG, "Switching to high reasoning model (gemini-2.5-pro-preview-03-25)")
                this.showToast("Switching to more powerful model (gemini-2.5-pro-preview-03-25)", false)
                GenerativeAiViewModelFactory.highReasoningModel()
                false // Synchronous
            }
            is Command.UseLowReasoningModel -> {
                Log.d(TAG, "Switching to low reasoning model (gemini-2.0-flash-lite)")
                this.showToast("Switching to faster model (gemini-2.0-flash-lite)", false)
                GenerativeAiViewModelFactory.lowReasoningModel()
                false // Synchronous
            }
            is Command.PressEnterKey -> {
                Log.d(TAG, "Pressing Enter key")
                this.showToast("Trying to press Enter key", false)
                this.pressEnterKey()
                true // Asynchronous
            }
        }
    }

    private fun processCommandQueue() {
        if (!isProcessingQueue.compareAndSet(false, true)) {
            Log.d(TAG, "Queue is already being processed.")
            return
        }

        if (commandQueue.isEmpty()) {
            Log.d(TAG, "Command queue is empty. Stopping processing.")
            isProcessingQueue.set(false)
            return
        }

        val command = commandQueue.poll()
        Log.d(TAG, "Processing command: $command. Queue size after poll: ${commandQueue.size}")

        if (command != null) {
            val commandWasAsync = executeSingleCommand(command) // executeSingleCommand now returns Boolean
            if (!commandWasAsync) {
                // If the command was synchronous, schedule the next one directly.
                // For async commands, they will call scheduleNextCommandProcessing themselves via callbacks.
                scheduleNextCommandProcessing()
            }
            // If commandWasAsync is true, executeSingleCommand (or the methods it calls)
            // is responsible for calling scheduleNextCommandProcessing upon completion.
        } else {
            Log.d(TAG, "Polled null command from queue, stopping processing.")
            isProcessingQueue.set(false)
        }
    }

    private fun convertCoordinate(coordinateString: String, screenSize: Int): Float {
        return try {
            if (coordinateString.endsWith("%")) {
                val numericValue = coordinateString.removeSuffix("%").toFloat()
                (numericValue / 100.0f) * screenSize
            } else {
                coordinateString.toFloat()
            }
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Error converting coordinate string: '$coordinateString'", e)
            showToast("Error parsing coordinate: '$coordinateString'. Using 0f.", true)
            0f // Default to 0f or handle error as appropriate
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error converting coordinate string: '$coordinateString'", e)
            showToast("Unexpected error parsing coordinate: '$coordinateString'. Using 0f.", true)
            0f // Default to 0f or handle error as appropriate
        }
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
     * Write text into the currently focused text field
     * 
     * @param text The text to write
     */
    fun writeText(text: String) {
        Log.d(TAG, "Writing text: $text")
        showToast("Writing text: \"$text\"", false)
        
        try {
            // Refresh the root node
            refreshRootNode()
            
            // Check if root node is available
            if (rootNode == null) {
                Log.e(TAG, "Root node is null, cannot write text")
                showToast("Error: Root node is not available", true)
                return
            }
            
            // Find the focused node (which should be an editable text field)
            val focusedNode = findFocusedEditableNode(rootNode!!)
            
            if (focusedNode != null) {
                Log.d(TAG, "Found focused editable node")
                showToast("Text field found, writing text: \"$text\"", false)
                
                // Set the text in the editable field
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                
                val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                
                if (result) {
                    Log.d(TAG, "Successfully wrote text: $text")
                    showToast("Text successfully written: \"$text\"", false)
                } else {
                    Log.e(TAG, "Failed to write text, trying alternative methods")
                    showToast("Error writing text, trying alternative methods", true)
                    
                    // Try alternative methods
                    tryAlternativeTextInputMethods(focusedNode, text)
                }
                
                // Recycle the node
                focusedNode.recycle()
            } else {
                Log.e(TAG, "Could not find focused editable node")
                showToast("No focused text field found, trying to find editable fields", true)
                
                // Try to find any editable field
                val editableNode = findFirstEditableNode(rootNode!!)
                
                if (editableNode != null) {
                    Log.d(TAG, "Found editable node")
                    showToast("Editable text field found, trying to focus", false)
                    
                    // Focus the editable field
                    val focusResult = editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    
                    if (focusResult) {
                        Log.d(TAG, "Successfully focused editable node")
                        showToast("Text field successfully focused, writing text: \"$text\"", false)
                        
                        // Set the text in the editable field
                        val bundle = android.os.Bundle()
                        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        
                        val result = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                        
                        if (result) {
                            Log.d(TAG, "Successfully wrote text: $text")
                            showToast("Text successfully written: \"$text\"", false)
                        } else {
                            Log.e(TAG, "Failed to write text, trying alternative methods")
                            showToast("Error writing text, trying alternative methods", true)
                            
                            // Try alternative methods
                            tryAlternativeTextInputMethods(editableNode, text)
                        }
                    } else {
                        Log.e(TAG, "Failed to focus editable node")
                        showToast("Error focusing text field", true)
                    }
                    
                    // Recycle the node
                    editableNode.recycle()
                } else {
                    Log.e(TAG, "Could not find any editable node")
                    showToast("No editable text field found", true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing text: ${e.message}")
            showToast("Error writing text: ${e.message}", true)
        }
    }
    
    /**
     * Find the focused editable node
     */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            // Check if this node is focused and editable
            if (node.isFocused && node.isEditable) {
                return AccessibilityNodeInfo.obtain(node)
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findFocusedEditableNode(child)
                child.recycle()
                
                if (result != null) {
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding focused editable node: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Find the first editable node
     */
    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            // Check if this node is editable
            if (node.isEditable) {
                return AccessibilityNodeInfo.obtain(node)
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findFirstEditableNode(child)
                child.recycle()
                
                if (result != null) {
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding first editable node: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Try alternative methods to input text
     */
    private fun tryAlternativeTextInputMethods(node: AccessibilityNodeInfo, text: String) {
        try {
            Log.d(TAG, "Trying alternative text input methods")
            showToast("Trying alternative text input methods", false)
            
            // Try to paste text
            pasteText(node, text)
        } catch (e: Exception) {
            Log.e(TAG, "Error trying alternative text input methods: ${e.message}")
            showToast("Error with alternative text input methods: ${e.message}", true)
        }
    }
    
    /**
     * Paste text into a node
     */
    private fun pasteText(node: AccessibilityNodeInfo, text: String) {
        try {
            Log.d(TAG, "Trying to paste text: $text")
            showToast("Trying to paste text: \"$text\"", false)
            
            // First, select all existing text
            val selectAllResult = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            
            if (selectAllResult) {
                Log.d(TAG, "Successfully focused text field")
                
                // Add a small delay before pasting
                Handler(Looper.getMainLooper()).postDelayed({
                    // Set the text in the clipboard
                    val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Accessibility Service Text", text)
                    clipboard.setPrimaryClip(clip)
                    
                    // Paste the text
                    val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    
                    if (pasteResult) {
                        Log.d(TAG, "Successfully pasted text: $text")
                        showToast("Text successfully pasted: \"$text\"", false)
                    } else {
                        Log.e(TAG, "Failed to paste text")
                        showToast("Error pasting text", true)
                    }
                }, 200) // 200ms delay
            } else {
                Log.e(TAG, "Failed to select all text")
                showToast("Error selecting existing text", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pasting text: ${e.message}")
            showToast("Error pasting text: ${e.message}", true)
        }
    }
    
    /**
     * Find and click a button with the specified text
     */
    fun findAndClickButtonByText(buttonText: String) {
        Log.d(TAG, "Finding and clicking button with text: $buttonText")
        showToast("Searching for button with text: \"$buttonText\"", false)
        
        // Refresh the root node
        refreshRootNode()
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button")
            showToast("Error: Root node is not available", true)
            scheduleNextCommandProcessing() // Continue queue if rootNode is null
            return
        }
        
        // Try to find the node with the specified text
        val node = findNodeByText(rootNode!!, buttonText)
        
        if (node != null) {
            Log.d(TAG, "Found node with text: $buttonText")
            showToast("Button found: \"$buttonText\"", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button: $buttonText")
                    showToast("Clicked button \"$buttonText\" successfully", false)
                } else {
                    Log.e(TAG, "Failed to click on button: $buttonText")
                    showToast("Failed to click button \"$buttonText\", trying alternative methods", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, buttonText)
                }
                
                // Recycle the node
                node.recycle()
                scheduleNextCommandProcessing()
            }, 200)
        } else {
            Log.e(TAG, "Could not find node with text: $buttonText, trying content description.")
            // findAndClickButtonByContentDescription will call scheduleNextCommandProcessing
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
            showToast("Trying to tap coordinates: ($centerX, $centerY)", false)
            
            // Tap at the center of the button
            tapAtCoordinates(centerX.toFloat(), centerY.toFloat())
        }
    }
    
    /**
     * Find and click a button by content description
     */
    private fun findAndClickButtonByContentDescription(description: String) {
        Log.d(TAG, "Finding and clicking button with content description: $description")
        showToast("Searching for button with description: \"$description\"", false)
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by content description")
            showToast("Error: Root node is not available", true)
            scheduleNextCommandProcessing() // Continue queue if rootNode is null
            return
        }
        
        // Try to find the node with the specified content description
        val node = findNodeByContentDescription(rootNode!!, description)
        
        if (node != null) {
            Log.d(TAG, "Found node with content description: $description")
            showToast("Button found with description: \"$description\"", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button with description: $description")
                    showToast("Clicked button with description \"$description\" successfully", false)
                } else {
                    Log.e(TAG, "Failed to click on button with description: $description")
                    showToast("Failed to click button with description \"$description\", trying alternative methods", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, description)
                }
                
                // Recycle the node
                node.recycle()
                scheduleNextCommandProcessing()
            }, 200)
        } else {
            Log.e(TAG, "Could not find node with content description: $description, trying ID.")
            // findAndClickButtonById will call scheduleNextCommandProcessing
            findAndClickButtonById(description)
        }
    }
    
    /**
     * Find and click a button by ID
     */
    private fun findAndClickButtonById(id: String) {
        Log.d(TAG, "Finding and clicking button with ID: $id")
        showToast("Searching for button with ID: \"$id\"", false)
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by ID")
            showToast("Error: Root node is not available", true)
            scheduleNextCommandProcessing() // Continue queue if rootNode is null
            return
        }
        
        // Try to find the node with the specified ID
        val node = findNodeById(rootNode!!, id)
        
        if (node != null) {
            Log.d(TAG, "Found node with ID: $id")
            showToast("Button found with ID: \"$id\"", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button with ID: $id")
                    showToast("Clicked button with ID \"$id\" successfully", false)
                } else {
                    Log.e(TAG, "Failed to click on button with ID: $id")
                    showToast("Failed to click button with ID \"$id\", trying alternative methods", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, id)
                }
                
                // Recycle the node
                node.recycle()
                scheduleNextCommandProcessing()
            }, 200)
        } else {
            Log.e(TAG, "Could not find node with ID: $id")
            showToast("Button with ID \"$id\" not found", true)
            scheduleNextCommandProcessing() // End of find chain
        }
    }
    
    /**
     * Find a node by text
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            // Check if this node has the specified text
            if (!node.text.isNullOrEmpty() && node.text.toString().contains(text, ignoreCase = true)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findNodeByText(child, text)
                child.recycle()
                
                if (result != null) {
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding node by text: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Find a node by content description
     */
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        try {
            // Check if this node has the specified content description
            if (!node.contentDescription.isNullOrEmpty() && 
                node.contentDescription.toString().contains(description, ignoreCase = true)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findNodeByContentDescription(child, description)
                child.recycle()
                
                if (result != null) {
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding node by content description: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Find a node by ID
     */
    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        try {
            // Check if this node has the specified ID
            val nodeId = getNodeId(node)
            if (nodeId.isNotEmpty() && nodeId.contains(id, ignoreCase = true)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findNodeById(child, id)
                child.recycle()
                
                if (result != null) {
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding node by ID: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Get the ID of a node
     */
    private fun getNodeId(node: AccessibilityNodeInfo): String {
        try {
            // Get the view ID resource name
            val viewIdResourceName = node.viewIdResourceName ?: ""
            
            // Extract the ID part (after the slash)
            val parts = viewIdResourceName.split("/")
            if (parts.size > 1) {
                return parts[1]
            }
            
            return viewIdResourceName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting node ID: ${e.message}")
            return ""
        }
    }
    
    /**
     * Find all interactive elements on the screen
     */
    private fun findAllInteractiveElements(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // Check if this node is interactive
            if (isNodeInteractive(node)) {
                elements.add(AccessibilityNodeInfo.obtain(node))
            }
            
            // Check children recursively
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
     * Check if a node is interactive
     */
    private fun isNodeInteractive(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || 
               node.isLongClickable || 
               node.isCheckable || 
               node.isEditable || 
               node.isFocusable
    }
    
    /**
     * Perform a click on a node
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
        showToast("Tapping at coordinates: ($x, $y)", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesture API is not available on this Android version", true)
            scheduleNextCommandProcessing() // Continue queue if API not available
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
                    showToast("Tapped coordinates ($x, $y) successfully", false)
                    scheduleNextCommandProcessing()
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Tap gesture cancelled")
                    showToast("Tap at coordinates ($x, $y) cancelled, trying longer duration", true)
                    // Try with longer duration, which will then call scheduleNextCommandProcessing
                    tapAtCoordinatesWithLongerDuration(x, y)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch tap gesture")
                showToast("Error dispatching tap gesture, trying longer duration", true)
                // Try with longer duration, which will then call scheduleNextCommandProcessing
                tapAtCoordinatesWithLongerDuration(x, y)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates: ${e.message}")
            showToast("Error tapping at coordinates: ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Tap at the specified coordinates with a longer duration
     */
    private fun tapAtCoordinatesWithLongerDuration(x: Float, y: Float) {
        Log.d(TAG, "Tapping at coordinates with longer duration: ($x, $y)")
        showToast("Trying to tap with longer duration at: ($x, $y)", false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API is not available on this Android version")
            showToast("Gesture API is not available on this Android version", true)
            scheduleNextCommandProcessing() // Continue queue
            return
        }
        
        try {
            // Create a tap gesture with longer duration
            val path = Path()
            path.moveTo(x, y)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)) // 300ms duration
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Long tap gesture completed")
                    showToast("Tapped with longer duration at coordinates ($x, $y) successfully", false)
                    scheduleNextCommandProcessing()
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Long tap gesture cancelled")
                    showToast("Tap with longer duration at coordinates ($x, $y) cancelled", true)
                    scheduleNextCommandProcessing()
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch long tap gesture")
                showToast("Error dispatching tap gesture with longer duration", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates with longer duration: ${e.message}")
            showToast("Error tapping with longer duration at coordinates: ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }

	   /**
 * Press the Enter key
 */
fun pressEnterKey() {
    Log.d(TAG, "Pressing Enter key")
    try {
        // Get display metrics to calculate screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Calculate tap position at 95% of width and 95% of height
        val x = (screenWidth * 0.95f).toInt()
        val y = (screenHeight * 0.96f).toInt()
        
        // Create gesture builder
        val gestureBuilder = GestureDescription.Builder()
        val clickPath = Path()
        
        // Add tap path (down and up at the same position)
        clickPath.moveTo(x.toFloat(), y.toFloat())
        
        // Set gesture stroke - duration 100ms for a quick tap
        val clickStroke = GestureDescription.StrokeDescription(clickPath, 0, 100)
        gestureBuilder.addStroke(clickStroke)
        
        // Dispatch the gesture
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Enter key tap gesture completed")
                showToast("Enter key pressed successfully", false)
                scheduleNextCommandProcessing() // Continue queue after completion
            }
            
            override fun onCancelled(gestureDescription: GestureDescription) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Enter key tap gesture cancelled")
                showToast("Enter key gesture cancelled", true)
                scheduleNextCommandProcessing() // Continue queue even if cancelled
            }
        }, null)

        if (!result) {
            Log.e(TAG, "Failed to dispatch Enter key tap gesture")
            showToast("Error pressing Enter key", true)
            scheduleNextCommandProcessing() // Continue queue if dispatch failed immediately
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error pressing Enter key: ${e.message}")
        showToast("Error pressing Enter key: ${e.message}", true)
        scheduleNextCommandProcessing()
    }
}
    
    /**
     * Open an app by package name
     */
    fun openApp(packageName: String) {
        Log.d(TAG, "Opening app with package name: $packageName")
        
        try {
            // Get the app name from the package name
            val appName = packageName
            
            // Try different methods to open the app
            if (openAppUsingLaunchIntent(packageName, appName)) {
                // Successfully opened app
            } else if (openAppUsingMainActivity(packageName, appName)) {
                // Successfully opened app
            } else if (openAppUsingQueryIntentActivities(packageName, appName)) {
                // Successfully opened app
            } else {
                // If all methods failed, show an error
                Log.e(TAG, "Failed to open app: $packageName")
                showToast("Error opening app: $appName", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}")
            showToast("Error opening app: ${e.message}", true)
        }
    }
    
    /**
     * Try to open an app using the launch intent
     */
    private fun openAppUsingLaunchIntent(packageName: String, appName: String): Boolean {
        try {
            Log.d(TAG, "Trying to open app using launch intent: $packageName")
            
            // Get the package manager
            val packageManager = applicationContext.packageManager
            
            // Try to get the launch intent for the package
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            
            if (launchIntent != null) {
                // Add flags to reuse existing instance if possible
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                
                // Start the activity
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(launchIntent)
                
                Log.d(TAG, "Successfully opened app using launch intent: $packageName")
                showToast("App opened: $appName", false)
                return true
            } else {
                Log.d(TAG, "No launch intent found for package: $packageName, trying alternative methods")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app using launch intent: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Try to open an app by directly starting its main activity
     */
    private fun openAppUsingMainActivity(packageName: String, appName: String): Boolean {
        try {
            Log.d(TAG, "Trying to open app using main activity: $packageName")
            
            // Create an intent with ACTION_MAIN
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            // Get the package manager
            val packageManager = applicationContext.packageManager
            
            // Query for activities that match our intent
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            // Find the main activity for our package
            for (resolveInfo in resolveInfoList) {
                if (resolveInfo.activityInfo.packageName == packageName) {
                    // Found the main activity
                    val className = resolveInfo.activityInfo.name
                    Log.d(TAG, "Found main activity for package $packageName: $className")
                    
                    // Create an intent to launch this specific activity
                    val launchIntent = Intent(Intent.ACTION_MAIN)
                    launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    launchIntent.setClassName(packageName, className)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    
                    // Start the activity
                    applicationContext.startActivity(launchIntent)
                    
                    Log.d(TAG, "Successfully opened app using main activity: $packageName")
                    showToast("App opened: $appName", false)
                    return true
                }
            }
            
            Log.d(TAG, "No main activity found for package: $packageName, trying next method")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app using main activity: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Try to open an app by querying all activities and starting the first one
     */
    private fun openAppUsingQueryIntentActivities(packageName: String, appName: String): Boolean {
        try {
            Log.d(TAG, "Trying to open app using query intent activities: $packageName")
            
            // Create a generic intent
            val intent = Intent()
            intent.setPackage(packageName)
            
            // Get the package manager
            val packageManager = applicationContext.packageManager
            
            // Query for all activities in the package
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            if (resolveInfoList.isNotEmpty()) {
                // Get the first activity
                val resolveInfo = resolveInfoList[0]
                val className = resolveInfo.activityInfo.name
                
                Log.d(TAG, "Found activity for package $packageName: $className")
                
                // Create an intent to launch this specific activity
                val launchIntent = Intent()
                launchIntent.component = ComponentName(packageName, className)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Start the activity
                applicationContext.startActivity(launchIntent)
                
                Log.d(TAG, "Successfully opened app using query intent activities: $packageName")
                showToast("App opened: $appName", false)
                return true
            }
            
            Log.d(TAG, "No activities found for package: $packageName")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app using query intent activities: ${e.message}", e)
            return false
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
            return "No screen information available (root node is null)"
        }
        
        // Build a string with information about all interactive elements
        val screenInfo = StringBuilder()
        screenInfo.append("Screen elements:\n")
        
        // Find all interactive elements
        val elements = findAllInteractiveElements(rootNode!!)
        
        // Add information about each element
        elements.forEachIndexed { index, element ->
            screenInfo.append("${index + 1}. ")
            
            // Add text if available
            if (!element.text.isNullOrEmpty()) {
                screenInfo.append("Text: \"${element.text}\" ")
            }
            
            // Add content description if available
            if (!element.contentDescription.isNullOrEmpty()) {
                screenInfo.append("Description: \"${element.contentDescription}\" ")
            }
            
            // Add element class name if available
            if (element.className != null) {
                screenInfo.append("Class: ${element.className} ")
            }
            
            // Add element properties
            val properties = mutableListOf<String>()
            if (element.isClickable) properties.add("clickable")
            if (element.isLongClickable) properties.add("long-clickable")
            if (element.isCheckable) properties.add("checkable")
            if (element.isChecked) properties.add("checked")
            if (element.isEditable) properties.add("editable")
            if (element.isFocusable) properties.add("focusable")
            if (element.isFocused) properties.add("focused")
            if (element.isPassword) properties.add("password")
            if (element.isScrollable) properties.add("scrollable")
            
            if (properties.isNotEmpty()) {
                screenInfo.append("Properties: ${properties.joinToString(", ")} ")
            }
            
            // Add element bounds
            val rect = Rect()
            element.getBoundsInScreen(rect)
            screenInfo.append("Position: (${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom})")
            
            // Add a button name if we can infer one
            val buttonName = getButtonName(element)
            if (buttonName.isNotEmpty()) {
                screenInfo.append(" Inferred name: \"$buttonName\"")
            }
            
            screenInfo.append("\n")
            
            // Recycle the element
            element.recycle()
        }
        
        return screenInfo.toString()
    }
    
    /**
     * Try to infer a button name from a node
     */
    private fun getButtonName(node: AccessibilityNodeInfo): String {
        try {
            // First, check if the node has text
            if (!node.text.isNullOrEmpty()) {
                return node.text.toString()
            }
            
            // Next, check if the node has a content description
            if (!node.contentDescription.isNullOrEmpty()) {
                return node.contentDescription.toString()
            }
            
            // Next, check if the node has an ID
            val nodeId = getNodeId(node)
            if (nodeId.isNotEmpty()) {
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
                        return "Text field: $hintText"
                    }
                } catch (e: Exception) {
                    // Reflection failed, ignore
                }
                
                return "Text field"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting button name: ${e.message}")
        }
        
        return ""
    }
    
    /**
     * Press the home button
     */
    fun pressHomeButton() {
        Log.d(TAG, "Pressing home button")
        showToast("Pressing Home button...", false)
        
        try {
            // Use the global action to press the home button
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
            
            if (result) {
                Log.d(TAG, "Successfully pressed home button")
                showToast("Home button pressed successfully", false)
            } else {
                Log.e(TAG, "Failed to press home button")
                showToast("Error pressing Home button", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing home button: ${e.message}")
            showToast("Error pressing Home button: ${e.message}", true)
        }
    }
    
    /**
     * Press the back button
     */
    fun pressBackButton() {
        Log.d(TAG, "Pressing back button")
        showToast("Pressing Back button...", false)
        
        try {
            // Use the global action to press the back button
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
            
            if (result) {
                Log.d(TAG, "Successfully pressed back button")
                showToast("Back button pressed successfully", false)
            } else {
                Log.e(TAG, "Failed to press back button")
                showToast("Error pressing Back button", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing back button: ${e.message}")
            showToast("Error pressing Back button: ${e.message}", true)
        }
    }
    
    /**
     * Show the recent apps screen
     */
    fun showRecentApps() {
        Log.d(TAG, "Showing recent apps")
        showToast("Opening recent apps overview...", false)
        
        try {
            // Use the global action to show recent apps
            val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
            
            if (result) {
                Log.d(TAG, "Successfully showed recent apps")
                showToast("Recent apps overview opened successfully", false)
            } else {
                Log.e(TAG, "Failed to show recent apps")
                showToast("Error opening recent apps overview", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recent apps: ${e.message}")
            showToast("Error opening recent apps overview: ${e.message}", true)
        }
    }
    
    /**
     * Scroll down on the screen using gesture
     */
    fun scrollDown() {
        Log.d(TAG, "Scrolling down")
        showToast("Scrolling down...", false)
        
        try {
            // Get display metrics to calculate swipe coordinates
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            // Create a path for the gesture (swipe from middle-bottom to middle-top)
            val swipePath = Path()
            swipePath.moveTo(screenWidth / 2f, screenHeight * 0.7f) // Start from 70% down the screen
            swipePath.lineTo(screenWidth / 2f, screenHeight * 0.3f) // Move to 30% down the screen
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                300 // duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Scroll down gesture completed")
                        showToast("Successfully scrolled down", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Scroll down gesture cancelled")
                        showToast("Scroll down cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll down gesture")
                showToast("Error scrolling down", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down: ${e.message}")
            showToast("Error scrolling down: ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll down from specific coordinates with custom distance and duration
     * 
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param distance Distance in pixels to scroll
     * @param duration Duration of the scroll gesture in milliseconds
     */
    fun scrollDown(x: Float, y: Float, distance: Float, duration: Long) {
        Log.d(TAG, "scrollDown method: Received x=$x, y=$y, distance=$distance, duration=$duration")
        showToast("Scrolling down from specific position...", false)
        
        try {
            // Create a path for the gesture (swipe from specified position upward by the specified distance)
            val swipePath = Path()
            val startX = x
            val startY = y
            val endX = x
            val endY = y - distance
            swipePath.moveTo(startX, startY)
            swipePath.lineTo(endX, endY)
            Log.d(TAG, "scrollDown method: Creating swipePath from ($startX, $startY) to ($endX, $endY) over $duration ms")
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                duration // custom duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "scrollDown method: Gesture completed for path from ($startX, $startY) to ($endX, $endY)")
                        showToast("Successfully scrolled down from position ($startX, $startY)", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "scrollDown method: Gesture CANCELLED for path from ($startX, $startY) to ($endX, $endY). GestureDescription: $gestureDescription")
                        showToast("Scroll down from position ($startX, $startY) cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll down gesture for path from ($startX, $startY) to ($endX, $endY)")
                showToast("Error scrolling down from position ($startX, $startY)", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down from coordinates: ${e.message}")
            showToast("Error scrolling down from position ($x, $y): ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll up on the screen using gesture
     */
    fun scrollUp() {
        Log.d(TAG, "Scrolling up")
        showToast("Scrolling up...", false)
        
        try {
            // Get display metrics to calculate swipe coordinates
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            // Create a path for the gesture (swipe from middle-top to middle-bottom)
            val swipePath = Path()
            swipePath.moveTo(screenWidth / 2f, screenHeight * 0.3f) // Start from 30% down the screen
            swipePath.lineTo(screenWidth / 2f, screenHeight * 0.7f) // Move to 70% down the screen
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                300 // duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Scroll up gesture completed")
                        showToast("Successfully scrolled up", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Scroll up gesture cancelled")
                        showToast("Scroll up cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll up gesture")
                showToast("Error scrolling up", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up: ${e.message}")
            showToast("Error scrolling up: ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll up from specific coordinates with custom distance and duration
     * 
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param distance Distance in pixels to scroll
     * @param duration Duration of the scroll gesture in milliseconds
     */
    fun scrollUp(x: Float, y: Float, distance: Float, duration: Long) {
        Log.d(TAG, "scrollUp method: Received x=$x, y=$y, distance=$distance, duration=$duration")
        showToast("Scrolling up from specific position...", false)
        
        try {
            // Create a path for the gesture (swipe from specified position downward by the specified distance)
            val swipePath = Path()
            val startX = x
            val startY = y
            val endX = x
            val endY = y + distance
            swipePath.moveTo(startX, startY)
            swipePath.lineTo(endX, endY)
            Log.d(TAG, "scrollUp method: Creating swipePath from ($startX, $startY) to ($endX, $endY) over $duration ms")
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                duration // custom duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "scrollUp method: Gesture completed for path from ($startX, $startY) to ($endX, $endY)")
                        showToast("Successfully scrolled up from position ($startX, $startY)", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "scrollUp method: Gesture CANCELLED for path from ($startX, $startY) to ($endX, $endY). GestureDescription: $gestureDescription")
                        showToast("Scroll up from position ($startX, $startY) cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll up gesture for path from ($startX, $startY) to ($endX, $endY)")
                showToast("Error scrolling up from position ($startX, $startY)", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up from coordinates: ${e.message}")
            showToast("Error scrolling up from position ($x, $y): ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll left on the screen using gesture
     */
    fun scrollLeft() {
        Log.d(TAG, "Scrolling left")
        showToast("Scrolling left...", false)
        
        try {
            // Get display metrics to calculate swipe coordinates
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            // Create a path for the gesture (swipe from left to right, content moves left)
            val swipePath = Path()
            swipePath.moveTo(screenWidth * 0.3f, screenHeight / 2f) // Start from 30% across the screen
            swipePath.lineTo(screenWidth * 0.7f, screenHeight / 2f) // Move to 70% across the screen
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                300 // duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Scroll left gesture completed")
                        showToast("Successfully scrolled left", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Scroll left gesture cancelled")
                        showToast("Scroll left cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll left gesture")
                showToast("Error scrolling left", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left: ${e.message}")
            showToast("Error scrolling left: ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll left from specific coordinates with custom distance and duration
     * 
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param distance Distance in pixels to scroll
     * @param duration Duration of the scroll gesture in milliseconds
     */
    fun scrollLeft(x: Float, y: Float, distance: Float, duration: Long) {
        Log.d(TAG, "scrollLeft method: Received x=$x, y=$y, distance=$distance, duration=$duration")
        showToast("Scrolling left from specific position...", false)
        
        try {
            // Create a path for the gesture (swipe L-R, content moves Left)
            val swipePath = Path()
            val startX = x
            val startY = y
            val endX = x + distance // Finger swipes L-R, content moves Left
            val endY = y
            swipePath.moveTo(startX, startY)
            swipePath.lineTo(endX, endY)
            Log.d(TAG, "scrollLeft method: Creating swipePath from ($startX, $startY) to ($endX, $endY) over $duration ms")
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                duration // custom duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "scrollLeft method: Gesture completed for path from ($startX, $startY) to ($endX, $endY)")
                        showToast("Successfully scrolled left from position ($startX, $startY)", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "scrollLeft method: Gesture CANCELLED for path from ($startX, $startY) to ($endX, $endY). GestureDescription: $gestureDescription")
                        showToast("Scroll left from position ($startX, $startY) cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll left gesture for path from ($startX, $startY) to ($endX, $endY)")
                showToast("Error scrolling left from position ($startX, $startY)", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left from coordinates: ${e.message}")
            showToast("Error scrolling left from position ($x, $y): ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll right on the screen using gesture
     */
    fun scrollRight() {
        Log.d(TAG, "Scrolling right")
        showToast("Scrolling right...", false)
        
        try {
            // Get display metrics to calculate swipe coordinates
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            // Create a path for the gesture (swipe from right to left, content moves right)
            val swipePath = Path()
            swipePath.moveTo(screenWidth * 0.7f, screenHeight / 2f) // Start from 70% across the screen
            swipePath.lineTo(screenWidth * 0.3f, screenHeight / 2f) // Move to 30% across the screen
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                300 // duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Scroll right gesture completed")
                        showToast("Successfully scrolled right", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Scroll right gesture cancelled")
                        showToast("Scroll right cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll right gesture")
                showToast("Error scrolling right", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right: ${e.message}")
            showToast("Error scrolling right: ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Scroll right from specific coordinates with custom distance and duration
     * 
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param distance Distance in pixels to scroll
     * @param duration Duration of the scroll gesture in milliseconds
     */
    fun scrollRight(x: Float, y: Float, distance: Float, duration: Long) {
        Log.d(TAG, "scrollRight method: Received x=$x, y=$y, distance=$distance, duration=$duration")
        showToast("Scrolling right from specific position...", false)
        
        try {
            // Create a path for the gesture (swipe R-L, content moves Right)
            val swipePath = Path()
            val startX = x
            val startY = y
            val endX = x - distance // Finger swipes R-L, content moves Right
            val endY = y
            swipePath.moveTo(startX, startY)
            swipePath.lineTo(endX, endY)
            Log.d(TAG, "scrollRight method: Creating swipePath from ($startX, $startY) to ($endX, $endY) over $duration ms")
            
            // Create a gesture builder and add the swipe
            val gestureBuilder = GestureDescription.Builder()
            val gesture = GestureDescription.StrokeDescription(
                swipePath, 
                0, // start time
                duration // custom duration in milliseconds
            )
            gestureBuilder.addStroke(gesture)
            
            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "scrollRight method: Gesture completed for path from ($startX, $startY) to ($endX, $endY)")
                        showToast("Successfully scrolled right from position ($startX, $startY)", false)
                        scheduleNextCommandProcessing()
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "scrollRight method: Gesture CANCELLED for path from ($startX, $startY) to ($endX, $endY). GestureDescription: $gestureDescription")
                        showToast("Scroll right from position ($startX, $startY) cancelled", true)
                        scheduleNextCommandProcessing()
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll right gesture for path from ($startX, $startY) to ($endX, $endY)")
                showToast("Error scrolling right from position ($startX, $startY)", true)
                scheduleNextCommandProcessing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right from coordinates: ${e.message}")
            showToast("Error scrolling right from position ($x, $y): ${e.message}", true)
            scheduleNextCommandProcessing()
        }
    }
    
    /**
     * Show a toast message
     */
    private fun showToast(message: String, isError: Boolean) {
        try {
            // Show the toast on the main thread
            Handler(Looper.getMainLooper()).post {
                val mainActivity = MainActivity.getInstance()
                if (mainActivity != null) {
                    mainActivity.updateStatusMessage(message, isError)
                } else {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast: ${e.message}")
        }
    }
}
