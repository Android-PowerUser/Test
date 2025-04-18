// --- START OF FILE ChatViewModel.kt.txt ---
package com.google.ai.sample.feature.chat

import android.content.Context // Import für Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.sample.BuildConfig
import com.google.ai.sample.MainActivity
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import com.google.ai.sample.util.ModelPreferences // Import für ModelPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

class ChatViewModel(
    initialGenerativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "ChatViewModel"

    private var currentGenerativeModel: GenerativeModel = initialGenerativeModel
        private set

    private var chat = currentGenerativeModel.startChat(
        history = listOf(
            // Initial history can be empty or loaded differently if needed
            // content(role = "user") { text("Hello, I have 2 dogs in my house.") },
            // content(role = "model") { text("Great to meet you. What would you like to know?") }
        )
    )

    private val _uiState: MutableStateFlow<ChatUiState> =
        MutableStateFlow(ChatUiState(chat.history.mapNotNull { content ->
            // Map the initial messages safely
            content.parts.firstOrNull()?.asTextOrNull()?.let { text ->
                 ChatMessage(
                    text = text,
                    participant = if (content.role == "user") Participant.USER else Participant.MODEL,
                    isPending = false
                )
            }
        }))
    val uiState: StateFlow<ChatUiState> =
        _uiState.asStateFlow()

    init {
        Log.i(TAG, "ViewModel initialized with model: ${initialGenerativeModel.modelName}")
        // Optional: Lade hier eine gespeicherte Chat-History, falls gewünscht
    }

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
                Log.d(TAG, "sendMessage: Using chat instance with model: ${chat.generativeModel.modelName}") // Log hinzugefügt
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
                Log.e(TAG, "Error sending message: ${e.message}", e)
                _uiState.value.replaceLastPendingMessage()
                _uiState.value.addMessage(
                    ChatMessage(
                        text = e.localizedMessage ?: "Unknown error sending message",
                        participant = Participant.ERROR
                    )
                )
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
                val commands = CommandParser.parseCommands(text)

                if (commands.isNotEmpty()) {
                    Log.d(TAG, "Found ${commands.size} commands in response")
                    // ... (Rest der Logik zum Anzeigen/Ausführen der Befehle bleibt gleich) ...

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
                            is Command.UseHighReasoningModel -> "Wechsle zu ${ModelPreferences.HIGH_REASONING_MODEL}" // Namen aus Konstante
                            is Command.UseLowReasoningModel -> "Wechsle zu ${ModelPreferences.LOW_REASONING_MODEL}" // Namen aus Konstante
                        }
                    }
                     val mainActivity = MainActivity.getInstance() // Hole Activity für Context/Toast
                     mainActivity?.updateStatusMessage(
                        "Befehle erkannt: ${commandDescriptions.joinToString(", ")}",
                        false
                    )
                    _commandExecutionStatus.value = "Befehle erkannt: ${commandDescriptions.joinToString(", ")}"

                    // --- Accessibility Service Checks (unverändert) ---
                    val isServiceEnabled = mainActivity?.let { ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it) } ?: false
                    if (!isServiceEnabled) { /* ... Fehlerbehandlung ... */ return@launch }
                    if (!ScreenOperatorAccessibilityService.isServiceAvailable()) { /* ... Fehlerbehandlung ... */ return@launch }

                    // --- Execute Commands (unverändert, außer Beschreibung) ---
                     commands.forEachIndexed { index, command ->
                        Log.d(TAG, "Executing command: $command")
                        val commandDescription = when (command) {
                             // ... (wie oben, aber mit Konstanten für Modellnamen) ...
                             is Command.UseHighReasoningModel -> "Wechsle zu ${ModelPreferences.HIGH_REASONING_MODEL}"
                             is Command.UseLowReasoningModel -> "Wechsle zu ${ModelPreferences.LOW_REASONING_MODEL}"
                             else -> "..." // Andere Befehle
                        }
                        _commandExecutionStatus.value = "Führe aus: $commandDescription (${index + 1}/${commands.size})"
                        mainActivity?.updateStatusMessage("Führe aus: $commandDescription", false)
                        ScreenOperatorAccessibilityService.executeCommand(command)
                        kotlinx.coroutines.delay(800)
                    }
                    _commandExecutionStatus.value = "Alle Befehle ausgeführt: ${commandDescriptions.joinToString(", ")}"
                    mainActivity?.updateStatusMessage("Alle Befehle ausgeführt", false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing commands: ${e.message}", e)
                _commandExecutionStatus.value = "Fehler bei der Befehlsverarbeitung: ${e.message}"
                MainActivity.getInstance()?.updateStatusMessage(
                    "Fehler bei der Befehlsverarbeitung: ${e.message}", true
                )
            }
        }
    }

    /**
     * Updates the GenerativeModel instance used by this ViewModel and restarts the chat.
     *
     * @param newModelName The name of the new model to use.
     */
    fun updateGenerativeModel(newModelName: String) {
        // Verhindere unnötigen Wechsel, wenn das Modell bereits aktiv ist
        if (currentGenerativeModel.modelName == newModelName) {
            Log.i(TAG, "Model $newModelName is already active. No update needed.")
            // Optional: Kurze Bestätigung an den User
            _commandExecutionStatus.value = "Modell '$newModelName' ist bereits aktiv."
             MainActivity.getInstance()?.updateStatusMessage("Modell '$newModelName' ist bereits aktiv.", false)
            return
        }

        viewModelScope.launch {
            Log.i(TAG, "Updating GenerativeModel from ${currentGenerativeModel.modelName} to: $newModelName")
            // Hole Context sicher
            val context = MainActivity.getInstance()?.applicationContext

            if (context == null) {
                Log.e(TAG, "Cannot update model, application context is null.")
                 _commandExecutionStatus.value = "Fehler: Kontext für Modellwechsel nicht verfügbar."
                return@launch
            }

            try {
                val config = generationConfig {
                    temperature = 0.0f
                }
                // Erstelle eine NEUE Instanz mit dem neuen Namen
                currentGenerativeModel = GenerativeModel(
                    modelName = newModelName,
                    apiKey = BuildConfig.apiKey,
                    generationConfig = config
                )

                // Speichere die aktuelle History, bevor der Chat neu gestartet wird
                val currentHistory = chat.history

                // Initialisiere die 'chat'-Instanz neu
                chat = currentGenerativeModel.startChat(
                    history = currentHistory // Übernehme die bisherige History
                )
                Log.i(TAG, "GenerativeModel and chat instance updated successfully. History preserved.")

                // --- GEÄNDERT: Speichere den neuen Namen persistent ---
                ModelPreferences.saveModelName(context, newModelName)

                // Füge eine Nachricht zum Chat hinzu, um den Wechsel anzuzeigen
                 _uiState.value.addMessage(
                     ChatMessage(
                         text = "Chat-Modell wurde zu '$newModelName' gewechselt.",
                         participant = Participant.MODEL, // Oder eine neue Participant.SYSTEM Rolle
                         isPending = false
                     )
                 )
                 _commandExecutionStatus.value = "Modell erfolgreich zu '$newModelName' gewechselt." // Update Status

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update GenerativeModel to $newModelName: ${e.message}", e)
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
// --- END OF FILE ChatViewModel.kt.txt ---
