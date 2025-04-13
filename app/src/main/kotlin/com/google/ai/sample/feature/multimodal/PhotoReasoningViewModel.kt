package com.google.ai.sample.feature.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.sample.MainActivity
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.ChatHistoryPreferences
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Base64

class PhotoReasoningViewModel(
    private val generativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "PhotoReasoningViewModel"
    
    // UI state
    private val _uiState = MutableStateFlow<PhotoReasoningUiState>(PhotoReasoningUiState.Initial)
    val uiState: StateFlow<PhotoReasoningUiState> = _uiState.asStateFlow()
    
    // Chat state
    private val _chatState = PhotoReasoningChatState()
    val chatMessages: List<PhotoReasoningMessage>
        get() = _chatState.messages
    
    // Chat messages flow
    private val _chatMessagesFlow = MutableStateFlow<List<PhotoReasoningMessage>>(emptyList())
    val chatMessagesFlow: StateFlow<List<PhotoReasoningMessage>> = _chatMessagesFlow.asStateFlow()
    
    // System message
    private val _systemMessage = MutableStateFlow("")
    val systemMessage: StateFlow<String> = _systemMessage.asStateFlow()
    
    // Keep track of detected commands
    private val _detectedCommands = MutableStateFlow<List<Command>>(emptyList())
    val detectedCommands: StateFlow<List<Command>> = _detectedCommands.asStateFlow()
    
    // Keep track of command execution status
    private val _commandExecutionStatus = MutableStateFlow<String>("")
    val commandExecutionStatus: StateFlow<String> = _commandExecutionStatus.asStateFlow()
    
    // Shared preferences key for system message
    private val SYSTEM_MESSAGE_PREF_KEY = "system_message"
    
    /**
     * Update the system message
     */
    fun updateSystemMessage(message: String, context: Context) {
        _systemMessage.value = message
        
        // Save to SharedPreferences
        val prefs = context.getSharedPreferences("photo_reasoning_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(SYSTEM_MESSAGE_PREF_KEY, message).apply()
        
        Log.d(TAG, "System message updated: $message")
    }
    
    /**
     * Load the system message from SharedPreferences
     */
    fun loadSystemMessage(context: Context) {
        val prefs = context.getSharedPreferences("photo_reasoning_prefs", Context.MODE_PRIVATE)
        val message = prefs.getString(SYSTEM_MESSAGE_PREF_KEY, "") ?: ""
        _systemMessage.value = message
        
        Log.d(TAG, "System message loaded: $message")
    }
    
    /**
     * Process an image and text query
     */
    fun reason(text: String, images: List<Bitmap>) {
        viewModelScope.launch {
            try {
                // Update UI state to loading
                _uiState.value = PhotoReasoningUiState.Loading
                
                // Clear previous commands
                _detectedCommands.value = emptyList()
                _commandExecutionStatus.value = ""
                
                // Add user message to chat
                val userMessage = PhotoReasoningMessage(
                    text = text,
                    participant = PhotoParticipant.USER,
                    isPending = false,
                    imageUris = emptyList() // We don't store the actual URIs in the chat history
                )
                _chatState.addMessage(userMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Add a pending message for the model
                val pendingMessage = PhotoReasoningMessage(
                    text = "Thinking...",
                    participant = PhotoParticipant.MODEL,
                    isPending = true
                )
                _chatState.addMessage(pendingMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Build the prompt with system message if available
                val systemMessageText = _systemMessage.value
                val prompt = if (systemMessageText.isNotBlank()) {
                    "$systemMessageText\n\nUser query: $text"
                } else {
                    text
                }
                
                // Create content parts
                val contentBuilder = content {
                    role = "user"
                    
                    // Add text
                    text(prompt)
                    
                    // Add images
                    for (image in images) {
                        image(image)
                    }
                }
                
                // Get response from model
                val response = generativeModel.generateContent(contentBuilder)
                
                // Remove the pending message
                _chatState.removeLastMessage()
                
                // Get the text from the response
                val responseText = response.text ?: "No response from model"
                
                // Add model message to chat
                val modelMessage = PhotoReasoningMessage(
                    text = responseText,
                    participant = PhotoParticipant.MODEL,
                    isPending = false
                )
                _chatState.addMessage(modelMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Update UI state to success
                _uiState.value = PhotoReasoningUiState.Success(responseText)
                
                // Save chat history
                saveChatHistory(context = MainActivity.getInstance())
                
                // Process commands in the response
                processCommands(responseText)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in reason: ${e.message}", e)
                
                // Remove the pending message if it exists
                if (_chatState.messages.lastOrNull()?.isPending == true) {
                    _chatState.removeLastMessage()
                }
                
                // Add error message to chat
                val errorMessage = PhotoReasoningMessage(
                    text = "Error: ${e.message}",
                    participant = PhotoParticipant.ERROR
                )
                _chatState.addMessage(errorMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Update UI state to error
                _uiState.value = PhotoReasoningUiState.Error(e)
                
                // Update command execution status
                _commandExecutionStatus.value = "Fehler: ${e.message}"
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
                    val commandDescriptions = commands.map { command -> 
                        when (command) {
                            is Command.ClickButton -> "Klick auf Button: \"${command.buttonText}\""
                            is Command.TapCoordinates -> "Tippen auf Koordinaten: (${command.x}, ${command.y})"
                            is Command.TakeScreenshot -> "Screenshot aufnehmen"
                            is Command.OpenApp -> "App öffnen: \"${command.appName}\""
                            is Command.PressBack -> "Zurück-Taste drücken"
                            is Command.PressHome -> "Home-Taste drücken"
                            is Command.PullStatusBarDown -> "Statusleiste herunterziehen"
                            is Command.PullStatusBarDownTwice -> "Statusleiste zweimal herunterziehen"
                            is Command.PushStatusBarUp -> "Statusleiste hochschieben"
                            is Command.ScrollDown -> "Nach unten scrollen"
                            is Command.ScrollUp -> "Nach oben scrollen"
                            is Command.ScrollLeft -> "Nach links scrollen"
                            is Command.ScrollRight -> "Nach rechts scrollen"
                            is Command.ShowRecentApps -> "Letzte Apps anzeigen"
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
                            is Command.OpenApp -> "App öffnen: \"${command.appName}\""
                            is Command.PressBack -> "Zurück-Taste drücken"
                            is Command.PressHome -> "Home-Taste drücken"
                            is Command.PullStatusBarDown -> "Statusleiste herunterziehen"
                            is Command.PullStatusBarDownTwice -> "Statusleiste zweimal herunterziehen"
                            is Command.PushStatusBarUp -> "Statusleiste hochschieben"
                            is Command.ScrollDown -> "Nach unten scrollen"
                            is Command.ScrollUp -> "Nach oben scrollen"
                            is Command.ScrollLeft -> "Nach links scrollen"
                            is Command.ScrollRight -> "Nach rechts scrollen"
                            is Command.ShowRecentApps -> "Letzte Apps anzeigen"
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
    
    /**
     * Save chat history to SharedPreferences
     */
    private fun saveChatHistory(context: Context?) {
        try {
            if (context == null) {
                Log.e(TAG, "Cannot save chat history: context is null")
                return
            }
            
            val messages = chatMessages
            
            // Save to SharedPreferences using the correct method name
            ChatHistoryPreferences.saveChatMessages(context, messages)
            
            Log.d(TAG, "Chat history saved: ${messages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat history: ${e.message}", e)
        }
    }
    
    /**
     * Load chat history from SharedPreferences
     */
    private fun loadChatHistory(context: android.content.Context) {
        try {
            // Load from SharedPreferences using the correct method name
            val messages = ChatHistoryPreferences.loadChatMessages(context)
            
            // Clear current messages
            _chatState.clearMessages()
            
            // Add loaded messages
            for (message in messages) {
                _chatState.addMessage(message)
            }
            
            // Update the flow
            _chatMessagesFlow.value = chatMessages
            
            Log.d(TAG, "Chat history loaded: ${messages.size} messages")
            
            // Rebuild chat history to ensure context is maintained
            rebuildChatHistory()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chat history: ${e.message}", e)
        }
    }
    
    /**
     * Clear chat history
     */
    fun clearChatHistory(context: Context) {
        try {
            // Clear from SharedPreferences using the correct method name
            ChatHistoryPreferences.clearChatMessages(context)
            
            // Clear current messages
            _chatState.clearMessages()
            
            // Update the flow
            _chatMessagesFlow.value = chatMessages
            
            Log.d(TAG, "Chat history cleared")
            
            // Show toast
            val mainActivity = MainActivity.getInstance()
            mainActivity?.updateStatusMessage(
                "Chat-Verlauf gelöscht",
                false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing chat history: ${e.message}", e)
        }
    }
    
    /**
     * Rebuild chat history for the model
     */
    private fun rebuildChatHistory() {
        // This would rebuild the chat history for the model if needed
        // For now, we're just using the messages directly
    }
    
    /**
     * Add a screenshot to the conversation
     */
    fun addScreenshotToConversation(screenshot: Bitmap) {
        viewModelScope.launch {
            try {
                // Create a new message with the screenshot
                val message = PhotoReasoningMessage(
                    text = "Screenshot",
                    participant = PhotoParticipant.USER,
                    isPending = false,
                    imageUris = emptyList() // We don't store the actual URIs
                )
                
                // Add to chat
                _chatState.addMessage(message)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Save chat history
                saveChatHistory(context = MainActivity.getInstance())
                
                // Process the screenshot with AI
                processScreenshot(screenshot)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error adding screenshot to conversation: ${e.message}", e)
            }
        }
    }
    
    /**
     * Process a screenshot with AI
     */
    private fun processScreenshot(screenshot: Bitmap) {
        viewModelScope.launch {
            try {
                // Update UI state to loading
                _uiState.value = PhotoReasoningUiState.Loading
                
                // Add a pending message for the model
                val pendingMessage = PhotoReasoningMessage(
                    text = "Analyzing screenshot...",
                    participant = PhotoParticipant.MODEL,
                    isPending = true
                )
                _chatState.addMessage(pendingMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Build the prompt with system message if available
                val systemMessageText = _systemMessage.value
                val prompt = if (systemMessageText.isNotBlank()) {
                    "$systemMessageText\n\nWhat do you see in this screenshot? Provide a detailed description and identify any UI elements that could be interacted with."
                } else {
                    "What do you see in this screenshot? Provide a detailed description and identify any UI elements that could be interacted with."
                }
                
                // Create content parts
                val contentBuilder = content {
                    role = "user"
                    
                    // Add text
                    text(prompt)
                    
                    // Add screenshot
                    image(screenshot)
                }
                
                // Get response from model
                val response = generativeModel.generateContent(contentBuilder)
                
                // Remove the pending message
                _chatState.removeLastMessage()
                
                // Get the text from the response
                val responseText = response.text ?: "No response from model"
                
                // Add model message to chat
                val modelMessage = PhotoReasoningMessage(
                    text = responseText,
                    participant = PhotoParticipant.MODEL,
                    isPending = false
                )
                _chatState.addMessage(modelMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Update UI state to success
                _uiState.value = PhotoReasoningUiState.Success(responseText)
                
                // Save chat history
                saveChatHistory(context = MainActivity.getInstance())
                
                // Process commands in the response
                processCommands(responseText)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing screenshot: ${e.message}", e)
                
                // Remove the pending message if it exists
                if (_chatState.messages.lastOrNull()?.isPending == true) {
                    _chatState.removeLastMessage()
                }
                
                // Add error message to chat
                val errorMessage = PhotoReasoningMessage(
                    text = "Error analyzing screenshot: ${e.message}",
                    participant = PhotoParticipant.ERROR
                )
                _chatState.addMessage(errorMessage)
                
                // Update the flow
                _chatMessagesFlow.value = chatMessages
                
                // Update UI state to error
                _uiState.value = PhotoReasoningUiState.Error(e)
            }
        }
    }
}
