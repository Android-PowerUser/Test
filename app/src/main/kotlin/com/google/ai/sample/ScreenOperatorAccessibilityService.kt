package com.google.ai.sample

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
                    Log.d(TAG, "Taking screenshot with 850ms delay")
                    showToast("Versuche Screenshot aufzunehmen (mit 850ms Verzögerung)", false)
                    // Add a 850ms delay before taking the screenshot, sure all commands executed before
                    mainHandler.postDelayed({
                        serviceInstance?.takeScreenshot()
                    }, 850) // 850ms delay
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
                is Command.WriteText -> {
                    Log.d(TAG, "Writing text: ${command.text}")
                    showToast("Versuche Text zu schreiben: \"${command.text}\"", false)
                    serviceInstance?.writeText(command.text)
                }
                is Command.UseHighReasoningModel -> {
                    Log.d(TAG, "Switching to high reasoning model (gemini-2.5-pro-preview-03-25)")
                    showToast("Wechsle zu leistungsfähigerem Modell (gemini-2.5-pro-preview-03-25)", false)
                    GenerativeAiViewModelFactory.highReasoningModel()
                }
                is Command.UseLowReasoningModel -> {
                    Log.d(TAG, "Switching to low reasoning model (gemini-2.0-flash-lite)")
                    showToast("Wechsle zu schnellerem Modell (gemini-2.0-flash-lite)", false)
                    GenerativeAiViewModelFactory.lowReasoningModel()
                } 
                    is Command.PressEnterKey -> {
                    Log.d(TAG, "Pressing Enter key")
                    showToast("Versuche Enter-Taste zu drücken", false)
                    serviceInstance?.pressEnterKey()
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
     * Write text into the currently focused text field
     * 
     * @param text The text to write
     */
    fun writeText(text: String) {
        Log.d(TAG, "Writing text: $text")
        showToast("Schreibe Text: \"$text\"", false)
        
        try {
            // Refresh the root node
            refreshRootNode()
            
            // Check if root node is available
            if (rootNode == null) {
                Log.e(TAG, "Root node is null, cannot write text")
                showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
                return
            }
            
            // Find the focused node (which should be an editable text field)
            val focusedNode = findFocusedEditableNode(rootNode!!)
            
            if (focusedNode != null) {
                Log.d(TAG, "Found focused editable node")
                showToast("Textfeld gefunden, schreibe Text: \"$text\"", false)
                
                // Set the text in the editable field
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                
                val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                
                if (result) {
                    Log.d(TAG, "Successfully wrote text: $text")
                    showToast("Text erfolgreich geschrieben: \"$text\"", false)
                } else {
                    Log.e(TAG, "Failed to write text, trying alternative methods")
                    showToast("Fehler beim Schreiben des Textes, versuche alternative Methoden", true)
                    
                    // Try alternative methods
                    tryAlternativeTextInputMethods(focusedNode, text)
                }
                
                // Recycle the node
                focusedNode.recycle()
            } else {
                Log.e(TAG, "Could not find focused editable node")
                showToast("Kein fokussiertes Textfeld gefunden, versuche Suche nach editierbaren Feldern", true)
                
                // Try to find any editable field
                val editableNode = findFirstEditableNode(rootNode!!)
                
                if (editableNode != null) {
                    Log.d(TAG, "Found editable node")
                    showToast("Editierbares Textfeld gefunden, versuche zu fokussieren", false)
                    
                    // Focus the editable field
                    val focusResult = editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    
                    if (focusResult) {
                        Log.d(TAG, "Successfully focused editable node")
                        showToast("Textfeld erfolgreich fokussiert, schreibe Text: \"$text\"", false)
                        
                        // Set the text in the editable field
                        val bundle = android.os.Bundle()
                        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        
                        val result = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                        
                        if (result) {
                            Log.d(TAG, "Successfully wrote text: $text")
                            showToast("Text erfolgreich geschrieben: \"$text\"", false)
                        } else {
                            Log.e(TAG, "Failed to write text, trying alternative methods")
                            showToast("Fehler beim Schreiben des Textes, versuche alternative Methoden", true)
                            
                            // Try alternative methods
                            tryAlternativeTextInputMethods(editableNode, text)
                        }
                    } else {
                        Log.e(TAG, "Failed to focus editable node")
                        showToast("Fehler beim Fokussieren des Textfeldes", true)
                    }
                    
                    // Recycle the node
                    editableNode.recycle()
                } else {
                    Log.e(TAG, "Could not find any editable node")
                    showToast("Kein editierbares Textfeld gefunden", true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing text: ${e.message}")
            showToast("Fehler beim Schreiben des Textes: ${e.message}", true)
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
            showToast("Versuche alternative Methoden zum Texteingeben", false)
            
            // Try to paste text
            pasteText(node, text)
        } catch (e: Exception) {
            Log.e(TAG, "Error trying alternative text input methods: ${e.message}")
            showToast("Fehler bei alternativen Texteingabemethoden: ${e.message}", true)
        }
    }
    
    /**
     * Paste text into a node
     */
    private fun pasteText(node: AccessibilityNodeInfo, text: String) {
        try {
            Log.d(TAG, "Trying to paste text: $text")
            showToast("Versuche Text einzufügen: \"$text\"", false)
            
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
                        showToast("Text erfolgreich eingefügt: \"$text\"", false)
                    } else {
                        Log.e(TAG, "Failed to paste text")
                        showToast("Fehler beim Einfügen des Textes", true)
                    }
                }, 200) // 200ms delay
            } else {
                Log.e(TAG, "Failed to select all text")
                showToast("Fehler beim Auswählen des vorhandenen Textes", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pasting text: ${e.message}")
            showToast("Fehler beim Einfügen des Textes: ${e.message}", true)
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
            tapAtCoordinates(centerX.toFloat(), centerY.toFloat())
        }
    }
    
    /**
     * Find and click a button by content description
     */
    private fun findAndClickButtonByContentDescription(description: String) {
        Log.d(TAG, "Finding and clicking button with content description: $description")
        showToast("Suche Button mit Beschreibung: \"$description\"", false)
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by content description")
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
            showToast("Button mit Beschreibung \"$description\" nicht gefunden, versuche Suche nach ID", true)
            
            // Try to find by ID
            findAndClickButtonById(description)
        }
    }
    
    /**
     * Find and click a button by ID
     */
    private fun findAndClickButtonById(id: String) {
        Log.d(TAG, "Finding and clicking button with ID: $id")
        showToast("Suche Button mit ID: \"$id\"", false)
        
        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by ID")
            showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
            return
        }
        
        // Try to find the node with the specified ID
        val node = findNodeById(rootNode!!, id)
        
        if (node != null) {
            Log.d(TAG, "Found node with ID: $id")
            showToast("Button gefunden mit ID: \"$id\"", false)
            
            // Add a small delay before clicking
            Handler(Looper.getMainLooper()).postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)
                
                if (clickResult) {
                    Log.d(TAG, "Successfully clicked on button with ID: $id")
                    showToast("Klick auf Button mit ID \"$id\" erfolgreich", false)
                } else {
                    Log.e(TAG, "Failed to click on button with ID: $id")
                    showToast("Klick auf Button mit ID \"$id\" fehlgeschlagen, versuche alternative Methoden", true)
                    
                    // Try alternative methods
                    tryAlternativeClickMethods(node, id)
                }
                
                // Recycle the node
                node.recycle()
            }, 200) // 200ms delay
        } else {
            Log.e(TAG, "Could not find node with ID: $id")
            showToast("Button mit ID \"$id\" nicht gefunden", true)
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
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)) // 300ms duration
                .build()
            
            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Long tap gesture completed")
                    showToast("Tippen mit längerer Dauer auf Koordinaten ($x, $y) erfolgreich", false)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Long tap gesture cancelled")
                    showToast("Tippen mit längerer Dauer auf Koordinaten ($x, $y) abgebrochen", true)
                }
            }, null)
            
            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch long tap gesture")
                showToast("Fehler beim Senden der Tipp-Geste mit längerer Dauer", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates with longer duration: ${e.message}")
            showToast("Fehler beim Tippen mit längerer Dauer auf Koordinaten: ${e.message}", true)
        }
    }

	   /**
 * Press the Enter key
 */
