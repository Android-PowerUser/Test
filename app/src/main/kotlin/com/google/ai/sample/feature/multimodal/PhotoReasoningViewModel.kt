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
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.sample.ApiKeyManager
import com.google.ai.sample.MainActivity
import com.google.ai.sample.PhotoReasoningApplication
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.ChatHistoryPreferences
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import com.google.ai.sample.util.SystemMessagePreferences
import com.google.ai.sample.util.SystemMessageEntryPreferences // Added import
import com.google.ai.sample.util.SystemMessageEntry // Added import
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

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
        
        val prompt = "FOLLOW THE INSTRUCTIONS STRICTLY: $userInput"
        
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
            
            // Check specifically for quota exceeded errors first
            if (isQuotaExceededError(e) && apiKeyManager != null) {
                handleQuotaExceededError(e, inputContent, retryCount)
                return
            }
            
            // Check for other 503 errors
            if (is503Error(e) && apiKeyManager != null) {
                handle503Error(e, inputContent, retryCount)
                return
            }
            
            // If we get here, it's not a 503 error or quota exceeded error
            withContext(Dispatchers.Main) {
                _uiState.value = PhotoReasoningUiState.Error(e.localizedMessage ?: "Unknown error")
                _commandExecutionStatus.value = "Error during generation: ${e.localizedMessage}"
                
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
    
    /**
     * Check if an exception represents a quota exceeded error
     * 
     * @param e The exception to check
     * @return True if the exception represents a quota exceeded error, false otherwise
     */
    private fun isQuotaExceededError(e: Exception): Boolean {
        // Check for quota exceeded error in the exception message
        val message = e.message?.lowercase() ?: ""
        val stackTrace = e.stackTraceToString().lowercase()
        
        // Check for quota exceeded patterns in both message and stack trace
        return message.contains("exceeded your current quota") || 
               message.contains("quota exceeded") ||
               message.contains("rate limit") ||
               stackTrace.contains("exceeded your current quota") ||
               stackTrace.contains("quota exceeded") ||
               stackTrace.contains("rate limit")
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
        
        // First check if it's a quota exceeded error, which should be handled separately
        if (isQuotaExceededError(e)) {
            return false
        }
        
        // Check for common 503 error patterns
        return message.contains("503") || 
               message.contains("service unavailable") || 
               message.contains("server unavailable") ||
               message.contains("server error") ||
               (e is IOException && message.contains("server"))
    }
    
    /**
     * Handle quota exceeded errors specifically
     */
    private suspend fun handleQuotaExceededError(e: Exception, inputContent: Content, retryCount: Int) {
        // Mark the current API key as failed
        val currentKey = MainActivity.getInstance()?.getCurrentApiKey()
        if (currentKey != null && apiKeyManager != null) {
            apiKeyManager.markKeyAsFailed(currentKey)
            
            // Log the specific quota exceeded error
            Log.e(TAG, "Quota exceeded for API key: ${currentKey.take(5)}..., error: ${e.message}")
            
            // Check if we have only one key or if all keys are failed
            val keyCount = apiKeyManager.getKeyCount()
            val allKeysFailed = apiKeyManager.areAllKeysFailed()
            
            if (keyCount <= 1 || (allKeysFailed && retryCount >= MAX_RETRY_ATTEMPTS)) {
                // Only one key available or all keys have failed after multiple attempts
                withContext(Dispatchers.Main) {
                    _uiState.value = PhotoReasoningUiState.Error(
                        "API quota exceeded. Please wait or add more API keys."
                    )
                    _commandExecutionStatus.value = "All API keys have exceeded their quota. Please wait or add more API keys."
                    
                    // Update chat with error message
                    _chatState.replaceLastPendingMessage()
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = "API quota exceeded. Please wait or add more API keys.",
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = chatMessages
                    
                    // Reset failed keys to allow retry after waiting
                    apiKeyManager.resetFailedKeys()
                    
                    // Show toast
                    MainActivity.getInstance()?.updateStatusMessage(
                        "All API keys have exceeded their quota. Please wait or add more API keys.",
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
                        _commandExecutionStatus.value = "API quota exceeded. Switch to next key..."
                        
                        // Show toast
                        MainActivity.getInstance()?.updateStatusMessage(
                            "API quota exceeded. Switch to next key...",
                            false
                        )
                    }
                    
                    // Create a new GenerativeModel with the new API key
                    // Get the current model name and generation config if available
                    val modelName = generativeModel.modelName
                    val generationConfig = generativeModel.generationConfig
                    val safetySettings = generativeModel.safetySettings
                    
                    // Create a new model with the same settings but new API key
                    generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = nextKey,
                        generationConfig = generationConfig,
                        safetySettings = safetySettings
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
    
    /**
     * Handle 503 errors (excluding quota exceeded errors)
     */
    private suspend fun handle503Error(e: Exception, inputContent: Content, retryCount: Int) {
        // Mark the current API key as failed
        val currentKey = MainActivity.getInstance()?.getCurrentApiKey()
        if (currentKey != null && apiKeyManager != null) {
            apiKeyManager.markKeyAsFailed(currentKey)
            
            // Check if we have only one key or if all keys are failed
            val keyCount = apiKeyManager.getKeyCount()
            val allKeysFailed = apiKeyManager.areAllKeysFailed()
            
            if (keyCount <= 1 || (allKeysFailed && retryCount >= MAX_RETRY_ATTEMPTS)) {
                // Only one key available or all keys have failed after multiple attempts
                withContext(Dispatchers.Main) {
                    _uiState.value = PhotoReasoningUiState.Error(
                        "Server overloaded (503). Please wait 45 seconds or add more API keys."
                    )
                    _commandExecutionStatus.value = "All API keys exhausted. Please wait 45 seconds or add more API keys."
                    
                    // Update chat with error message
                    _chatState.replaceLastPendingMessage()
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = "Server overloaded (503). Please wait 45 seconds or add more API keys.",
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = chatMessages
                    
                    // Reset failed keys to allow retry after waiting
                    apiKeyManager.resetFailedKeys()
                    
                    // Show toast
                    MainActivity.getInstance()?.updateStatusMessage(
                        "All API keys exhausted. Please wait 45 seconds or add more API keys.",
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
                        _commandExecutionStatus.value = "API key exhausted. Switch to next key..."
                        
                        // Show toast
                        MainActivity.getInstance()?.updateStatusMessage(
                            "API key exhausted (503). Switch to next key...",
                            false
                        )
                    }
                    
                    // Create a new GenerativeModel with the new API key
                    // Get the current model name and generation config if available
                    val modelName = generativeModel.modelName
                    val generationConfig = generativeModel.generationConfig
                    val safetySettings = generativeModel.safetySettings
                    
                    // Create a new model with the same settings but new API key
                    generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = nextKey,
                        generationConfig = generationConfig,
                        safetySettings = safetySettings
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
            for (message in messages) {
                _chatState.addMessage(message)
            }
            
            // Update the flow
            _chatMessagesFlow.value = chatMessages
            
            // Save chat history after updating message
            saveChatHistory(MainActivity.getInstance()?.applicationContext)
        }
    }
    
    /**
     * Update the system message
     */
    fun updateSystemMessage(message: String, context: Context) {
        _systemMessage.value = message
        
        // Save to SharedPreferences for persistence
        SystemMessagePreferences.saveSystemMessage(context, message)
    }
    
    /**
     * Load the system message from SharedPreferences
     */
    fun loadSystemMessage(context: Context) {
        val message = SystemMessagePreferences.loadSystemMessage(context)
        _systemMessage.value = message
        
        // Also load chat history
        loadChatHistory(context)
    }

    /**
     * Helper function to format database entries as text.
     */
    private fun formatDatabaseEntriesAsText(context: Context): String {
        val entries = SystemMessageEntryPreferences.loadEntries(context)
        if (entries.isEmpty()) {
            return ""
        }
        val builder = StringBuilder()
        builder.append("Available System Guides:\n---\n")
        for (entry in entries) {
            builder.append("Title: ${entry.title}\n")
            builder.append("Guide: ${entry.guide}\n")
            builder.append("---\n")
        }
        return builder.toString()
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
                    val commandDescriptions = commands.joinToString("; ") { command -> 
                        command.toString()
                    }
                    _commandExecutionStatus.value = "Commands detected: $commandDescriptions"
                    
                    // Execute the commands
                    for (command in commands) {
                        try {
                            ScreenOperatorAccessibilityService.executeCommand(command)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error executing command: ${e.message}", e)
                            _commandExecutionStatus.value = "Error during command execution: ${e.message}"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing commands: ${e.message}", e)
                _commandExecutionStatus.value = "Error during command processing: ${e.message}"
            }
        }
    }
    
    /**
     * Save chat history to SharedPreferences
     */
    private fun saveChatHistory(context: Context?) {
        context?.let {
            ChatHistoryPreferences.saveChatMessages(it, chatMessages)
        }
    }
    
    /**
     * Load chat history from SharedPreferences
     */
    fun loadChatHistory(context: Context) {
        val savedMessages = ChatHistoryPreferences.loadChatMessages(context)
        if (savedMessages.isNotEmpty()) {
            _chatState.clearMessages()
            for (message in savedMessages) {
                _chatState.addMessage(message)
            }
            _chatMessagesFlow.value = chatMessages
            
            // Rebuild the chat history for the AI
            rebuildChatHistory(context) // Pass context here
        }
    }
    
    /**
     * Rebuild the chat history for the AI based on the current messages
     */
    private fun rebuildChatHistory(context: Context) { // Added context parameter
        // Convert the current chat messages to Content objects for the chat history
        val history = mutableListOf<Content>()

        // 1. Active System Message
        if (_systemMessage.value.isNotBlank()) {
            history.add(content(role = "user") { text(_systemMessage.value) })
        }

        // 2. Formatted Database Entries
        val formattedDbEntries = formatDatabaseEntriesAsText(context)
        if (formattedDbEntries.isNotBlank()) {
            history.add(content(role = "user") { text(formattedDbEntries) })
        }
        
        // 3. Group messages by participant to create proper conversation turns
        var currentUserContent = ""
        var currentModelContent = ""
        
        for (message in chatMessages) {
            when (message.participant) {
                PhotoParticipant.USER -> {
                    // If we have model content and are now seeing a user message,
                    // add the model content to history and reset
                    if (currentModelContent.isNotEmpty()) {
                        history.add(content(role = "model") { text(currentModelContent) })
                        currentModelContent = ""
                    }
                    
                    // Append to current user content
                    if (currentUserContent.isNotEmpty()) {
                        currentUserContent += "\n\n"
                    }
                    currentUserContent += message.text
                }
                PhotoParticipant.MODEL -> {
                    // If we have user content and are now seeing a model message,
                    // add the user content to history and reset
                    if (currentUserContent.isNotEmpty()) {
                        history.add(content(role = "user") { text(currentUserContent) })
                        currentUserContent = ""
                    }
                    
                    // Append to current model content
                    if (currentModelContent.isNotEmpty()) {
                        currentModelContent += "\n\n"
                    }
                    currentModelContent += message.text
                }
                PhotoParticipant.ERROR -> {
                    // Errors are not included in the AI history
                    continue
                }
            }
        }
        
        // Add any remaining content
        if (currentUserContent.isNotEmpty()) {
            history.add(content(role = "user") { text(currentUserContent) })
        }
        if (currentModelContent.isNotEmpty()) {
            history.add(content(role = "model") { text(currentModelContent) })
        }
        
        // Create a new chat with the rebuilt history
        if (history.isNotEmpty()) {
            chat = generativeModel.startChat(
                history = history
            )
        } else {
            // Ensure chat is reset even if history is empty (e.g. only system message was there and it's now blank)
            chat = generativeModel.startChat(history = emptyList())
        }
    }
    
    /**
     * Clear the chat history
     */
    fun clearChatHistory(context: Context? = null) {
        _chatState.clearMessages()
        _chatMessagesFlow.value = emptyList()
        
        val initialHistory = mutableListOf<Content>()
        if (_systemMessage.value.isNotBlank()) {
            initialHistory.add(content(role = "user") { text(_systemMessage.value) })
        }
        context?.let { ctx ->
            val formattedDbEntries = formatDatabaseEntriesAsText(ctx)
            if (formattedDbEntries.isNotBlank()) {
                initialHistory.add(content(role = "user") { text(formattedDbEntries) })
            }
        }
        chat = generativeModel.startChat(history = initialHistory.toList())
        
        // Also clear from SharedPreferences if context is provided
        context?.let {
            ChatHistoryPreferences.clearChatMessages(it)
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
        context: Context,
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
                _commandExecutionStatus.value = "Processing screenshot..."
                
                // Show toast
                Toast.makeText(context, "Processing screenshot...", Toast.LENGTH_SHORT).show()
                
                // Create message text with screen information if available
                val messageText = if (screenInfo != null) {
                    "Screenshot captured\n\n$screenInfo"
                } else {
                    "Screenshot captured"
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
                        _commandExecutionStatus.value = "Screenshot added, sending to AI..."
                        
                        // Show toast
                        Toast.makeText(context, "Screenshot added, sending to AI...", Toast.LENGTH_SHORT).show()
                        
                        // Create prompt with screen information if available
                        val prompt = if (screenInfo != null) {
                            "Analyze this screenshot. Here is the available screen information: $screenInfo"
                        } else {
                            "Analyze this screenshot"
                        }
                        
                        // Re-send the query with only the latest screenshot
                        reason(prompt, listOf(bitmap))
                        
                        // Show a toast to indicate the screenshot was added
                        Toast.makeText(context, "Screenshot added to conversation", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "Failed to process screenshot: result is not SuccessResult")
                        _commandExecutionStatus.value = "Error processing screenshot"
                        Toast.makeText(context, "Error processing screenshot", Toast.LENGTH_SHORT).show()
                        
                        // Add error message to chat
                        _chatState.addMessage(
                            PhotoReasoningMessage(
                                text = "Error processing screenshot",
                                participant = PhotoParticipant.ERROR
                            )
                        )
                        _chatMessagesFlow.value = chatMessages
                        
                        // Save chat history after adding error message
                        saveChatHistory(context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot: ${e.message}", e)
                    _commandExecutionStatus.value = "Error processing screenshot: ${e.message}"
                    Toast.makeText(context, "Error processing screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
                    
                    // Add error message to chat
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = "Error processing screenshot: ${e.message}",
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = chatMessages
                    
                    // Save chat history after adding error message
                    saveChatHistory(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding screenshot to conversation: ${e.message}", e)
                _commandExecutionStatus.value = "Error adding screenshot: ${e.message}"
                Toast.makeText(context, "Error adding screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Chat state management class
     */
    private class ChatState {
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
    }
}
