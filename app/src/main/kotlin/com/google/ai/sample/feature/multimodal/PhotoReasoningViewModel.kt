package com.google.ai.sample.feature.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.Content
import com.google.ai.sample.MainActivity
import com.google.ai.sample.PhotoReasoningApplication
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.ChatHistoryPreferences
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import com.google.ai.sample.util.SystemMessagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoReasoningViewModel(
    private val generativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "PhotoReasoningViewModel"

    private val _uiState: MutableStateFlow<PhotoReasoningUiState> =
        MutableStateFlow(PhotoReasoningUiState.Initial)
    val uiState: StateFlow<PhotoReasoningUiState> =
        _uiState.asStateFlow()
        
    // Keep track of the latest screenshot URI
    private var latestScreenshotUri: Uri? = null
    
    // Keep track of the latest screenshot bitmap
    private var latestScreenshotBitmap: Bitmap? = null
    
    // Keep track of the current selected images
    private var currentSelectedImages: List<Bitmap> = emptyList()
    
    // Keep track of the current user input
    private var currentUserInput: String = ""
    
    // Keep track of detected commands
    private val _detectedCommands = MutableStateFlow<List<Command>>(emptyList())
    val detectedCommands: StateFlow<List<Command>> = _detectedCommands.asStateFlow()
    
    // Keep track of command execution status
    private val _commandExecutionStatus = MutableStateFlow<String>("")
    val commandExecutionStatus: StateFlow<String> = _commandExecutionStatus.asStateFlow()
    
    // System message state
    private val _systemMessage = MutableStateFlow<String>("")
    val systemMessage: StateFlow<String> = _systemMessage.asStateFlow()
    
    // Chat history state
    private val _chatState = ChatState()
    val chatMessages: List<PhotoReasoningMessage>
        get() = _chatState.messages
    
    // Chat history state flow for UI updates
    private val _chatMessagesFlow = MutableStateFlow<List<PhotoReasoningMessage>>(emptyList())
    val chatMessagesFlow: StateFlow<List<PhotoReasoningMessage>> = _chatMessagesFlow.asStateFlow()
    
    // ImageLoader and ImageRequestBuilder for processing images
    private var imageLoader: ImageLoader? = null
    private var imageRequestBuilder: ImageRequest.Builder? = null
    
    // Chat instance for maintaining conversation context
    private var chat = generativeModel.startChat(
        history = emptyList()
    )

    fun reason(
        userInput: String,
        selectedImages: List<Bitmap>
    ) {
        _uiState.value = PhotoReasoningUiState.Loading
        
        // Get the system message
        val systemMessageText = _systemMessage.value
        
        // Create the prompt with system message if available
        val prompt = if (systemMessageText.isNotBlank()) {
            "System Message: $systemMessageText\n\nLook at the image(s), and then answer the following question: $userInput"
        } else {
            "Look at the image(s), and then answer the following question: $userInput"
        }
        
        // Store the current user input and selected images
        currentUserInput = userInput
        currentSelectedImages = selectedImages
        
        // Clear previous commands
        _detectedCommands.value = emptyList()
        _commandExecutionStatus.value = ""
        
        // Add user message to chat history
        val userMessage = PhotoReasoningMessage(
            text = userInput,
            participant = PhotoParticipant.USER,
            isPending = false
        )
        _chatState.addMessage(userMessage)
        _chatMessagesFlow.value = chatMessages
        
        // Add AI message with pending status
        val pendingAiMessage = PhotoReasoningMessage(
            text = "",
            participant = PhotoParticipant.MODEL,
            isPending = true
        )
        _chatState.addMessage(pendingAiMessage)
        _chatMessagesFlow.value = chatMessages

        // Use application scope to prevent cancellation when app goes to background
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.IO) {
            try {
                // Create content with the current images and prompt
                val inputContent = content {
                    for (bitmap in selectedImages) {
                        image(bitmap)
                    }
                    text(prompt)
                }

                // Send the message to the chat to maintain context
                val response = chat.sendMessage(inputContent)
                
                var outputContent = ""
                
                // Process the response
                response.text?.let { modelResponse ->
                    outputContent = modelResponse
                    
                    withContext(Dispatchers.Main) {
                        _uiState.value = PhotoReasoningUiState.Success(outputContent)
                        
                        // Update the AI message in chat history
                        updateAiMessage(outputContent)
                        
                        // Parse and execute commands from the response
                        processCommands(modelResponse)
                    }
                }
                
                // Save chat history after successful response
                withContext(Dispatchers.Main) {
                    saveChatHistory(MainActivity.getInstance()?.applicationContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating content: ${e.message}", e)
                
                withContext(Dispatchers.Main) {
                    _uiState.value = PhotoReasoningUiState.Error(e.localizedMessage ?: "Unknown error")
                    _commandExecutionStatus.value = "Fehler bei der Generierung: ${e.localizedMessage}"
                    
                    // Update chat with error message
                    _chatState.replaceLastPendingMessage()
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = e.localizedMessage ?: "Unknown error",
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = chatMessages
                    
                    // Save chat history even after error
                    saveChatHistory(MainActivity.getInstance()?.applicationContext)
                }
            }
        }
    }
    
    /**
     * Update the AI message in chat history
     */
    private fun updateAiMessage(text: String) {
        // Find the last AI message and update it
        val messages = _chatState.messages.toMutableList()
        val lastAiMessageIndex = messages.indexOfLast { it.participant == PhotoParticipant.MODEL }
        
        if (lastAiMessageIndex >= 0) {
            val updatedMessage = messages[lastAiMessageIndex].copy(text = text, isPending = false)
            messages[lastAiMessageIndex] = updatedMessage
            
            // Clear and re-add all messages to maintain order
            _chatState.clearMessages()
            messages.forEach { _chatState.addMessage(it) }
            
            // Update the flow
            _chatMessagesFlow.value = chatMessages
            
            // Save chat history after updating message
            saveChatHistory(MainActivity.getInstance()?.applicationContext)
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
                    _detectedCommands.value = commands
                    
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
                    
                    // Update status
                    _commandExecutionStatus.value = "Befehle erkannt: ${commandDescriptions.joinToString(", ")}"
                    
                    // Show toast with detected commands
                    val mainActivity = MainActivity.getInstance()
                    mainActivity?.updateStatusMessage(
                        "Befehle erkannt: ${commandDescriptions.joinToString(", ")}",
                        false
                    )
                    
                    // Check if accessibility service is enabled
                    val isServiceEnabled = mainActivity?.let { 
                        ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it)
                    } ?: false
                    
                    if (!isServiceEnabled) {
                        Log.e(TAG, "Accessibility service is not enabled")
                        _commandExecutionStatus.value = "Accessibility Service ist nicht aktiviert. Bitte aktivieren Sie den Service in den Einstellungen."
                        
                        // Show toast
                        mainActivity?.updateStatusMessage(
                            "Accessibility Service ist nicht aktiviert. Bitte aktivieren Sie den Service in den Einstellungen.",
                            true
                        )
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
                        kotlinx.coroutines.delay(500)
                    }
                    
                    // Update status to show all commands were executed
                    _commandExecutionStatus.value = "Alle Befehle ausgeführt"
                    
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
     * Add a screenshot to the conversation
     * 
     * @param screenshotUri URI of the screenshot
     * @param context Application context
     * @param screenInfo Optional information about screen elements (null if not available)
     */
    fun addScreenshotToConversation(
        screenshotUri: Uri, 
        context: android.content.Context,
        screenInfo: String? = null
    ) {
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "Adding screenshot to conversation: $screenshotUri")
                
                // Store the latest screenshot URI
                latestScreenshotUri = screenshotUri
                
                // Initialize ImageLoader and ImageRequestBuilder if needed
                if (imageLoader == null) {
                    imageLoader = ImageLoader.Builder(context).build()
                }
                if (imageRequestBuilder == null) {
                    imageRequestBuilder = ImageRequest.Builder(context)
                }
                
                // Update status
                _commandExecutionStatus.value = "Verarbeite Screenshot..."
                
                // Show toast
                Toast.makeText(context, "Verarbeite Screenshot...", Toast.LENGTH_SHORT).show()
                
                // Load the image from URI
                val request = imageRequestBuilder!!
                    .data(screenshotUri)
                    .size(1024, 1024) // Resize to reasonable dimensions
                    .precision(Precision.EXACT)
                    .build()
                
                val result = withContext(Dispatchers.IO) {
                    val result = imageLoader!!.execute(request)
                    if (result is SuccessResult) {
                        return@withContext (result.drawable as BitmapDrawable).bitmap
                    } else {
                        throw Exception("Failed to load image")
                    }
                }
                
                // Store the latest screenshot bitmap
                latestScreenshotBitmap = result
                
                // Create message text with screen info if available
                val messageText = if (screenInfo != null && screenInfo.isNotEmpty()) {
                    "Screenshot aufgenommen\n\n$screenInfo"
                } else {
                    "Screenshot aufgenommen"
                }
                
                // Add user message to chat history
                val userMessage = PhotoReasoningMessage(
                    text = messageText,
                    participant = PhotoParticipant.USER,
                    isPending = false
                )
                _chatState.addMessage(userMessage)
                _chatMessagesFlow.value = chatMessages
                
                // Save chat history
                saveChatHistory(context)
                
                // Update the current selected images to include only the latest screenshot
                currentSelectedImages = listOf(result)
                
                // Update status
                _commandExecutionStatus.value = "Screenshot zur Konversation hinzugefügt"
                
                // Show toast
                Toast.makeText(context, "Screenshot zur Konversation hinzugefügt", Toast.LENGTH_SHORT).show()
                
                // Rebuild chat history to ensure context is maintained
                rebuildChatHistory()
            } catch (e: Exception) {
                Log.e(TAG, "Error adding screenshot to conversation: ${e.message}", e)
                _commandExecutionStatus.value = "Fehler beim Hinzufügen des Screenshots: ${e.message}"
                
                // Show toast
                Toast.makeText(context, "Fehler beim Hinzufügen des Screenshots: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Add a screenshot to the conversation (overloaded method for Bitmap)
     * 
     * @param screenshot Bitmap of the screenshot
     */
    fun addScreenshotToConversation(screenshot: Bitmap) {
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "Adding screenshot to conversation (Bitmap)")
                
                // Store the latest screenshot bitmap
                latestScreenshotBitmap = screenshot
                
                // Update status
                _commandExecutionStatus.value = "Verarbeite Screenshot..."
                
                // Create message text
                val messageText = "Screenshot aufgenommen"
                
                // Add user message to chat history
                val userMessage = PhotoReasoningMessage(
                    text = messageText,
                    participant = PhotoParticipant.USER,
                    isPending = false
                )
                _chatState.addMessage(userMessage)
                _chatMessagesFlow.value = chatMessages
                
                // Save chat history
                saveChatHistory(MainActivity.getInstance()?.applicationContext)
                
                // Update the current selected images to include only the latest screenshot
                currentSelectedImages = listOf(screenshot)
                
                // Update status
                _commandExecutionStatus.value = "Screenshot zur Konversation hinzugefügt"
                
                // Rebuild chat history to ensure context is maintained
                rebuildChatHistory()
            } catch (e: Exception) {
                Log.e(TAG, "Error adding screenshot to conversation: ${e.message}", e)
                _commandExecutionStatus.value = "Fehler beim Hinzufügen des Screenshots: ${e.message}"
            }
        }
    }
    
    /**
     * Load system message from preferences
     */
    fun loadSystemMessage(context: android.content.Context) {
        val systemMessageText = SystemMessagePreferences.loadSystemMessage(context)
        _systemMessage.value = systemMessageText
        Log.d(TAG, "System message loaded: $systemMessageText")
    }
    
    /**
     * Update system message and save to preferences
     */
    fun updateSystemMessage(message: String, context: android.content.Context) {
        _systemMessage.value = message
        SystemMessagePreferences.saveSystemMessage(context, message)
        Log.d(TAG, "System message updated: $message")
    }
    
    /**
     * Save chat history to SharedPreferences
     */
    private fun saveChatHistory(context: android.content.Context?) {
        if (context == null) {
            Log.e(TAG, "Cannot save chat history: context is null")
            return
        }
        
        try {
            val messages = chatMessages
            ChatHistoryPreferences.saveChatMessages(context, messages)
            Log.d(TAG, "Chat history saved: ${messages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat history: ${e.message}", e)
        }
    }
    
    /**
     * Load chat history from SharedPreferences
     */
    fun loadChatHistory(context: android.content.Context) {
        try {
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
    fun clearChatHistory(context: android.content.Context) {
        try {
            // Clear current messages
            _chatState.clearMessages()
            
            // Update the flow
            _chatMessagesFlow.value = chatMessages
            
            // Clear from SharedPreferences
            ChatHistoryPreferences.clearChatMessages(context)
            
            // Reset the chat
            chat = generativeModel.startChat(
                history = emptyList()
            )
            
            // Clear latest screenshot references
            latestScreenshotUri = null
            latestScreenshotBitmap = null
            currentSelectedImages = emptyList()
            
            Log.d(TAG, "Chat history cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing chat history: ${e.message}", e)
        }
    }
    
    /**
     * Rebuild chat history for the chat object
     */
    private fun rebuildChatHistory() {
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.IO) {
            try {
                // Get the current messages
                val messages = _chatState.messages
                
                // Group messages by participant
                val groupedMessages = mutableListOf<Pair<PhotoParticipant, String>>()
                
                var currentParticipant: PhotoParticipant? = null
                var currentText = StringBuilder()
                
                for (message in messages) {
                    // Skip pending messages
                    if (message.isPending) continue
                    
                    // Skip error messages
                    if (message.participant == PhotoParticipant.ERROR) continue
                    
                    if (currentParticipant == null) {
                        // First message
                        currentParticipant = message.participant
                        currentText.append(message.text)
                    } else if (currentParticipant == message.participant) {
                        // Same participant, append text
                        currentText.append("\n\n").append(message.text)
                    } else {
                        // Different participant, add the current group and start a new one
                        groupedMessages.add(Pair(currentParticipant, currentText.toString()))
                        currentParticipant = message.participant
                        currentText = StringBuilder(message.text)
                    }
                }
                
                // Add the last group if any
                if (currentParticipant != null) {
                    groupedMessages.add(Pair(currentParticipant, currentText.toString()))
                }
                
                // Build the history for the chat
                val history = mutableListOf<Content>()
                
                for ((participant, text) in groupedMessages) {
                    val role = when (participant) {
                        PhotoParticipant.USER -> "user"
                        PhotoParticipant.MODEL -> "model"
                        else -> continue // Skip other participants
                    }
                    
                    val content = content {
                        this.role = role
                        text(text)
                    }
                    
                    history.add(content)
                }
                
                // Reset the chat with the new history
                withContext(Dispatchers.Main) {
                    chat = generativeModel.startChat(
                        history = history
                    )
                    
                    Log.d(TAG, "Chat history rebuilt with ${history.size} messages")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rebuilding chat history: ${e.message}", e)
            }
        }
    }
    
    /**
     * Chat state class to manage messages
     */
    inner class ChatState {
        private val _messages = mutableListOf<PhotoReasoningMessage>()
        val messages: List<PhotoReasoningMessage>
            get() = _messages.toList()
        
        fun addMessage(message: PhotoReasoningMessage) {
            _messages.add(message)
        }
        
        fun clearMessages() {
            _messages.clear()
        }
        
        fun replaceLastPendingMessage() {
            val lastPendingIndex = _messages.indexOfLast { it.isPending }
            if (lastPendingIndex >= 0) {
                _messages.removeAt(lastPendingIndex)
            }
        }
        
        fun removeLastMessage() {
            if (_messages.isNotEmpty()) {
                _messages.removeAt(_messages.size - 1)
            }
        }
    }
}
