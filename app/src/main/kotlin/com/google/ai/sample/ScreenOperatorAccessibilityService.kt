package com.google.ai.sample.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig // Import hinzugefügt
import com.google.ai.sample.BuildConfig // Import hinzugefügt
import com.google.ai.sample.MainActivity
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

class ChatViewModel(
    // Das initiale Modell wird weiterhin übergeben
    initialGenerativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "ChatViewModel"

    // --- NEU: Variable für das aktuell verwendete Modell ---
    private var currentGenerativeModel: GenerativeModel = initialGenerativeModel
        private set // Nur intern änderbar

    // --- GEÄNDERT: Chat wird mit dem aktuellen Modell initialisiert ---
    private var chat = currentGenerativeModel.startChat(
        history = listOf(
            content(role = "user") { text("Hello, I have 2 dogs in my house.") },
            content(role = "model") { text("Great to meet you. What would you like to know?") }
        )
    )

    private val _uiState: MutableStateFlow<ChatUiState> =
        MutableStateFlow(ChatUiState(chat.history.map { content ->
            // Map the initial messages
            ChatMessage(
                text = content.parts.first().asTextOrNull() ?: "",
                participant = if (content.role == "user") Participant.USER else Participant.MODEL,
                isPending = false
            )
        }))
    val uiState: StateFlow<ChatUiState> =
        _uiState.asStateFlow()

    // Keep track of detected commands
    private val _detectedCommands = MutableStateFlow<List<Command>>(emptyList())
    val detectedCommands: StateFlow<List<Command>> = _detectedCommands.asStateFlow()

    // Keep track of command execution status
    private val _commandExecutionStatus = MutableStateFlow<String>("")
    val commandExecutionStatus: StateFlow<String> = _commandExecutionStatus.asStateFlow()

    fun sendMessage(userMessage: String) {
        // Add a pending message
        _uiState.value.addMessage(
            ChatMessage(
                text = userMessage,
                participant = Participant.USER,
                isPending = true
            )
        )

        // Clear previous commands
        _detectedCommands.value = emptyList()
        _commandExecutionStatus.value = ""

        viewModelScope.launch {
            try {
                // --- GEÄNDERT: Verwendet die aktuelle 'chat'-Instanz ---
                val response = chat.sendMessage(userMessage)

                _uiState.value.replaceLastPendingMessage()

                response.text?.let { modelResponse ->
                    _uiState.value.addMessage(
                        ChatMessage(
                            text = modelResponse,
                            participant = Participant.MODEL,
                            isPending = false
                        )
                    )

                    // Process commands in the response
                    processCommands(modelResponse)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e) // Logging hinzugefügt
                _uiState.value.replaceLastPendingMessage()
                _uiState.value.addMessage(
                    ChatMessage(
                        text = e.localizedMessage ?: "Unknown error sending message", // Klarere Fehlermeldung
                        participant = Participant.ERROR
                    )
                )

                // Update command execution status
                _commandExecutionStatus.value = "Fehler beim Senden: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Process commands found in the AI response
     */
    private fun processCommands(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                // Parse commands from the text
                // --- HINWEIS: Die Logs im Parser bleiben erhalten ---
                val commands = CommandParser.parseCommands(text)

                if (commands.isNotEmpty()) {
                    Log.d(TAG, "Found ${commands.size} commands in response")

                    // Update the detected commands
                    val currentCommands = _detectedCommands.value.toMutableList()
                    currentCommands.addAll(commands)
                    _detectedCommands.value = currentCommands

                    // Update status to show commands were detected
                    val commandDescriptions = commands.map {
                        when (it) {
                            is Command.ClickButton -> "Klick auf Button: \"${it.buttonText}\""
                            is Command.TapCoordinates -> "Tippen auf Koordinaten: (${it.x}, ${it.y})"
                            is Command.TakeScreenshot -> "Screenshot aufnehmen"
                            is Command.PressHomeButton -> "Home-Button drücken"
                            is Command.PressBackButton -> "Zurück-Button drücken"
                            is Command.ShowRecentApps -> "Übersicht der letzten Apps öffnen"
                            is Command.ScrollDown -> "Nach unten scrollen"
                            is Command.ScrollUp -> "Nach oben scrollen"
                            is Command.ScrollLeft -> "Nach links scrollen"
                            is Command.ScrollRight -> "Nach rechts scrollen"
                            is Command.ScrollDownFromCoordinates -> "Nach unten scrollen von Position (${it.x}, ${it.y}) mit Distanz ${it.distance}px und Dauer ${it.duration}ms"
                            is Command.ScrollUpFromCoordinates -> "Nach oben scrollen von Position (${it.x}, ${it.y}) mit Distanz ${it.distance}px und Dauer ${it.duration}ms"
                            is Command.ScrollLeftFromCoordinates -> "Nach links scrollen von Position (${it.x}, ${it.y}) mit Distanz ${it.distance}px und Dauer ${it.duration}ms"
                            is Command.ScrollRightFromCoordinates -> "Nach rechts scrollen von Position (${it.x}, ${it.y}) mit Distanz ${it.distance}px und Dauer ${it.duration}ms"
                            is Command.OpenApp -> "App öffnen: \"${it.packageName}\""
                            is Command.WriteText -> "Text schreiben: \"${it.text}\""
                            is Command.UseHighReasoningModel -> "Wechsle zu leistungsfähigerem Modell (gemini-2.5-pro-exp-03-25)" // Name aktualisiert
                            is Command.UseLowReasoningModel -> "Wechsle zu schnellerem Modell (gemini-2.0-flash-lite)"
                        }
                    }

                    // Show toast with detected commands
                    val mainActivity = MainActivity.getInstance()
                    mainActivity?.updateStatusMessage(
                        "Befehle erkannt: ${commandDescriptions.joinToString(", ")}",
                        false
                    )

                    // Update status
                    _commandExecutionStatus.value = "Befehle erkannt: ${commandDescriptions.joinToString(", ")}"

                    // Check if accessibility service is enabled
                    val isServiceEnabled = mainActivity?.let {
                        ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it)
                    } ?: false

                    if (!isServiceEnabled) {
                        Log.e(TAG, "Accessibility service is not enabled")
                        _commandExecutionStatus.value = "Accessibility Service ist nicht aktiviert. Bitte aktivieren Sie den Service in den Einstellungen."

                        // Prompt user to enable accessibility service
                        mainActivity?.checkAccessibilityServiceEnabled()
                        return@launch
                    }

                    // Check if service is available
                    if (!ScreenOperatorAccessibilityService.isServiceAvailable()) {
                        Log.e(TAG, "Accessibility service is not available")
                        _commandExecutionStatus.value = "Accessibility Service ist nicht verfügbar. Bitte starten Sie die App neu."

                        // Show toast
                        mainActivity?.updateStatusMessage(
                            "Accessibility Service ist nicht verfügbar. Bitte starten Sie die App neu.",
                            true
                        )
                        return@launch
                    }

                    // Execute each command
                    commands.forEachIndexed { index, command ->
                        Log.d(TAG, "Executing command: $command")

                        // Update status to show command is being executed
                        val commandDescription = when (command) {
                            is Command.ClickButton -> "Klick auf Button: \"${command.buttonText}\""
                            is Command.TapCoordinates -> "Tippen auf Koordinaten: (${command.x}, ${command.y})"
                            is Command.TakeScreenshot -> "Screenshot aufnehmen"
                            is Command.PressHomeButton -> "Home-Button drücken"
                            is Command.PressBackButton -> "Zurück-Button drücken"
                            is Command.ShowRecentApps -> "Übersicht der letzten Apps öffnen"
                            is Command.ScrollDown -> "Nach unten scrollen"
                            is Command.ScrollUp -> "Nach oben scrollen"
                            is Command.ScrollLeft -> "Nach links scrollen"
                            is Command.ScrollRight -> "Nach rechts scrollen"
                            is Command.ScrollDownFromCoordinates -> "Nach unten scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.ScrollUpFromCoordinates -> "Nach oben scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.ScrollLeftFromCoordinates -> "Nach links scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.ScrollRightFromCoordinates -> "Nach rechts scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.OpenApp -> "App öffnen: \"${command.packageName}\""
                            is Command.WriteText -> "Text schreiben: \"${command.text}\""
                            is Command.UseHighReasoningModel -> "Wechsle zu leistungsfähigerem Modell (gemini-2.5-pro-exp-03-25)" // Name aktualisiert
                            is Command.UseLowReasoningModel -> "Wechsle zu schnellerem Modell (gemini-2.0-flash-lite)"
                        }

                        _commandExecutionStatus.value = "Führe aus: $commandDescription (${index + 1}/${commands.size})"

                        // Show toast with command being executed
                        mainActivity?.updateStatusMessage(
                            "Führe aus: $commandDescription",
                            false
                        )

                        // Execute the command
                        ScreenOperatorAccessibilityService.executeCommand(command)

                        // Add a small delay between commands to avoid overwhelming the system
                        kotlinx.coroutines.delay(800)
                    }

                    // Update status to show all commands were executed
                    _commandExecutionStatus.value = "Alle Befehle ausgeführt: ${commandDescriptions.joinToString(", ")}"

                    // Show toast with all commands executed
                    mainActivity?.updateStatusMessage(
                        "Alle Befehle ausgeführt",
                        false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing commands: ${e.message}", e)
                _commandExecutionStatus.value = "Fehler bei der Befehlsverarbeitung: ${e.message}"

                // Show toast with error
                val mainActivity = MainActivity.getInstance()
                mainActivity?.updateStatusMessage(
                    "Fehler bei der Befehlsverarbeitung: ${e.message}",
                    true
                )
            }
        }
    }

    // --- NEUE FUNKTION zum Aktualisieren des Modells ---
    /**
     * Updates the GenerativeModel instance used by this ViewModel and restarts the chat.
     *
     * @param newModelName The name of the new model to use (e.g., "gemini-2.5-pro-exp-03-25").
     */
    fun updateGenerativeModel(newModelName: String) {
        viewModelScope.launch {
            Log.i(TAG, "Updating GenerativeModel to: $newModelName")
            try {
                val config = generationConfig {
                    temperature = 0.0f // Behalte deine Standardkonfiguration bei
                    // Weitere Konfigurationen hier hinzufügen, falls nötig
                }
                // Erstelle eine NEUE Instanz mit dem neuen Namen
                currentGenerativeModel = GenerativeModel(
                    modelName = newModelName,
                    apiKey = BuildConfig.apiKey, // Stelle sicher, dass apiKey hier verfügbar ist
                    generationConfig = config
                )

                // Speichere die aktuelle History, bevor der Chat neu gestartet wird
                val currentHistory = chat.history

                // Initialisiere die 'chat'-Instanz neu, damit sie das neue Modell verwendet
                chat = currentGenerativeModel.startChat(
                    history = currentHistory // Übernehme die bisherige History
                )
                Log.i(TAG, "GenerativeModel and chat instance updated successfully. History preserved.")

                // Optional: Füge eine Nachricht zum Chat hinzu, um den Wechsel anzuzeigen
                 _uiState.value.addMessage(
                     ChatMessage(
                         text = "Chat-Modell wurde zu '$newModelName' gewechselt.",
                         participant = Participant.MODEL, // Oder eine neue Participant.SYSTEM Rolle
                         isPending = false
                     )
                 )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update GenerativeModel to $newModelName: ${e.message}", e)
                // Optional: Füge eine Fehlermeldung zum Chat hinzu
                 _uiState.value.addMessage(
                     ChatMessage(
                         text = "Fehler beim Wechseln des Chat-Modells zu '$newModelName': ${e.localizedMessage}",
                         participant = Participant.ERROR,
                         isPending = false
                     )
                 )
                _commandExecutionStatus.value = "Fehler beim Modellwechsel: ${e.localizedMessage}"
            }
        }
    }
}
```

--- END OF FILE ChatViewModel.kt.txt ---

--- START OF FILE PhotoReasoningViewModel.kt.txt ---

```kotlin

```

--- END OF FILE PhotoReasoningViewModel.kt.txt ---

--- START OF FILE ScreenOperatorAccessibilityService.kt.txt ---

```kotlin
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
// import com.google.ai.sample.GenerativeViewModelFactory // Wird nicht mehr direkt für den Wechsel benötigt
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
                // --- GEÄNDERT: Ruft ViewModel-Methode statt Factory auf ---
                is Command.UseHighReasoningModel -> {
                    val newModelName = "gemini-2.5-pro-exp-03-25" // Neuer Modellname
                    Log.i(TAG, "Command received: Switch to high reasoning model ($newModelName)")
                    showToast("Wechsle zu leistungsfähigerem Modell ($newModelName)...", false)
                    // Rufe die neue Methode im/in den relevanten ViewModel(s) auf
                    val mainActivity = MainActivity.getInstance()
                    if (mainActivity != null) {
                        mainActivity.getChatViewModel()?.updateGenerativeModel(newModelName)
                        mainActivity.getPhotoReasoningViewModel()?.updateGenerativeModel(newModelName)
                        Log.i(TAG, "Requested model update in ViewModels for $newModelName")
                    } else {
                        Log.e(TAG, "MainActivity instance is null, cannot update ViewModels.")
                        showToast("Fehler: MainActivity nicht gefunden, Modellwechsel nicht möglich.", true)
                    }
                    // GenerativeAiViewModelFactory.highReasoningModel() // Nicht mehr benötigt für aktiven Wechsel
                }
                // --- GEÄNDERT: Ruft ViewModel-Methode statt Factory auf ---
                is Command.UseLowReasoningModel -> {
                    val newModelName = "gemini-2.0-flash-lite"
                    Log.i(TAG, "Command received: Switch to low reasoning model ($newModelName)")
                    showToast("Wechsle zu schnellerem Modell ($newModelName)...", false)
                    // Rufe die neue Methode im/in den relevanten ViewModel(s) auf
                     val mainActivity = MainActivity.getInstance()
                    if (mainActivity != null) {
                        mainActivity.getChatViewModel()?.updateGenerativeModel(newModelName)
                        mainActivity.getPhotoReasoningViewModel()?.updateGenerativeModel(newModelName)
                        Log.i(TAG, "Requested model update in ViewModels for $newModelName")
                    } else {
                        Log.e(TAG, "MainActivity instance is null, cannot update ViewModels.")
                        showToast("Fehler: MainActivity nicht gefunden, Modellwechsel nicht möglich.", true)
                    }
                    // GenerativeAiViewModelFactory.lowReasoningModel() // Nicht mehr benötigt für aktiven Wechsel
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
                    Log.e(TAG, "MainActivity instance is null, cannot show toast via MainActivity")
                    // Fallback: Zeige einen Standard-Toast, wenn MainActivity nicht verfügbar ist
                    // Dies sollte nur passieren, wenn der Service läuft, aber die App im Hintergrund ist
                    // oder die Activity zerstört wurde.
                    val duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    Toast.makeText(serviceInstance?.applicationContext ?: return@post, message, duration).show()
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
        val info = serviceInfo ?: AccessibilityServiceInfo() // Sicherstellen, dass info nicht null ist
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        // Flags kombinieren statt überschreiben
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
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
        // event?.let { // Null-Check ist gut, aber event ist non-null deklariert
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    // Log.d(TAG, "Accessibility event: View clicked") // Kann sehr gesprächig sein
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    // Log.d(TAG, "Accessibility event: View focused") // Kann sehr gesprächig sein
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Accessibility event: Window state changed to: ${event.className} (Package: ${event.packageName})")
                    refreshRootNode() // Wichtig für aktuelle Infos
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Log.d(TAG, "Accessibility event: Window content changed") // Sehr gesprächig, evtl. deaktivieren
                    // Refresh the root node when window state or content changes
                    // Refresh nur bei state changed oder seltener, da content changed sehr oft kommt
                    // refreshRootNode()
                }
                 AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                     // Log.d(TAG, "Accessibility event: View scrolled")
                 }
                // else -> { // Nicht nötig, wenn man nicht alle Typen loggen will
                //     // Handle all other event types
                //     Log.d(TAG, "Accessibility event: Other event type: ${AccessibilityEvent.eventTypeToString(event.eventType)}")
                // }
            }

            // Refresh the root node when window state changes (zuverlässiger als content changed)
            // if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            //     refreshRootNode()
            // }
        // }
    }


    /**
     * Refresh the root node of the accessibility tree
     */
    private fun refreshRootNode() {
        val currentTime = System.currentTimeMillis()

        // Only refresh if more than 250ms have passed since the last refresh (reduziert von 500ms)
        if (currentTime - lastRootNodeRefreshTime < 250) {
            // Log.v(TAG, "Skipping root node refresh (too soon)") // Verbose Log
            return
        }

        try {
            // Get the root node in active window
            val newRootNode = rootInActiveWindow
            if (newRootNode != null) {
                 // Recycle old root node before assigning new one
                 // rootNode?.recycle() // Vorsicht: Kann zu Problemen führen, wenn noch Referenzen bestehen
                 rootNode = newRootNode
                 lastRootNodeRefreshTime = currentTime
                 Log.d(TAG, "Root node refreshed successfully.")
            } else {
                 Log.w(TAG, "Root node refresh failed (rootInActiveWindow is null).")
            }

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
        Log.d(TAG, "Attempting to write text: \"$text\"")
        // showToast("Schreibe Text: \"$text\"", false) // Wird schon im executeCommand angezeigt

        try {
            // Refresh the root node to get the latest state
            refreshRootNode()

            // Check if root node is available
            if (rootNode == null) {
                Log.e(TAG, "Root node is null, cannot write text")
                showToast("Fehler: Aktuelle Ansicht nicht zugänglich (Root-Knoten ist null)", true)
                return
            }

            // Find the focused node (which should be an editable text field)
            val focusedNode = findFocusedEditableNode(rootNode!!) // Non-null Assertion, da oben geprüft

            if (focusedNode != null) {
                Log.d(TAG, "Found focused editable node: ${focusedNode.viewIdResourceName}")
                // showToast("Textfeld gefunden, schreibe Text...", false)

                // Set the text in the editable field
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)

                val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

                if (result) {
                    Log.i(TAG, "Successfully wrote text using ACTION_SET_TEXT: \"$text\"")
                    showToast("Text erfolgreich geschrieben: \"$text\"", false)
                } else {
                    Log.w(TAG, "ACTION_SET_TEXT failed, trying alternative method (paste).")
                    showToast("Fehler beim Schreiben, versuche Einfügen...", true)
                    tryPasteText(focusedNode, text)
                }

                // Recycle the node
                focusedNode.recycle()
            } else {
                Log.w(TAG, "No focused editable node found. Searching for any editable node.")
                // showToast("Kein fokussiertes Textfeld gefunden", true) // Kann verwirrend sein

                // Try to find any editable node
                val editableNode = findAnyEditableNode(rootNode!!) // Non-null Assertion

                if (editableNode != null) {
                    Log.d(TAG, "Found an editable node (not focused): ${editableNode.viewIdResourceName}. Trying to focus and write.")
                    showToast("Textfeld gefunden, versuche zu fokussieren...", false)

                    // Focus the node first
                    val focusResult = editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                    if (focusResult) {
                        Log.d(TAG, "Successfully focused the editable node.")
                        // Wait a tiny bit for focus to potentially settle
                        handler.postDelayed({
                            // Set the text in the now hopefully focused editable field
                            val bundle = android.os.Bundle()
                            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                            val setResult = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

                            if (setResult) {
                                Log.i(TAG, "Successfully wrote text after focusing: \"$text\"")
                                showToast("Text erfolgreich geschrieben: \"$text\"", false)
                            } else {
                                Log.w(TAG, "ACTION_SET_TEXT failed after focusing, trying paste.")
                                showToast("Fehler beim Schreiben nach Fokus, versuche Einfügen...", true)
                                tryPasteText(editableNode, text)
                            }
                            editableNode.recycle() // Recycle after use
                        }, 100) // 100ms delay

                    } else {
                        Log.e(TAG, "Failed to focus the found editable node.")
                        showToast("Fehler beim Fokussieren des Textfeldes", true)
                        editableNode.recycle() // Recycle if focus fails
                    }
                } else {
                    Log.e(TAG, "No editable node found on the screen.")
                    showToast("Kein beschreibbares Textfeld gefunden", true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing text: ${e.message}", e)
            showToast("Fehler beim Schreiben des Textes: ${e.message}", true)
        } finally {
             // Ensure root node is recycled if it was obtained locally (though refreshRootNode assigns to member)
             // rootNode?.recycle() // Careful with recycling member variables directly
        }
    }


    /**
     * Find the focused editable node in the accessibility tree
     */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Use a queue for breadth-first search, often faster for finding focused elements
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(node) // Start with the root

        while (queue.isNotEmpty()) {
            val currentNode = queue.removeFirst()

            try {
                // Check if this node is focused and editable
                if (currentNode.isFocused && isNodeEditable(currentNode)) {
                    // Found it! Return a copy.
                    return AccessibilityNodeInfo.obtain(currentNode)
                }

                // Add children to the queue
                for (i in 0 until currentNode.childCount) {
                    val child = currentNode.getChild(i)
                    if (child != null) {
                        queue.addLast(child)
                    }
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Error processing node in findFocusedEditableNode: ${e.message}")
            } finally {
                 // Recycle nodes processed in this iteration, except the root passed in
                 // if (currentNode != node) { // Avoid recycling the initial node here
                 //    currentNode.recycle()
                 // }
                 // Recycling here is complex due to queue; better to recycle the final result or none
            }
        }

        return null // Not found
    }


    /**
     * Find any editable node in the accessibility tree
     */
     private fun findAnyEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Use a queue for breadth-first search
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(node)

        while (queue.isNotEmpty()) {
            val currentNode = queue.removeFirst()

            try {
                // Check if this node is editable
                if (isNodeEditable(currentNode)) {
                    // Found one! Return a copy.
                    return AccessibilityNodeInfo.obtain(currentNode)
                }

                // Add children to the queue
                for (i in 0 until currentNode.childCount) {
                    val child = currentNode.getChild(i)
                    if (child != null) {
                        queue.addLast(child)
                    }
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Error processing node in findAnyEditableNode: ${e.message}")
            } finally {
                 // See comment in findFocusedEditableNode about recycling
            }
        }
        return null // Not found
    }


    /**
     * Check if a node is editable
     */
    private fun isNodeEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        // Check standard property first
        if (node.isEditable) return true
        // Check common class names as fallback
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
               className.contains("TextField", ignoreCase = true) // Added TextField for Compose
               // TextInputLayout is a container, not the editable field itself
               // (node.className?.contains("TextInputLayout", ignoreCase = true) == true)
    }

    /**
     * Try to paste text as an alternative method
     */
    private fun tryPasteText(node: AccessibilityNodeInfo, text: String) {
        try {
            Log.d(TAG, "Attempting to paste text: \"$text\"")

            // Set clipboard text
            val clipboardManager = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager?
            if (clipboardManager == null) {
                 Log.e(TAG, "ClipboardManager not available.")
                 showToast("Fehler: Zwischenablage nicht verfügbar.", true)
                 return
            }
            val clip = android.content.ClipData.newPlainText("ai_paste", text)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "Text copied to clipboard.")

            // Wait a moment for clipboard to update and potentially for focus to settle
            handler.postDelayed({
                // Perform PASTE action
                val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                if (pasteResult) {
                    Log.i(TAG, "Successfully pasted text using ACTION_PASTE.")
                    showToast("Text erfolgreich eingefügt: \"$text\"", false)
                } else {
                    Log.e(TAG, "ACTION_PASTE failed.")
                    showToast("Fehler beim Einfügen des Textes.", true)
                    // Optional: Try ACTION_SET_TEXT again after a delay? Might be redundant.
                }
            }, 250) // Increased delay slightly (250ms)

        } catch (e: Exception) {
            Log.e(TAG, "Error pasting text: ${e.message}", e)
            showToast("Fehler beim Einfügen des Textes: ${e.message}", true)
        }
    }


    /**
     * Find and click a button with the specified text
     */
    fun findAndClickButtonByText(buttonText: String) {
        Log.d(TAG, "Attempting to find and click button with text: \"$buttonText\"")
        // showToast("Suche Button mit Text: \"$buttonText\"", false) // Already in executeCommand

        // Refresh the root node
        refreshRootNode()

        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by text.")
            showToast("Fehler: Aktuelle Ansicht nicht zugänglich (Root-Knoten ist null)", true)
            return
        }

        // Try to find the node with the specified text
        val node = findNodeByText(rootNode!!, buttonText, true) // Search for clickable nodes first

        if (node != null) {
            Log.d(TAG, "Found clickable node with text: \"$buttonText\" (${node.viewIdResourceName})")
            // showToast("Button gefunden: \"$buttonText\"", false)

            // Add a small delay before clicking
            handler.postDelayed({
                // Perform the click
                val clickResult = performClickOnNode(node)

                if (clickResult) {
                    Log.i(TAG, "Successfully clicked on node with text: \"$buttonText\"")
                    showToast("Klick auf \"$buttonText\" erfolgreich", false)
                } else {
                    Log.w(TAG, "performClickOnNode failed for text: \"$buttonText\". Trying alternatives.")
                    showToast("Klick auf \"$buttonText\" fehlgeschlagen, versuche Alternativen...", true)
                    tryAlternativeClickMethods(node, buttonText)
                }

                // Recycle the node
                node.recycle()
            }, 150) // Reduced delay slightly (150ms)
        } else {
            Log.w(TAG, "Could not find clickable node with text: \"$buttonText\". Trying content description.")
            // showToast("Button mit Text \"$buttonText\" nicht gefunden, versuche Beschreibung...", true) // Can be noisy
            findAndClickButtonByContentDescription(buttonText)
        }
    }

    /**
     * Try alternative click methods if the standard click fails
     */
    private fun tryAlternativeClickMethods(node: AccessibilityNodeInfo, identifier: String) {
        // Try to get the bounds and tap at the center
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() > 0 && rect.height() > 0) {
            val centerX = rect.exactCenterX() // Use exact center
            val centerY = rect.exactCenterY()

            Log.d(TAG, "Trying alternative click: Tapping at center ($centerX, $centerY) for identifier \"$identifier\"")
            showToast("Versuche Tippen auf Mitte von \"$identifier\"", false)

            // Tap at the center of the button
            tapAtCoordinates(centerX, centerY)
        } else {
             Log.w(TAG, "Cannot attempt tap-by-coordinates for \"$identifier\", node has invalid bounds: $rect")
        }
    }

    /**
     * Find and click a button by content description
     */
    private fun findAndClickButtonByContentDescription(description: String) {
        Log.d(TAG, "Attempting to find and click button by content description: \"$description\"")
        // showToast("Suche Button mit Beschreibung: \"$description\"", false)

        // Check if root node is available (already checked in calling function, but good practice)
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by content description.")
            // showToast("Fehler: Root-Knoten ist nicht verfügbar", true) // Redundant toast
            return
        }

        // Try to find the node with the specified content description
        val node = findNodeByContentDescription(rootNode!!, description, true) // Search for clickable

        if (node != null) {
            Log.d(TAG, "Found clickable node with content description: \"$description\" (${node.viewIdResourceName})")
            // showToast("Button gefunden mit Beschreibung: \"$description\"", false)

            handler.postDelayed({
                val clickResult = performClickOnNode(node)
                if (clickResult) {
                    Log.i(TAG, "Successfully clicked on node with description: \"$description\"")
                    showToast("Klick auf Beschreibung \"$description\" erfolgreich", false)
                } else {
                    Log.w(TAG, "performClickOnNode failed for description: \"$description\". Trying alternatives.")
                    showToast("Klick auf Beschreibung \"$description\" fehlgeschlagen, versuche Alternativen...", true)
                    tryAlternativeClickMethods(node, description)
                }
                node.recycle()
            }, 150) // 150ms delay
        } else {
            Log.w(TAG, "Could not find clickable node with content description: \"$description\". Trying ID.")
            // showToast("Button mit Beschreibung \"$description\" nicht gefunden, versuche ID...", true)
            findAndClickButtonById(description) // Use the description as a potential ID fallback
        }
    }

    /**
     * Find and click a button by ID
     */
    private fun findAndClickButtonById(id: String) {
        Log.d(TAG, "Attempting to find and click button by ID: \"$id\"")
        // showToast("Suche Button mit ID: \"$id\"", false)

        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot find button by ID.")
            // showToast("Fehler: Root-Knoten ist nicht verfügbar", true)
            return
        }

        // Try to find the node with the specified ID
        val node = findNodeById(rootNode!!, id, true) // Search for clickable

        if (node != null) {
            Log.d(TAG, "Found clickable node with ID: \"$id\" (${node.viewIdResourceName})")
            // showToast("Button gefunden mit ID: \"$id\"", false)

            handler.postDelayed({
                val clickResult = performClickOnNode(node)
                if (clickResult) {
                    Log.i(TAG, "Successfully clicked on node with ID: \"$id\"")
                    showToast("Klick auf ID \"$id\" erfolgreich", false)
                } else {
                    Log.w(TAG, "performClickOnNode failed for ID: \"$id\". Trying alternatives.")
                    showToast("Klick auf ID \"$id\" fehlgeschlagen, versuche Alternativen...", true)
                    tryAlternativeClickMethods(node, id)
                }
                node.recycle()
            }, 150) // 150ms delay
        } else {
            Log.e(TAG, "Could not find clickable node with ID: \"$id\". Search failed.")
            showToast("Element mit ID \"$id\" nicht gefunden oder nicht klickbar.", true)
        }
    }

    /**
     * Find a node by text, optionally requiring it to be clickable.
     * Uses Breadth-First Search (BFS).
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, text: String, requireClickable: Boolean): AccessibilityNodeInfo? {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(root)
        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>() // Avoid cycles/redundancy

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (!visitedNodes.add(node)) { // If already visited, skip
                continue
            }

            try {
                // Check text match (case-insensitive, partial contains)
                if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
                    if (!requireClickable || isNodeClickable(node)) {
                        return AccessibilityNodeInfo.obtain(node) // Found it
                    }
                }

                // Add children to queue
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.addLast(child)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing node in findNodeByText: ${e.message}")
            } finally {
                 // Recycling here is complex with BFS and visited set
            }
        }
        return null // Not found
    }

    /**
     * Find a node by content description, optionally requiring it to be clickable.
     * Uses Breadth-First Search (BFS).
     */
    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, description: String, requireClickable: Boolean): AccessibilityNodeInfo? {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(root)
        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

             if (!visitedNodes.add(node)) continue

            try {
                // Check content description match
                if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
                    if (!requireClickable || isNodeClickable(node)) {
                        return AccessibilityNodeInfo.obtain(node)
                    }
                }
                // Add children
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Error processing node in findNodeByContentDescription: ${e.message}")
            }
        }
        return null
    }

    /**
     * Find a node by its resource ID, optionally requiring it to be clickable.
     * Uses Breadth-First Search (BFS).
     */
    private fun findNodeById(root: AccessibilityNodeInfo, id: String, requireClickable: Boolean): AccessibilityNodeInfo? {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(root)
        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (!visitedNodes.add(node)) continue

            try {
                // Check ID match
                val nodeId = getNodeId(node) // Extracts the ID part after '/'
                if (nodeId.equals(id, ignoreCase = true)) { // Use equals for exact ID match
                    if (!requireClickable || isNodeClickable(node)) {
                        return AccessibilityNodeInfo.obtain(node)
                    }
                }
                // Add children
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Error processing node in findNodeById: ${e.message}")
            }
        }
        return null
    }


    /**
     * Get the ID part of a node's resource name.
     */
    private fun getNodeId(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        try {
            val viewIdResourceName = node.viewIdResourceName ?: return ""
            val parts = viewIdResourceName.split("/")
            return if (parts.size > 1) parts[1] else viewIdResourceName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting node ID: ${e.message}")
            return ""
        }
    }

    /**
     * Find all interactive elements on the screen using BFS.
     */
    private fun findAllInteractiveElements(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(root)
        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (!visitedNodes.add(node)) continue

            try {
                if (isNodeInteractive(node)) {
                    elements.add(AccessibilityNodeInfo.obtain(node)) // Add a copy
                }
                // Add children
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Error processing node in findAllInteractiveElements: ${e.message}")
            }
        }
        return elements
    }


    /**
     * Check if a node is interactive (clickable, focusable, editable, etc.).
     */
    private fun isNodeInteractive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.isClickable ||
               node.isLongClickable ||
               node.isCheckable ||
               node.isEditable ||
               node.isFocusable || // Focusable elements are often interactive
               node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) // Check actions
    }

    /**
     * Check if a node is clickable or has a clickable ancestor.
     */
     private fun isNodeClickable(node: AccessibilityNodeInfo?): Boolean {
         var current: AccessibilityNodeInfo? = node
         while (current != null) {
             if (current.isClickable || current.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                 // current.recycle() // Don't recycle here, let the caller handle it
                 return true
             }
             val parent = current.parent
             // if (current != node) current.recycle() // Recycle intermediate parents if needed, but risky
             current = parent
         }
         return false
     }

    /**
     * Perform a click on a node, trying the node itself and then its parents.
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var targetNode: AccessibilityNodeInfo? = node
        while (targetNode != null) {
            try {
                if (targetNode.isClickable || targetNode.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                    Log.d(TAG, "Performing ACTION_CLICK on node: ${targetNode.viewIdResourceName}")
                    val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    // targetNode.recycle() // Let caller recycle the original node
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing click on node ${targetNode.viewIdResourceName}: ${e.message}")
                // Continue searching up the hierarchy
            }
            val parent = targetNode.parent
            // if (targetNode != node) targetNode.recycle() // Risky
            targetNode = parent
        }
        Log.w(TAG, "Could not perform click; no clickable node found in hierarchy for initial node: ${node.viewIdResourceName}")
        return false
    }


    /**
     * Tap at the specified coordinates
     */
    fun tapAtCoordinates(x: Float, y: Float) {
        Log.d(TAG, "Attempting to tap at coordinates: ($x, $y)")
        // showToast("Tippen auf Koordinaten: ($x, $y)", false) // Already in executeCommand

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture dispatch not supported below Android N.")
            showToast("Gestensteuerung nicht unterstützt (Android Version zu alt).", true)
            return
        }

        try {
            // Create a tap gesture (short duration)
            val path = Path()
            // Ensure coordinates are within screen bounds (basic check)
            val safeX = x.coerceIn(0f, resources.displayMetrics.widthPixels.toFloat() - 1)
            val safeY = y.coerceIn(0f, resources.displayMetrics.heightPixels.toFloat() - 1)
            path.moveTo(safeX, safeY)

            val gestureBuilder = GestureDescription.Builder()
            // Standard tap duration: 1ms down, 1ms up, total duration ~100ms seems reasonable
            val stroke = GestureDescription.StrokeDescription(path, 0, 100L)
            gestureBuilder.addStroke(stroke)

            // Dispatch the gesture
            val dispatchResult = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Tap gesture completed at ($safeX, $safeY)")
                    showToast("Tippen auf ($x, $y) erfolgreich", false) // Show original coords
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Tap gesture cancelled at ($safeX, $safeY).")
                    showToast("Tippen auf ($x, $y) abgebrochen.", true)
                    // Optional: Retry with longer duration?
                    // tapAtCoordinatesWithLongerDuration(x, y)
                }
            }, handler) // Use the service's handler

            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch tap gesture.")
                showToast("Fehler beim Senden der Tipp-Geste.", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates ($x, $y): ${e.message}", e)
            showToast("Fehler beim Tippen auf Koordinaten: ${e.message}", true)
        }
    }


    /**
     * Tap at the specified coordinates with a longer duration (simulates long press)
     */
    private fun tapAtCoordinatesWithLongerDuration(x: Float, y: Float) {
        Log.d(TAG, "Attempting long tap at coordinates: ($x, $y)")
        // showToast("Versuche langes Tippen auf: ($x, $y)", false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // Log.e(TAG, "Gesture dispatch not supported below Android N.") // Redundant log
            // showToast("Gestensteuerung nicht unterstützt.", true) // Redundant toast
            return
        }

        try {
            // Create a long tap gesture
            val path = Path()
            val safeX = x.coerceIn(0f, resources.displayMetrics.widthPixels.toFloat() - 1)
            val safeY = y.coerceIn(0f, resources.displayMetrics.heightPixels.toFloat() - 1)
            path.moveTo(safeX, safeY)

            val gestureBuilder = GestureDescription.Builder()
            // Duration for long press (e.g., 600ms)
            val stroke = GestureDescription.StrokeDescription(path, 0, 600L)
            gestureBuilder.addStroke(stroke)

            val dispatchResult = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.i(TAG, "Long tap gesture completed at ($safeX, $safeY)")
                    showToast("Langes Tippen auf ($x, $y) erfolgreich", false)
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "Long tap gesture cancelled at ($safeX, $safeY).")
                    showToast("Langes Tippen auf ($x, $y) abgebrochen.", true)
                }
            }, handler)

            if (!dispatchResult) {
                Log.e(TAG, "Failed to dispatch long tap gesture.")
                showToast("Fehler beim Senden der langen Tipp-Geste.", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error long tapping at coordinates ($x, $y): ${e.message}", e)
            showToast("Fehler beim langen Tippen: ${e.message}", true)
        }
    }

    /**
     * Open an app by name or package name using multiple methods to ensure success
     */
    fun openApp(nameOrPackage: String) {
        Log.d(TAG, "Attempting to open app with name or package: $nameOrPackage")
        // showToast("Versuche App zu öffnen: $nameOrPackage", false) // Already in executeCommand

        try {
            // First, try to resolve the app name to a package name if needed
            val packageName = appNamePackageMapper.getPackageName(nameOrPackage) ?: nameOrPackage

            // If the input was an app name, show the resolved package name
            if (packageName != nameOrPackage) {
                Log.d(TAG, "Resolved app name '$nameOrPackage' to package name '$packageName'")
                // showToast("App-Name '$nameOrPackage' aufgelöst zu '$packageName'", false)
            }

            // Get the app name for display purposes
            val appName = appNamePackageMapper.getAppName(packageName) ?: packageName // Fallback to package name

            // Try multiple methods to open the app
            if (openAppUsingLaunchIntent(packageName, appName)) {
                Log.i(TAG, "Successfully opened app '$appName' using launch intent.")
                showToast("App geöffnet: $appName", false)
                return
            }
             if (openAppUsingMainActivity(packageName, appName)) {
                 Log.i(TAG, "Successfully opened app '$appName' using main activity intent.")
                 showToast("App geöffnet: $appName", false)
                 return
             }
             if (openAppUsingQueryIntentActivities(packageName, appName)) {
                 Log.i(TAG, "Successfully opened app '$appName' using query intent activities.")
                 showToast("App geöffnet: $appName", false)
                 return
             }


            // If all methods failed, show an error
            Log.e(TAG, "All methods failed to open app for package: $packageName (Input: '$nameOrPackage')")
            showToast("Fehler: App '$nameOrPackage' nicht gefunden oder kann nicht geöffnet werden.", true)

        } catch (e: Exception) {
            Log.e(TAG, "Error opening app '$nameOrPackage': ${e.message}", e)
            showToast("Fehler beim Öffnen der App '$nameOrPackage': ${e.message}", true)
        }
    }

    /**
     * Try to open an app using the standard launch intent
     */
    private fun openAppUsingLaunchIntent(packageName: String, appName: String): Boolean {
        try {
            Log.d(TAG, "Trying method 1: Open app using launch intent for $packageName")
            val packageManager = applicationContext.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                applicationContext.startActivity(launchIntent)
                return true
            } else {
                Log.w(TAG, "Method 1 failed: No launch intent found for $packageName.")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Method 1 error for $packageName: ${e.message}", e)
            return false
        }
    }

    /**
     * Try to open an app by directly starting its main activity
     */
    private fun openAppUsingMainActivity(packageName: String, appName: String): Boolean {
        try {
            Log.d(TAG, "Trying method 2: Open app using main activity query for $packageName")
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName) // Limit query to the specific package

            val packageManager = applicationContext.packageManager
            // Use queryIntentActivities with MATCH_DEFAULT_ONLY flag for better filtering
            val resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

            if (resolveInfoList.isNotEmpty()) {
                // Usually the first one is the main launcher activity
                val activityInfo = resolveInfoList[0].activityInfo
                val componentName = ComponentName(activityInfo.packageName, activityInfo.name)

                Log.d(TAG, "Method 2: Found main activity ${componentName.flattenToString()}")

                val launchIntent = Intent(Intent.ACTION_MAIN)
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                launchIntent.component = componentName
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)

                applicationContext.startActivity(launchIntent)
                return true
            } else {
                Log.w(TAG, "Method 2 failed: No main activity found via query for $packageName.")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Method 2 error for $packageName: ${e.message}", e)
            return false
        }
    }


    /**
     * Try to open an app by querying all activities and starting the first one (less reliable)
     */
    private fun openAppUsingQueryIntentActivities(packageName: String, appName: String): Boolean {
        try {
            Log.d(TAG, "Trying method 3: Open app using generic query intent activities for $packageName")
            val intent = Intent(Intent.ACTION_MAIN, null) // More generic intent
            intent.setPackage(packageName)

            val packageManager = applicationContext.packageManager
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0) // Query all matching activities

            if (resolveInfoList.isNotEmpty()) {
                // Get the first activity found (might not be the main launcher)
                val activityInfo = resolveInfoList[0].activityInfo
                val componentName = ComponentName(activityInfo.packageName, activityInfo.name)

                Log.d(TAG, "Method 3: Found activity ${componentName.flattenToString()} (might not be main)")

                val launchIntent = Intent()
                launchIntent.component = componentName
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)

                applicationContext.startActivity(launchIntent)
                return true
            }

            Log.w(TAG, "Method 3 failed: No activities found via generic query for $packageName.")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Method 3 error for $packageName: ${e.message}", e)
            return false
        }
    }


    /**
     * Take a screenshot using the global action.
     */
    fun takeScreenshot() {
        Log.d(TAG, "Attempting to take screenshot")
        // showToast("Nehme Screenshot auf...", false) // Already in executeCommand

        try {
            // Capture screen information *before* taking the screenshot
            val screenInfo = captureScreenInformation()

            // Try to use the global action to take a screenshot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                if (result) {
                    Log.i(TAG, "Successfully initiated screenshot using GLOBAL_ACTION_TAKE_SCREENSHOT.")
                    showToast("Screenshot wird aufgenommen...", false)

                    // Wait a moment for the screenshot to be saved, then retrieve it
                    handler.postDelayed({
                        retrieveLatestScreenshot(screenInfo)
                    }, 1500) // Wait 1.5 seconds for the screenshot to be saved (increased slightly)

                    return // Success
                } else {
                    Log.e(TAG, "GLOBAL_ACTION_TAKE_SCREENSHOT returned false.")
                    // Fall through to error message
                }
            } else {
                 Log.e(TAG, "GLOBAL_ACTION_TAKE_SCREENSHOT not available on this Android version (SDK < P).")
                 // Fall through to error message
            }

            // If the global action failed or is not available, show an error
            showToast("Fehler: Screenshot konnte nicht aufgenommen werden (Aktion fehlgeschlagen oder nicht verfügbar).", true)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}", e)
            showToast("Fehler beim Aufnehmen des Screenshots: ${e.message}", true)
        }
    }


    /**
     * Capture information about all interactive elements on the screen
     */
    private fun captureScreenInformation(): String {
        Log.d(TAG, "Capturing screen information...")

        // Refresh the root node to ensure we have the latest information
        refreshRootNode()

        // Check if root node is available
        if (rootNode == null) {
            Log.e(TAG, "Root node is null, cannot capture screen information.")
            return "FEHLER: Keine Bildschirminformationen verfügbar (Root-Knoten ist null)."
        }

        // Build a string with information about all interactive elements
        val screenInfo = StringBuilder()
        screenInfo.append("Aktuelle Bildschirmelemente:\n")
        var elementCount = 0

        // Use BFS to traverse the tree and capture info
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(rootNode!!) // Non-null asserted
        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (!visitedNodes.add(node)) continue

            try {
                // Capture info only for potentially interesting nodes (visible and interactive or has text/desc)
                if (node.isVisibleToUser && (isNodeInteractive(node) || !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty())) {
                    elementCount++
                    screenInfo.append("${elementCount}. ")

                    // Get element ID if available
                    val elementId = getNodeId(node)
                    if (elementId.isNotEmpty()) {
                        screenInfo.append("ID: \"$elementId\" ")
                    }

                    // Add element text if available
                    if (!node.text.isNullOrEmpty()) {
                        // Limit text length to avoid overly long strings
                        val shortText = node.text.toString().take(80) + if (node.text.length > 80) "..." else ""
                        screenInfo.append("Text: \"${shortText}\" ")
                    }

                    // Add content description if available
                    if (!node.contentDescription.isNullOrEmpty()) {
                         val shortDesc = node.contentDescription.toString().take(80) + if (node.contentDescription.length > 80) "..." else ""
                        screenInfo.append("Beschreibung: \"${shortDesc}\" ")
                    }

                    // Add element class name (simple name)
                    val className = node.className?.toString()?.substringAfterLast('.') ?: "Unbekannt"
                    screenInfo.append("Typ: $className ")


                    // Add element properties concisely
                    val props = mutableListOf<String>()
                    if (node.isClickable) props.add("klickbar")
                    // if (node.isLongClickable) props.add("lang-klickbar") // Less common
                    if (node.isCheckable) props.add(if (node.isChecked) "aktiviert" else "auswählbar")
                    if (node.isEditable) props.add("editierbar")
                    if (node.isFocusable) props.add("fokussierbar")
                    if (node.isFocused) props.add("fokussiert")
                    // if (node.isPassword) props.add("passwort") // Sensitive
                    if (node.isScrollable) props.add("scrollfähig")
                    if (!node.isEnabled) props.add("deaktiviert") // Important state

                    if (props.isNotEmpty()) {
                        screenInfo.append("[${props.joinToString(", ")}] ")
                    }

                    // Add element bounds
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    screenInfo.append("Pos: (${rect.centerX()}, ${rect.centerY()})") // Center point is often useful

                    // Add inferred name (optional, can be noisy)
                    // val buttonName = getButtonName(node)
                    // if (buttonName.isNotEmpty() && buttonName != node.text?.toString() && buttonName != node.contentDescription?.toString()) {
                    //     screenInfo.append(" Name?: \"$buttonName\"")
                    // }

                    screenInfo.append("\n")
                }

                // Add children to queue
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Error processing node during screen capture: ${e.message}")
            } finally {
                 // node.recycle() // Avoid recycling within BFS loop
            }
        }


        if (elementCount == 0) {
            screenInfo.append("Keine relevanten interaktiven Elemente gefunden.")
        } else {
             // Optional: Add summary
             // screenInfo.insert(0, "Bildschirminformationen (${elementCount} Elemente gefunden):\n")
        }

        // Recycle the root node copy if obtained locally, otherwise rely on system management
        // rootNode?.recycle() // Careful

        Log.d(TAG, "Screen information captured (${elementCount} elements).")
        return screenInfo.toString()
    }


    /**
     * Try to infer a button name from a node (Simplified)
     */
    private fun getButtonName(node: AccessibilityNodeInfo): String {
        // Prioritize text, then content description
        if (!node.text.isNullOrEmpty()) return node.text.toString()
        if (!node.contentDescription.isNullOrEmpty()) return node.contentDescription.toString()

        // Use ID as fallback if it looks like a name
        val nodeId = getNodeId(node)
        if (nodeId.isNotEmpty() && nodeId.matches(Regex(".*[a-zA-Z].*"))) { // Contains letters
             // Basic formatting
             return nodeId.replace("_", " ")
                        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

        // Generic name based on class
        val className = node.className?.toString() ?: ""
        if (className.contains("Button", ignoreCase = true)) return "Button"
        if (className.contains("EditText", ignoreCase = true)) return "Textfeld"
        if (className.contains("ImageView", ignoreCase = true)) return "Bild"
        if (className.contains("CheckBox", ignoreCase = true)) return "Checkbox"
        if (className.contains("Switch", ignoreCase = true)) return "Schalter"

        return "" // No suitable name found
    }

    /**
     * Retrieve the latest screenshot
     */
    private fun retrieveLatestScreenshot(screenInfo: String) {
        try {
            Log.d(TAG, "Retrieving latest screenshot...")
            // showToast("Suche nach aufgenommenem Screenshot...", false)

            // Find the latest screenshot file (improved logic)
            val screenshotFile = findLatestScreenshotFile()

            if (screenshotFile != null && screenshotFile.exists()) {
                Log.i(TAG, "Found screenshot file: ${screenshotFile.absolutePath}")
                showToast("Screenshot gefunden: ${screenshotFile.name}", false)

                // Convert file to URI using FileProvider for broader compatibility (if possible)
                // val screenshotUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", screenshotFile)
                // Fallback to Uri.fromFile if FileProvider setup is complex
                val screenshotUri = Uri.fromFile(screenshotFile)

                // Add the screenshot to the conversation with screen information
                addScreenshotToConversation(screenshotUri, screenInfo)
            } else {
                Log.e(TAG, "Could not find the saved screenshot file after waiting.")
                showToast("Fehler: Screenshot-Datei nicht gefunden. Bitte Berechtigungen prüfen.", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving screenshot: ${e.message}", e)
            showToast("Fehler beim Abrufen des Screenshots: ${e.message}", true)
        }
    }

    /**
     * Find the latest screenshot file in standard locations (Improved)
     */
     private fun findLatestScreenshotFile(): File? {
        val possibleDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.resolve("Screenshots"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.resolve("Screenshots"),
            // Less common locations
            Environment.getExternalStorageDirectory()?.resolve("Pictures/Screenshots"),
            Environment.getExternalStorageDirectory()?.resolve("DCIM/Screenshots"),
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.resolve("Screenshots") // App specific
        )

        var latestFile: File? = null
        var latestModifiedTime = 0L
        val maxAgeMillis = 15000L // Allow files up to 15 seconds old
        val currentTime = System.currentTimeMillis()

        Log.d(TAG, "Searching for screenshots in: ${possibleDirs.map { it.absolutePath }}")

        for (dir in possibleDirs) {
            if (dir.exists() && dir.isDirectory) {
                // Log.v(TAG, "Checking directory: ${dir.absolutePath}") // Verbose
                try {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile &&
                            (file.name.contains("screenshot", ignoreCase = true) || file.name.startsWith("IMG_")) && // Broader name check
                            (file.name.endsWith(".png") || file.name.endsWith(".jpg") || file.name.endsWith(".jpeg")) &&
                            file.lastModified() > (currentTime - maxAgeMillis) && // Check age first
                            file.lastModified() > latestModifiedTime)
                        {
                            latestFile = file
                            latestModifiedTime = file.lastModified()
                            // Log.v(TAG, "Found candidate: ${file.name} (Age: ${currentTime - file.lastModified()}ms)") // Verbose
                        }
                    }
                } catch (secEx: SecurityException) {
                     Log.w(TAG, "Permission denied accessing directory: ${dir.absolutePath}")
                } catch (ex: Exception) {
                     Log.e(TAG, "Error listing files in ${dir.absolutePath}: ${ex.message}")
                }
            }
        }

        if (latestFile != null) {
             Log.i(TAG, "Latest screenshot found: ${latestFile?.absolutePath} (Age: ${currentTime - latestModifiedTime}ms)")
        } else {
             Log.w(TAG, "No recent screenshot file found in standard directories. Trying MediaStore.")
             // Try MediaStore as a fallback (can be slower)
             latestFile = findLatestScreenshotViaMediaStore(maxAgeMillis)
             if (latestFile != null) {
                  Log.i(TAG, "Latest screenshot found via MediaStore: ${latestFile?.absolutePath}")
             } else {
                  Log.w(TAG, "No recent screenshot found via MediaStore either.")
             }
        }

        return latestFile
    }


    /**
     * Find the latest screenshot using MediaStore (Improved)
     */
    private fun findLatestScreenshotViaMediaStore(maxAgeMillis: Long): File? {
        var latestFile: File? = null
        try {
            val contentResolver = applicationContext.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED // Use DATE_ADDED for query sorting
            )
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val minTimeSeconds = currentTimeSeconds - (maxAgeMillis / 1000)

            // Query for recent screenshots (case-insensitive name check)
            // Use DATE_ADDED for filtering recent files
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf("%screenshot%", minTimeSeconds.toString())
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC" // Get the newest first

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val filePath = cursor.getString(dataColumnIndex)
                    val file = File(filePath)
                    if (file.exists()) {
                        // Double-check modification time if possible, though DATE_ADDED should be sufficient
                        if (file.lastModified() > (System.currentTimeMillis() - maxAgeMillis)) {
                             latestFile = file
                        } else {
                             Log.w(TAG, "MediaStore file ${file.name} is older than max age based on lastModified.")
                        }
                    } else {
                         Log.w(TAG, "MediaStore path points to non-existent file: $filePath")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding screenshot via MediaStore: ${e.message}", e)
        }
        return latestFile
    }


    /**
     * Add the screenshot to the conversation with screen information
     */
    private fun addScreenshotToConversation(screenshotUri: Uri, screenInfo: String) {
        try {
            Log.d(TAG, "Adding screenshot to conversation: $screenshotUri")

            // Get the MainActivity instance
            val mainActivity = MainActivity.getInstance()
            if (mainActivity == null) {
                Log.e(TAG, "MainActivity instance is null, cannot add screenshot to conversation.")
                showToast("Fehler: App nicht im Vordergrund, Screenshot kann nicht verarbeitet werden.", true)
                return
            }

            // Get the PhotoReasoningViewModel from MainActivity
            val photoReasoningViewModel = mainActivity.getPhotoReasoningViewModel()
            if (photoReasoningViewModel == null) {
                Log.e(TAG, "PhotoReasoningViewModel is null, cannot add screenshot to conversation.")
                showToast("Fehler: Chat-Ansicht nicht aktiv, Screenshot kann nicht verarbeitet werden.", true)
                return
            }

            // Add the screenshot to the conversation via the ViewModel
            // Pass context from the service
            photoReasoningViewModel.addScreenshotToConversation(screenshotUri, applicationContext, screenInfo)

            Log.d(TAG, "Screenshot passed to PhotoReasoningViewModel.")
            // Toast wird jetzt im ViewModel angezeigt
            // showToast("Screenshot mit Bildschirminformationen zur Konversation hinzugefügt", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding screenshot to conversation: ${e.message}", e)
            showToast("Fehler beim Hinzufügen des Screenshots zur Konversation: ${e.message}", true)
        }
    }

    /**
     * Press the home button
     */
    fun pressHomeButton() {
        Log.d(TAG, "Attempting to press home button")
        // showToast("Drücke Home-Button...", false) // Already in executeCommand
        try {
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
            if (result) {
                Log.i(TAG, "Successfully pressed home button.")
                showToast("Home-Button gedrückt", false)
            } else {
                Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_HOME) failed.")
                showToast("Fehler beim Drücken des Home-Buttons", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing home button: ${e.message}", e)
            showToast("Fehler beim Drücken des Home-Buttons: ${e.message}", true)
        }
    }

    /**
     * Press the back button
     */
    fun pressBackButton() {
        Log.d(TAG, "Attempting to press back button")
        // showToast("Drücke Zurück-Button...", false) // Already in executeCommand
        try {
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
            if (result) {
                Log.i(TAG, "Successfully pressed back button.")
                showToast("Zurück-Button gedrückt", false)
            } else {
                Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) failed.")
                showToast("Fehler beim Drücken des Zurück-Buttons", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing back button: ${e.message}", e)
            showToast("Fehler beim Drücken des Zurück-Buttons: ${e.message}", true)
        }
    }

    /**
     * Show recent apps overview
     */
    fun showRecentApps() {
        Log.d(TAG, "Attempting to show recent apps")
        // showToast("Öffne Übersicht der letzten Apps...", false) // Already in executeCommand
        try {
            val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
            if (result) {
                Log.i(TAG, "Successfully showed recent apps.")
                showToast("Übersicht der letzten Apps geöffnet", false)
            } else {
                Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_RECENTS) failed.")
                showToast("Fehler beim Öffnen der App-Übersicht", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recent apps: ${e.message}", e)
            showToast("Fehler beim Öffnen der App-Übersicht: ${e.message}", true)
        }
    }

    // --- Scrollfunktionen mit Gesten ---

    /**
     * Perform a swipe gesture.
     *
     * @param startX Start X coordinate.
     * @param startY Start Y coordinate.
     * @param endX End X coordinate.
     * @param endY End Y coordinate.
     * @param duration Duration of the swipe in milliseconds.
     * @param actionName Name of the action for logging/toast messages.
     */
    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long, actionName: String) {
        Log.d(TAG, "Performing swipe: $actionName from ($startX, $startY) to ($endX, $endY) in ${duration}ms")
        // showToast("Versuche $actionName...", false) // Already in executeCommand

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture dispatch not supported below Android N.")
            showToast("Gestensteuerung nicht unterstützt.", true)
            return
        }

        try {
            // Create path for the gesture
            val swipePath = Path()
            // Coerce coordinates to be within bounds
            val metrics = resources.displayMetrics
            val safeStartX = startX.coerceIn(0f, metrics.widthPixels.toFloat() - 1)
            val safeStartY = startY.coerceIn(0f, metrics.heightPixels.toFloat() - 1)
            val safeEndX = endX.coerceIn(0f, metrics.widthPixels.toFloat() - 1)
            val safeEndY = endY.coerceIn(0f, metrics.heightPixels.toFloat() - 1)

            swipePath.moveTo(safeStartX, safeStartY)
            swipePath.lineTo(safeEndX, safeEndY)

            // Build the gesture
            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(swipePath, 0, duration)
            gestureBuilder.addStroke(stroke)

            // Dispatch the gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        Log.i(TAG, "$actionName gesture completed.")
                        showToast("$actionName erfolgreich", false)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.w(TAG, "$actionName gesture cancelled.")
                        showToast("$actionName abgebrochen", true)
                    }
                },
                handler // Use the service's handler
            )

            if (!result) {
                Log.e(TAG, "Failed to dispatch $actionName gesture.")
                showToast("Fehler beim Ausführen: $actionName", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing $actionName gesture: ${e.message}", e)
            showToast("Fehler bei $actionName: ${e.message}", true)
        }
    }

    /** Scroll down (swipe up) */
    fun scrollDown() {
        val metrics = resources.displayMetrics
        val startX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.7f // Start lower
        val endY = metrics.heightPixels * 0.3f   // End higher
        performSwipe(startX, startY, startX, endY, 300L, "Scrollen nach unten")
    }

    /** Scroll up (swipe down) */
    fun scrollUp() {
        val metrics = resources.displayMetrics
        val startX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.3f // Start higher
        val endY = metrics.heightPixels * 0.7f   // End lower
        performSwipe(startX, startY, startX, endY, 300L, "Scrollen nach oben")
    }

    /** Scroll left (swipe right) */
    fun scrollLeft() {
        val metrics = resources.displayMetrics
        val startX = metrics.widthPixels * 0.7f // Start right
        val startY = metrics.heightPixels / 2f
        val endX = metrics.widthPixels * 0.3f   // End left
        performSwipe(startX, startY, endX, startY, 300L, "Scrollen nach links")
    }

    /** Scroll right (swipe left) */
    fun scrollRight() {
        val metrics = resources.displayMetrics
        val startX = metrics.widthPixels * 0.3f // Start left
        val startY = metrics.heightPixels / 2f
        val endX = metrics.widthPixels * 0.7f   // End right
        performSwipe(startX, startY, endX, startY, 300L, "Scrollen nach rechts")
    }

    /** Scroll down from specific coordinates */
    fun scrollDown(x: Float, y: Float, distance: Float, duration: Long) {
        performSwipe(x, y, x, y - distance, duration, "Scrollen nach unten von ($x, $y)")
    }

    /** Scroll up from specific coordinates */
    fun scrollUp(x: Float, y: Float, distance: Float, duration: Long) {
        performSwipe(x, y, x, y + distance, duration, "Scrollen nach oben von ($x, $y)")
    }

    /** Scroll left from specific coordinates */
    fun scrollLeft(x: Float, y: Float, distance: Float, duration: Long) {
        performSwipe(x, y, x - distance, y, duration, "Scrollen nach links von ($x, $y)")
    }

    /** Scroll right from specific coordinates */
    fun scrollRight(x: Float, y: Float, distance: Float, duration: Long) {
        performSwipe(x, y, x + distance, y, duration, "Scrollen nach rechts von ($x, $y)")
    }


    /**
     * Show a toast message (internal helper)
     */
    private fun showToast(message: String, isError: Boolean) {
        // Use the static companion object method for consistency
        Companion.showToast(message, isError)
    }
}
