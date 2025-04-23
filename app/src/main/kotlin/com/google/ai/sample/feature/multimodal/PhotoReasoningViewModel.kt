package com.google.ai.sample.feature.multimodal

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
import com.google.ai.sample.ApiKeyManager
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
import java.io.IOException
import java.net.HttpURLConnection

class PhotoReasoningViewModel(
    private var generativeModel: GenerativeModel,
    private val apiKeyManager: ApiKeyManager? = null
) : ViewModel() {
    private val TAG = "PhotoReasoningViewModel"

    private val _uiState: MutableStateFlow<PhotoReasoningUiState> =
        MutableStateFlow(PhotoReasoningUiState.Initial)
    val uiState: StateFlow<PhotoReasoningUiState> =
        _uiState.asStateFlow()
        
    // Keep track of the latest screenshot URI
    private var latestScreenshotUri: Uri? = null
    
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
    
    // Maximum number of retry attempts for API calls
    private val MAX_RETRY_ATTEMPTS = 3

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
            // Create content with the current images and prompt
            val inputContent = content {
                for (bitmap in selectedImages) {
                    image(bitmap)
                }
                text(prompt)
            }
            
            // Try to send the message with retry logic for 503 errors
            sendMessageWithRetry(inputContent, 0)
        }
    }
    
    /**
     * Send a message to the AI with retry logic for 503 errors
     * 
     * @param inputContent The content to send
     * @param retryCount The current retry count
     */
    private suspend fun sendMessageWithRetry(inputContent: Content, retryCount: Int) {
        try {
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
            
            // Check if this is a 503 error
            if (is503Error(e) && apiKeyManager != null) {
                // Mark the current API key as failed
                val currentKey = MainActivity.getInstance()?.getCurrentApiKey()
                if (currentKey != null) {
                    apiKeyManager.markKeyAsFailed(currentKey)
                    
                    // Check if we have only one key or if all keys are failed
                    val keyCount = apiKeyManager.getKeyCount()
                    val allKeysFailed = apiKeyManager.areAllKeysFailed()
                    
                    if (keyCount <= 1 || (allKeysFailed && retryCount >= MAX_RETRY_ATTEMPTS)) {
                        // Only one key available or all keys have failed after multiple attempts
                        // Show the special message about waiting or adding more keys
                        withContext(Dispatchers.Main) {
                            _uiState.value = PhotoReasoningUiState.Error(
                                "Server überlastet (503). Bitte warten Sie 45 Sekunden oder fügen Sie weitere API-Schlüssel hinzu."
                            )
                            _commandExecutionStatus.value = "Alle API-Schlüssel erschöpft. Bitte warten Sie 45 Sekunden oder fügen Sie weitere API-Schlüssel hinzu."
                            
                            // Update chat with error message
                            _chatState.replaceLastPendingMessage()
                            _chatState.addMessage(
                                PhotoReasoningMessage(
                                    text = "Server überlastet (503). Bitte warten Sie 45 Sekunden oder fügen Sie weitere API-Schlüssel hinzu.",
                                    participant = PhotoParticipant.ERROR
                                )
                            )
                            _chatMessagesFlow.value = chatMessages
                            
                            // Reset failed keys to allow retry after waiting
                            apiKeyManager.resetFailedKeys()
                            
                            // Show toast
                            MainActivity.getInstance()?.updateStatusMessage(
                                "Alle API-Schlüssel erschöpft. Bitte warten Sie 45 Sekunden oder fügen Sie weitere API-Schlüssel hinzu.",
                                true
                            )
                            
                            // Save chat history even after error
                            saveChatHistory(MainActivity.getInstance()?.applicationContext)
                        }
                    } else if (retryCount < MAX_RETRY_ATTEMPTS) {
                        // Try to switch to the next available key
                        val nextKey = apiKeyManager.switchToNextAvailableKey()
                        if (nextKey != null) {
                            withContext(Dispatchers.Main) {
                                _commandExecutionStatus.value = "API-Schlüssel erschöpft. Wechsle zu nächstem Schlüssel..."
                                
                                // Show toast
                                MainActivity.getInstance()?.updateStatusMessage(
                                    "API-Schlüssel erschöpft (503). Wechsle zu nächstem Schlüssel...",
                                    false
                                )
                            }
                            
                            // Create a new GenerativeModel with the new API key
                            val config = generativeModel.config
                            generativeModel = GenerativeModel(
                                modelName = generativeModel.modelName,
                                apiKey = nextKey,
                                generationConfig = config.generationConfig
                            )
                            
                            // Create a new chat instance with the new model
                            chat = generativeModel.startChat(
                                history = emptyList()
                            )
                            
                            // Retry the request with the new API key
                            sendMessageWithRetry(inputContent, retryCount + 1)
                            return
                        }
                    }
                }
            }
            
            // If we get here, either it's not a 503 error, or we've exhausted all API keys, or reached max retries
            withContext(Dispatchers.Main) {
                _uiState.value = PhotoReasoningUiState.Error(e.localizedMessage ?: "Unknown error")
                _commandExecutionStatus.value = "Fehler bei der Generierung: ${e.localizedMessage}"
                
                // Update chat with error message
                _chatState.replaceLastPendingMessage()
                _chatState.addMessage(
                    PhotoReasoningMessage(
                        text = if (is503Error(e)) 
                            "Server überlastet (503). Bitte warten Sie 45 Sekunden oder fügen Sie weitere API-Schlüssel hinzu." 
                        else 
                            e.localizedMessage ?: "Unknown error",
                        participant = PhotoParticipant.ERROR
                    )
                )
                _chatMessagesFlow.value = chatMessages
                
                // Save chat history even after error
                saveChatHistory(MainActivity.getInstance()?.applicationContext)
            }
        }
    }
    
    /**
     * Check if an exception represents a 503 Service Unavailable error
     * 
     * @param e The exception to check
     * @return True if the exception represents a 503 error, false otherwise
     */
    private fun is503Error(e: Exception): Boolean {
        // Check for HTTP 503 error in the exception message
        val message = e.message?.lowercase() ?: ""
        
        // Check for common 503 error patterns
        return message.contains("503") || 
               message.contains("service unavailable") || 
               message.contains("server unavailable") ||
               message.contains("server error") ||
               (e is IOException && message.contains("server"))
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
     * Update the system message
     */
    fun updateSystemMessage(message: String, context: android.content.Context) {
        _systemMessage.value = message
        
        // Save to SharedPreferences for persistence
        SystemMessagePreferences.saveSystemMessage(context, message)
    }
    
    /**
     * Load the system message from SharedPreferences
     */
    fun loadSystemMessage(context: android.content.Context) {
        val message = SystemMessagePreferences.loadSystemMessage(context)
        _systemMessage.value = message
        
        // Also load chat history
        loadChatHistory(context)
    }
    
    /**
     * Process commands found in the AI response
     */
    private fun processCommands(text: String) {
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.Main) {
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
                            is Command.UseHighReasoningModel -> "Wechsle zu leistungsfähigerem Modell (gemini-2.5-pro-preview-03-25)"
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
                            is Command.UseHighReasoningModel -> "Wechsle zu leistungsfähigerem Modell (gemini-2.5-pro-preview-03-25)"
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
    
    /**
     * Save chat history to SharedPreferences
     */
    fun saveChatHistory(context: android.content.Context?) {
        if (context != null) {
            ChatHistoryPreferences.saveChatHistory(context, chatMessages)
        }
    }
    
    /**
     * Load chat history from SharedPreferences
     */
    fun loadChatHistory(context: android.content.Context) {
        val history = ChatHistoryPreferences.loadChatHistory(context)
        _chatState.clearMessages()
        history.forEach { _chatState.addMessage(it) }
        _chatMessagesFlow.value = chatMessages
    }
    
    /**
     * Clear chat history
     */
    fun clearChatHistory(context: android.content.Context) {
        _chatState.clearMessages()
        _chatMessagesFlow.value = chatMessages
        ChatHistoryPreferences.clearChatHistory(context)
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
                
                // Create message text with screen information if available
                val messageText = if (screenInfo != null) {
                    "Screenshot aufgenommen\n\n$screenInfo"
                } else {
                    "Screenshot aufgenommen"
                }
                
                // Add screenshot message to chat history
                val screenshotMessage = PhotoReasoningMessage(
                    text = messageText,
                    participant = PhotoParticipant.USER,
                    imageUris = listOf(screenshotUri.toString())
                )
                _chatState.addMessage(screenshotMessage)
                _chatMessagesFlow.value = chatMessages
                
                // Save chat history after adding screenshot
                saveChatHistory(context)
                
                // Process the screenshot
                val imageRequest = imageRequestBuilder!!
                    .data(screenshotUri)
                    .precision(Precision.EXACT)
                    .build()
                
                try {
                    val result = imageLoader!!.execute(imageRequest)
                    if (result is SuccessResult) {
                        Log.d(TAG, "Successfully processed screenshot")
                        val bitmap = (result.drawable as BitmapDrawable).bitmap
                        
                        // Add the screenshot to the current images
                        val updatedImages = currentSelectedImages.toMutableList()
                        updatedImages.add(bitmap)
                        
                        // Update the current selected images - only keep the latest screenshot
                        currentSelectedImages = listOf(bitmap)
                        
                        // Update status
                        _commandExecutionStatus.value = "Screenshot hinzugefügt, sende an KI..."
                        
                        // Show toast
                        Toast.makeText(context, "Screenshot hinzugefügt, sende an KI...", Toast.LENGTH_SHORT).show()
                        
                        // Create prompt with screen information if available
                        val prompt = if (screenInfo != null) {
                            "Analysiere diesen Screenshot. Hier sind die verfügbaren Bildschirmelemente: $screenInfo"
                        } else {
                            "Analysiere diesen Screenshot"
                        }
                        
                        // Re-send the query with only the latest screenshot
                        reason(prompt, listOf(bitmap))
                        
                        // Show a toast to indicate the screenshot was added
                        Toast.makeText(context, "Screenshot zur Konversation hinzugefügt", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "Failed to process screenshot: result is not SuccessResult")
                        _commandExecutionStatus.value = "Fehler bei der Screenshot-Verarbeitung"
                        Toast.makeText(context, "Fehler bei der Screenshot-Verarbeitung", Toast.LENGTH_SHORT).show()
                        
                        // Add error message to chat
                        _chatState.addMessage(
                            PhotoReasoningMessage(
                                text = "Fehler bei der Screenshot-Verarbeitung",
                                participant = PhotoParticipant.ERROR
                            )
                        )
                        _chatMessagesFlow.value = chatMessages
                        
                        // Save chat history after adding error message
                        saveChatHistory(context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot: ${e.message}", e)
                    _commandExecutionStatus.value = "Fehler bei der Screenshot-Verarbeitung: ${e.message}"
                    Toast.makeText(context, "Fehler bei der Screenshot-Verarbeitung: ${e.message}", Toast.LENGTH_SHORT).show()
                    
                    // Add error message to chat
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = "Fehler bei der Screenshot-Verarbeitung: ${e.message}",
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = chatMessages
                    
                    // Save chat history after adding error message
                    saveChatHistory(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding screenshot to conversation: ${e.message}", e)
                _commandExecutionStatus.value = "Fehler beim Hinzufügen des Screenshots: ${e.message}"
                Toast.makeText(context, "Fehler beim Hinzufügen des Screenshots: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