fun pressEnterKey() {
    Log.d(TAG, "Pressing Enter key")
    try {
        // Get display metrics to calculate screen dimensions
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Calculate tap position at 95% of width and 95% of height
        val x = (screenWidth * 0.95f).toInt()
        val y = (screenHeight * 0.95f).toInt()
        
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
                Log.d(TAG, "Enter key tap gesture completed")
                showToast("Enter-Taste erfolgreich gedrückt", false)
            }
            
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.e(TAG, "Enter key tap gesture cancelled")
                showToast("Enter-Tasten-Geste abgebrochen", true)
            }
        }, null)
        
        if (!result) {
            Log.e(TAG, "Failed to dispatch Enter key tap gesture")
            showToast("Fehler beim Drücken der Enter-Taste", true)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error pressing Enter key: ${e.message}")
        showToast("Fehler beim Drücken der Enter-Taste: ${e.message}", true)
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
                showToast("Fehler beim Öffnen der App: $appName", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}")
            showToast("Fehler beim Öffnen der App: ${e.message}", true)
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
                showToast("App geöffnet: $appName", false)
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
                    showToast("App geöffnet: $appName", false)
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
                showToast("App geöffnet: $appName", false)
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
     * Take a screenshot by simulating hardware button presses
     */
    fun takeScreenshot() {
        Log.d(TAG, "Taking screenshot")
        showToast("Nehme Screenshot auf...", false)
        
        try {
            // Capture screen information before taking the screenshot
            val screenInfo = captureScreenInformation()
            
            // Try to use the global action to take a screenshot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                if (result) {
                    Log.d(TAG, "Successfully took screenshot using GLOBAL_ACTION_TAKE_SCREENSHOT")
                    
                    // Wait a moment for the screenshot to be saved, then retrieve it
                    handler.postDelayed({
                        retrieveLatestScreenshot(screenInfo)
                    }, 800) // Wait 800ms for the screenshot to be saved (reduced from 1000ms)
                    
                    return
                } else {
                    Log.d(TAG, "Failed to take screenshot using GLOBAL_ACTION_TAKE_SCREENSHOT, trying alternative methods")
                }
            }
            
            // If the global action failed or is not available, show an error
            Log.e(TAG, "Could not take screenshot, global action not available or failed")
            showToast("Fehler beim Aufnehmen des Screenshots: Globale Aktion nicht verfügbar oder fehlgeschlagen", true)
            
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
                screenInfo.append("Beschreibung: \"${element.contentDescription}\" ")
            }
            
            // Add element class name if available
            if (element.className != null) {
                screenInfo.append("Klasse: ${element.className} ")
            }
            
            // Add element properties
            val properties = mutableListOf<String>()
            if (element.isClickable) properties.add("klickbar")
            if (element.isLongClickable) properties.add("lang-klickbar")
            if (element.isCheckable) properties.add("auswählbar")
            if (element.isChecked) properties.add("ausgewählt")
            if (element.isEditable) properties.add("editierbar")
            if (element.isFocusable) properties.add("fokussierbar")
            if (element.isFocused) properties.add("fokussiert")
            if (element.isPassword) properties.add("passwort")
            if (element.isScrollable) properties.add("scrollbar")
            
            if (properties.isNotEmpty()) {
                screenInfo.append("Eigenschaften: ${properties.joinToString(", ")} ")
            }
            
            // Add element bounds
            val rect = Rect()
            element.getBoundsInScreen(rect)
            screenInfo.append("Position: (${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom})")
            
            // Add a button name if we can infer one
            val buttonName = getButtonName(element)
            if (buttonName.isNotEmpty()) {
                screenInfo.append(" Vermuteter Name: \"$buttonName\"")
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
                        return "Textfeld: $hintText"
                    }
                } catch (e: Exception) {
                    // Reflection failed, ignore
                }
                
                return "Textfeld"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting button name: ${e.message}")
        }
        
        return ""
    }
    
    /**
     * Retrieve the latest screenshot
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
                
                // Automatically close the screenshot notification
                closeScreenshotNotification()
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
     * Automatically close the screenshot notification
     */
    private fun closeScreenshotNotification() {
        Log.d(TAG, "Attempting to close screenshot notification")
        
        // Add a delay to ensure the notification is visible
        handler.postDelayed({
            try {
                // Refresh the root node to get the latest UI state
                refreshRootNode()
                
                // Check if root node is available
                if (rootNode == null) {
                    Log.e(TAG, "Root node is null, cannot close screenshot notification")
                    showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
                    return@postDelayed
                }
                
                // List of possible button texts for "Close" in different languages and ROMs
                val closeButtonTexts = listOf(
                    "Screenshot close", "Screenshot schließen", "Cerrar captura de pantalla", 
                    "Fermer la capture d'écran", "Chiudi screenshot", "Fechar captura de tela",
                    "Screenshot sluiten", "Lukk skjermbilde", "Luk skærmbillede", "Stäng skärmbild",
                    "Sulje kuvakaappaus", "Zamknij zrzut ekranu", "Zavřít snímek obrazovky",
                    "Zatvoriť snímku obrazovky", "Képernyőkép bezárása", "Închide captura de ecran",
                    "Затвори екранна снимка", "Закрыть скриншот", "Закрити знімок екрана",
                    "Затвори снимак екрана", "Κλείσιμο στιγμιότυπου οθόνης", "Ekran görüntüsünü kapat",
                    "סגור צילום מסך", "إغلاق لقطة الشاشة", "स्क्रीनशॉट बंद करें", "स्क्रीनशॉट बंद करा",
                    "スクリーンショットを閉じる", "关闭截图", "關閉截圖", "스크린샷 닫기",
                    "Đóng ảnh chụp màn hình", "ปิดภาพหน้าจอ", "Tutup tangkapan layar", "Isara ang screenshot",
                    "Tutup tangkapan skrin", "Tanca la captura de pantalla", "Peche a captura de pantalla",
                    "Itxi pantaila-argazkia", "Cau'r sgrinlun", "Mbyll fotografinë e ekranit",
                    "Zapri posnetek zaslona", "Aizvērt ekrānuzņēmumu", "Uždaryti ekrano kopiją",
                    "Sulge ekraanipilt", "Zapri snimku zaslona", "Zatvori snimak zaslona",
                    "Затвори снимка на екрана", "Затвори снимак екрана", "Затвори снимка на екранот",
                    "Dùin an glacadh-sgrìn", "Cau'r sgrinlun", "Funga picha ya skrini", "Vala isithombe-skrini",
                    "Xirra l-iskrinxott"
                )
                
                // Try to find and click the close button
                for (buttonText in closeButtonTexts) {
                    val node = findNodeByText(rootNode!!, buttonText)
                    if (node != null) {
                        Log.d(TAG, "Found screenshot close button with text: $buttonText")
                        showToast("Screenshot-Schließen-Button gefunden: \"$buttonText\"", false)
                        
                        // Perform the click
                        val clickResult = performClickOnNode(node)
                        
                        if (clickResult) {
                            Log.d(TAG, "Successfully clicked on screenshot close button: $buttonText")
                            showToast("Screenshot-Benachrichtigung automatisch geschlossen", false)
                        } else {
                            Log.e(TAG, "Failed to click on screenshot close button: $buttonText")
                            showToast("Klick auf Screenshot-Schließen-Button fehlgeschlagen, versuche alternative Methoden", true)
                            
                            // Try alternative methods
                            tryAlternativeClickMethods(node, buttonText)
                        }
                        
                        // Recycle the node
                        node.recycle()
                        break
                    }
                }
                
                // If no button with text was found, try to find by content description
                for (buttonText in closeButtonTexts) {
                    val node = findNodeByContentDescription(rootNode!!, buttonText)
                    if (node != null) {
                        Log.d(TAG, "Found screenshot close button with description: $buttonText")
                        showToast("Screenshot-Schließen-Button gefunden mit Beschreibung: \"$buttonText\"", false)
                        
                        // Perform the click
                        val clickResult = performClickOnNode(node)
                        
                        if (clickResult) {
                            Log.d(TAG, "Successfully clicked on screenshot close button with description: $buttonText")
                            showToast("Screenshot-Benachrichtigung automatisch geschlossen", false)
                        } else {
                            Log.e(TAG, "Failed to click on screenshot close button with description: $buttonText")
                            showToast("Klick auf Screenshot-Schließen-Button fehlgeschlagen, versuche alternative Methoden", true)
                            
                            // Try alternative methods
                            tryAlternativeClickMethods(node, buttonText)
                        }
                        
                        // Recycle the node
                        node.recycle()
                        break
                    }
                }
                
                // If no specific button was found, look for any button in a notification
                val notificationButtons = findNotificationButtons()
                if (notificationButtons.isNotEmpty()) {
                    // Try clicking the rightmost button (usually the close/dismiss button)
                    val rightmostButton = findRightmostButton(notificationButtons)
                    if (rightmostButton != null) {
                        Log.d(TAG, "Found rightmost notification button, assuming it's the close button")
                        showToast("Versuche den rechtesten Button in der Benachrichtigung zu drücken", false)
                        
                        // Perform the click
                        val clickResult = performClickOnNode(rightmostButton)
                        
                        if (clickResult) {
                            Log.d(TAG, "Successfully clicked on rightmost notification button")
                            showToast("Screenshot-Benachrichtigung automatisch geschlossen", false)
                        } else {
                            Log.e(TAG, "Failed to click on rightmost notification button")
                            showToast("Klick auf rechtesten Button in der Benachrichtigung fehlgeschlagen", true)
                            
                            // Try alternative methods
                            tryAlternativeClickMethods(rightmostButton, "Close")
                        }
                        
                        // Recycle the node
                        rightmostButton.recycle()
                    }
                    
                    // Recycle all notification buttons
                    notificationButtons.forEach { it.recycle() }
                }
                
                Log.d(TAG, "Could not find screenshot close button")
                showToast("Konnte keinen Screenshot-Schließen-Button finden", true)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing screenshot notification: ${e.message}")
                showToast("Fehler beim Schließen der Screenshot-Benachrichtigung: ${e.message}", true)
            }
        }, 1000) // 1000ms delay to ensure notification is visible
    }
    
    /**
     * Find all buttons in notifications
     */
    private fun findNotificationButtons(): List<AccessibilityNodeInfo> {
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // Check if root node is available
            if (rootNode == null) {
                Log.e(TAG, "Root node is null, cannot find notification buttons")
                return buttons
            }
            
            // Find nodes that might be notifications
            val potentialNotifications = findPotentialNotifications(rootNode!!)
            
            // For each potential notification, find clickable children
            for (notification in potentialNotifications) {
                findClickableChildren(notification, buttons)
                notification.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding notification buttons: ${e.message}")
        }
        
        return buttons
    }
    
    /**
     * Find potential notification containers
     */
    private fun findPotentialNotifications(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val notifications = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // Check if this node might be a notification
            val className = node.className?.toString() ?: ""
            if (className.contains("Notification", ignoreCase = true) || 
                className.contains("StatusBar", ignoreCase = true) ||
                (getNodeId(node).contains("notification", ignoreCase = true))) {
                notifications.add(AccessibilityNodeInfo.obtain(node))
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                notifications.addAll(findPotentialNotifications(child))
                child.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding potential notifications: ${e.message}")
        }
        
        return notifications
    }
    
    /**
     * Find clickable children of a node
     */
    private fun findClickableChildren(node: AccessibilityNodeInfo, buttons: MutableList<AccessibilityNodeInfo>) {
        try {
            // Check if this node is clickable
            if (node.isClickable) {
                // Check if it's a button-like element
                val className = node.className?.toString() ?: ""
                if (className.contains("Button", ignoreCase = true) || 
                    className.contains("ImageView", ignoreCase = true) ||
                    className.contains("TextView", ignoreCase = true) ||
                    className.contains("ImageButton", ignoreCase = true)) {
                    buttons.add(AccessibilityNodeInfo.obtain(node))
                }
            }
            
            // Check children recursively
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findClickableChildren(child, buttons)
                child.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding clickable children: ${e.message}")
        }
    }
    
    /**
     * Find the rightmost button in a list of buttons
     */
    private fun findRightmostButton(buttons: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        if (buttons.isEmpty()) {
            return null
        }
        
        var rightmostButton: AccessibilityNodeInfo? = null
        var rightmostX = -1
        
        for (button in buttons) {
            val rect = Rect()
            button.getBoundsInScreen(rect)
            
            if (rect.centerX() > rightmostX) {
                rightmostX = rect.centerX()
                rightmostButton = AccessibilityNodeInfo.obtain(button)
            }
        }
        
        return rightmostButton
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
     * Show the recent apps screen
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
        Log.d(TAG, "Scrolling down")
        showToast("Scrolle nach unten...", false)
        
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
                        showToast("Erfolgreich nach unten gescrollt", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Scroll down gesture cancelled")
                        showToast("Scrollen nach unten abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll down gesture")
                showToast("Fehler beim Scrollen nach unten", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down: ${e.message}")
            showToast("Fehler beim Scrollen nach unten: ${e.message}", true)
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
        Log.d(TAG, "Scrolling down from ($x, $y) with distance $distance and duration $duration ms")
        showToast("Scrolle nach unten von bestimmter Position...", false)
        
        try {
            // Create a path for the gesture (swipe from specified position upward by the specified distance)
            val swipePath = Path()
            swipePath.moveTo(x, y) // Start from specified position
            swipePath.lineTo(x, y - distance) // Move upward by the specified distance
            
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
                        Log.d(TAG, "Coordinate-based scroll down gesture completed")
                        showToast("Erfolgreich nach unten gescrollt von Position ($x, $y)", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.e(TAG, "Coordinate-based scroll down gesture cancelled")
                        showToast("Scrollen nach unten von Position ($x, $y) abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll down gesture")
                showToast("Fehler beim Scrollen nach unten von Position ($x, $y)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling down from coordinates: ${e.message}")
            showToast("Fehler beim Scrollen nach unten von Position ($x, $y): ${e.message}", true)
        }
    }
    
    /**
     * Scroll up on the screen using gesture
     */
    fun scrollUp() {
        Log.d(TAG, "Scrolling up")
        showToast("Scrolle nach oben...", false)
        
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
                        showToast("Erfolgreich nach oben gescrollt", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Scroll up gesture cancelled")
                        showToast("Scrollen nach oben abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll up gesture")
                showToast("Fehler beim Scrollen nach oben", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up: ${e.message}")
            showToast("Fehler beim Scrollen nach oben: ${e.message}", true)
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
        Log.d(TAG, "Scrolling up from ($x, $y) with distance $distance and duration $duration ms")
        showToast("Scrolle nach oben von bestimmter Position...", false)
        
        try {
            // Create a path for the gesture (swipe from specified position downward by the specified distance)
            val swipePath = Path()
            swipePath.moveTo(x, y) // Start from specified position
            swipePath.lineTo(x, y + distance) // Move downward by the specified distance
            
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
                        Log.d(TAG, "Coordinate-based scroll up gesture completed")
                        showToast("Erfolgreich nach oben gescrollt von Position ($x, $y)", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.e(TAG, "Coordinate-based scroll up gesture cancelled")
                        showToast("Scrollen nach oben von Position ($x, $y) abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll up gesture")
                showToast("Fehler beim Scrollen nach oben von Position ($x, $y)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling up from coordinates: ${e.message}")
            showToast("Fehler beim Scrollen nach oben von Position ($x, $y): ${e.message}", true)
        }
    }
    
    /**
     * Scroll left on the screen using gesture
     */
    fun scrollLeft() {
        Log.d(TAG, "Scrolling left")
        showToast("Scrolle nach links...", false)
        
        try {
            // Get display metrics to calculate swipe coordinates
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            // Create a path for the gesture (swipe from middle-right to middle-left)
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
                        Log.d(TAG, "Scroll left gesture completed")
                        showToast("Erfolgreich nach links gescrollt", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.e(TAG, "Scroll left gesture cancelled")
                        showToast("Scrollen nach links abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll left gesture")
                showToast("Fehler beim Scrollen nach links", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left: ${e.message}")
            showToast("Fehler beim Scrollen nach links: ${e.message}", true)
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
        Log.d(TAG, "Scrolling left from ($x, $y) with distance $distance and duration $duration ms")
        showToast("Scrolle nach links von bestimmter Position...", false)
        
        try {
            // Create a path for the gesture (swipe from specified position to the left by the specified distance)
            val swipePath = Path()
            swipePath.moveTo(x, y) // Start from specified position
            swipePath.lineTo(x - distance, y) // Move to the left by the specified distance
            
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
                        Log.d(TAG, "Coordinate-based scroll left gesture completed")
                        showToast("Erfolgreich nach links gescrollt von Position ($x, $y)", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.e(TAG, "Coordinate-based scroll left gesture cancelled")
                        showToast("Scrollen nach links von Position ($x, $y) abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll left gesture")
                showToast("Fehler beim Scrollen nach links von Position ($x, $y)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling left from coordinates: ${e.message}")
            showToast("Fehler beim Scrollen nach links von Position ($x, $y): ${e.message}", true)
        }
    }
    
    /**
     * Scroll right on the screen using gesture
     */
    fun scrollRight() {
        Log.d(TAG, "Scrolling right")
        showToast("Scrolle nach rechts...", false)
        
        try {
            // Get display metrics to calculate swipe coordinates
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            // Create a path for the gesture (swipe from middle-left to middle-right)
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
                        Log.d(TAG, "Scroll right gesture completed")
                        showToast("Erfolgreich nach rechts gescrollt", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.e(TAG, "Scroll right gesture cancelled")
                        showToast("Scrollen nach rechts abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch scroll right gesture")
                showToast("Fehler beim Scrollen nach rechts", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right: ${e.message}")
            showToast("Fehler beim Scrollen nach rechts: ${e.message}", true)
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
        Log.d(TAG, "Scrolling right from ($x, $y) with distance $distance and duration $duration ms")
        showToast("Scrolle nach rechts von bestimmter Position...", false)
        
        try {
            // Create a path for the gesture (swipe from specified position to the right by the specified distance)
            val swipePath = Path()
            swipePath.moveTo(x, y) // Start from specified position
            swipePath.lineTo(x + distance, y) // Move to the right by the specified distance
            
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
                        Log.d(TAG, "Coordinate-based scroll right gesture completed")
                        showToast("Erfolgreich nach rechts gescrollt von Position ($x, $y)", false)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.e(TAG, "Coordinate-based scroll right gesture cancelled")
                        showToast("Scrollen nach rechts von Position ($x, $y) abgebrochen", true)
                    }
                },
                null // handler
            )
            
            if (!result) {
                Log.e(TAG, "Failed to dispatch coordinate-based scroll right gesture")
                showToast("Fehler beim Scrollen nach rechts von Position ($x, $y)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling right from coordinates: ${e.message}")
            showToast("Fehler beim Scrollen nach rechts von Position ($x, $y): ${e.message}", true)
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
