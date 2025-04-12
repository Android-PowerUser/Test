package com.google.ai.sample.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
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
    generativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "ChatViewModel"
    
    private val chat = generativeModel.startChat(
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
                _uiState.value.replaceLastPendingMessage()
                _uiState.value.addMessage(
                    ChatMessage(
                        text = e.localizedMessage,
                        participant = Participant.ERROR
                    )
                )
                
                // Update command execution status
                _commandExecutionStatus.value = "Fehler: ${e.localizedMessage}"
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
}
