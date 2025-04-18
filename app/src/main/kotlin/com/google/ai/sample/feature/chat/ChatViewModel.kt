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

    // --- NEU: StateFlow für den aktuellen Modellnamen ---
    private val _currentModelName = MutableStateFlow(initialGenerativeModel.modelName)
    val currentModelName: StateFlow<String> = _currentModelName.asStateFlow()

    // --- ENTFERNT: Separater Trigger nicht mehr nötig ---
    // private val _modelUpdateTrigger = MutableStateFlow(0)
    // val modelUpdateTrigger: StateFlow<Int> = _modelUpdateTrigger.asStateFlow()

    private var chat = currentGenerativeModel.startChat(
        history = listOf(
            // Initial history can be empty or loaded differently if needed
        )
    )

    private val _uiState: MutableStateFlow<ChatUiState> =
        MutableStateFlow(ChatUiState(chat.history.mapNotNull { content ->
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
        // Setze den initialen Namen im StateFlow (redundant, da schon im Konstruktor, aber sicher)
        _currentModelName.value = initialGenerativeModel.modelName
    }

    // Keep track of detected commands
    private val _detectedCommands = MutableStateFlow<List<Command>>(emptyList())
    val detectedCommands: StateFlow<List<Command>> = _detectedCommands.asStateFlow()

    // Keep track of command execution status
    private val _commandExecutionStatus = MutableStateFlow<String>("")
    val commandExecutionStatus: StateFlow<String> = _commandExecutionStatus.asStateFlow()

    fun sendMessage(userMessage: String) {
        _uiState.value.addMessage(
            ChatMessage(
                text = userMessage,
                participant = Participant.USER,
                isPending = true
            )
        )
        _detectedCommands.value = emptyList()
        _commandExecutionStatus.value = ""

        viewModelScope.launch {
            try {
                Log.d(TAG, "sendMessage: Using chat instance with model: ${currentGenerativeModel.modelName}")
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

                    val currentCommands = _detectedCommands.value.toMutableList()
                    currentCommands.addAll(commands)
                    _detectedCommands.value = currentCommands

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
                            is Command.UseHighReasoningModel -> "Wechsle zu ${ModelPreferences.HIGH_REASONING_MODEL}"
                            is Command.UseLowReasoningModel -> "Wechsle zu ${ModelPreferences.LOW_REASONING_MODEL}"
                        }
                    }
                     val mainActivity = MainActivity.getInstance()
                     mainActivity?.updateStatusMessage(
                        "Befehle erkannt: ${commandDescriptions.joinToString(", ")}",
                        false
                    )
                    _commandExecutionStatus.value = "Befehle erkannt: ${commandDescriptions.joinToString(", ")}"

                    val isServiceEnabled = mainActivity?.let { ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it) } ?: false
                    if (!isServiceEnabled) { /* ... */ return@launch }
                    if (!ScreenOperatorAccessibilityService.isServiceAvailable()) { /* ... */ return@launch }

                     commands.forEachIndexed { index, command ->
                        Log.d(TAG, "Executing command: $command")
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
                             is Command.UseHighReasoningModel -> "Wechsle zu ${ModelPreferences.HIGH_REASONING_MODEL}"
                             is Command.UseLowReasoningModel -> "Wechsle zu ${ModelPreferences.LOW_REASONING_MODEL}"
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
        // --- GEÄNDERT: Verwende den StateFlow für die Prüfung ---
        if (_currentModelName.value == newModelName) {
            Log.i(TAG, "Model $newModelName is already active. No update needed.")
            _commandExecutionStatus.value = "Modell '$newModelName' ist bereits aktiv."
             MainActivity.getInstance()?.updateStatusMessage("Modell '$newModelName' ist bereits aktiv.", false)
            return
        }

        viewModelScope.launch {
            Log.i(TAG, "Updating GenerativeModel from ${_currentModelName.value} to: $newModelName")
            val context = MainActivity.getInstance()?.applicationContext
            if (context == null) {
                Log.e(TAG, "Cannot update model, application context is null.")
                 _commandExecutionStatus.value = "Fehler: Kontext für Modellwechsel nicht verfügbar."
                return@launch
            }

            try {
                val config = generationConfig { temperature = 0.0f }
                currentGenerativeModel = GenerativeModel(
                    modelName = newModelName,
                    apiKey = BuildConfig.apiKey,
                    generationConfig = config
                )
                val currentHistory = chat.history
                chat = currentGenerativeModel.startChat(history = currentHistory)
                Log.i(TAG, "GenerativeModel and chat instance updated successfully. History preserved.")

                ModelPreferences.saveModelName(context, newModelName)

                // --- NEU: Aktualisiere den StateFlow mit dem neuen Namen ---
                _currentModelName.value = newModelName

                 _uiState.value.addMessage(
                     ChatMessage(
                         text = "Chat-Modell wurde zu '$newModelName' gewechselt.",
                         participant = Participant.MODEL,
                         isPending = false
                     )
                 )

                // --- ENTFERNT: Trigger nicht mehr nötig ---
                // _modelUpdateTrigger.value++

                 _commandExecutionStatus.value = "Modell erfolgreich zu '$newModelName' gewechselt."

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
