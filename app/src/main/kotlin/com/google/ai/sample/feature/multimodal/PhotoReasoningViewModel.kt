package com.google.ai.sample.feature.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.sample.ApiKeyManager
import com.google.ai.sample.MainActivity
import com.google.ai.sample.ScreenCaptureService
import com.google.ai.sample.PhotoReasoningApplication
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.ChatHistoryPreferences
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import com.google.ai.sample.util.SystemMessagePreferences
import com.google.ai.sample.util.SystemMessageEntryPreferences // Added import
import com.google.ai.sample.util.SystemMessageEntry // Added import
import com.google.ai.sample.feature.multimodal.dtos.toDto // Added for DTO mapping
import com.google.ai.sample.feature.multimodal.dtos.ImagePartDto // Required for path extraction
import kotlinx.coroutines.Dispatchers
import java.util.ArrayList // Required for StringArrayListExtra
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// Removed duplicate StateFlow import
// Removed duplicate asStateFlow import
// import kotlinx.coroutines.isActive // Removed as we will use job.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
// import kotlin.coroutines.coroutineContext // Removed if not used
import java.util.concurrent.atomic.AtomicBoolean

class PhotoReasoningViewModel(
    private var generativeModel: GenerativeModel,
    private val apiKeyManager: ApiKeyManager? = null
) : ViewModel() {
    private val TAG = "PhotoReasoningViewModel"

    private val _uiState: MutableStateFlow<PhotoReasoningUiState> =
        MutableStateFlow(PhotoReasoningUiState.Initial)
    val uiState: StateFlow<PhotoReasoningUiState> =
        _uiState.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _showStopNotificationFlow = MutableStateFlow(false)
    val showStopNotificationFlow: StateFlow<Boolean> = _showStopNotificationFlow.asStateFlow()
        
    // Keep track of the latest screenshot URI
    private var latestScreenshotUri: Uri? = null
    private var lastProcessedScreenshotUri: Uri? = null
    private var lastProcessedScreenshotTime: Long = 0L
    
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
    private var currentReasoningJob: Job? = null
    private var commandProcessingJob: Job? = null
    private val stopExecutionFlag = AtomicBoolean(false)

    private val aiResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_AI_CALL_RESULT) {
                _showStopNotificationFlow.value = false // AI call finished one way or another
                val responseText = intent.getStringExtra(ScreenCaptureService.EXTRA_AI_RESPONSE_TEXT)
                val errorMessage = intent.getStringExtra(ScreenCaptureService.EXTRA_AI_ERROR_MESSAGE)

                if (responseText != null) {
                    Log.d(TAG, "AI Call Success via Broadcast: $responseText")
                    _uiState.value = PhotoReasoningUiState.Success(responseText)
                    updateAiMessage(responseText) // Existing method to update chat history
                    processCommands(responseText) // Existing method
                    saveChatHistory(MainActivity.getInstance()?.applicationContext)
                } else if (errorMessage != null) {
                    Log.e(TAG, "AI Call Error via Broadcast: $errorMessage")
                    _uiState.value = PhotoReasoningUiState.Error(errorMessage)
                    _commandExecutionStatus.value = "Error during AI generation: $errorMessage"
                    _chatState.replaceLastPendingMessage()
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = errorMessage,
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = chatMessages
                    saveChatHistory(MainActivity.getInstance()?.applicationContext)
                }
                // Reset pending AI message if any (assuming updateAiMessage or error handling does this)
            }
        }
    }

    init {
        // ... other init logic if any ...
        val context = MainActivity.getInstance()?.applicationContext
        if (context != null) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_AI_CALL_RESULT)
            // Comment: Specify RECEIVER_NOT_EXPORTED for Android 13 (API 33) and above
            // to comply with security requirements for programmatically registered receivers.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(aiResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(aiResultReceiver, filter)
            }
            Log.d(TAG, "AIResultReceiver registered.")
        } else {
            Log.e(TAG, "Failed to register AIResultReceiver: applicationContext is null at init.")
            // Consider if this state implies a critical failure for the ViewModel's operation.
            // For now, just logging.
        }
    }

    override fun onCleared() {
        super.onCleared()
        MainActivity.getInstance()?.applicationContext?.unregisterReceiver(aiResultReceiver)
        Log.d(TAG, "AIResultReceiver unregistered.")
        // ... other onCleared logic ...
    }

    private fun createChatWithSystemMessage(context: Context? = null): Chat {
        val ctx = context ?: MainActivity.getInstance()?.applicationContext
        val history = mutableListOf<Content>()
        if (_systemMessage.value.isNotBlank()) {
            history.add(content(role = "user") { text(_systemMessage.value) })
        }
        ctx?.let {
            val formattedDbEntries = formatDatabaseEntriesAsText(it)
            if (formattedDbEntries.isNotBlank()) {
                history.add(content(role = "user") { text(formattedDbEntries) })
            }
        }
        return generativeModel.startChat(history = history)
    }

    private fun ensureInitialized(context: Context?) {
        if (!_isInitialized.value && context != null) {
            loadSystemMessage(context)
        }
    }

    private fun performReasoning(
        userInput: String,
        selectedImages: List<Bitmap>,
        screenInfoForPrompt: String? = null,
        imageUrisForChat: List<String>? = null
    ) {
        if (chat.history.isEmpty() && _systemMessage.value.isNotBlank()) {
            Log.w(TAG, "performReasoning - Chat history is empty but system message exists. Recreating chat instance.")
            chat = createChatWithSystemMessage()
        }
        Log.d(TAG, "performReasoning() called. User input: '$userInput', Image count: ${selectedImages.size}, ScreenInfo: ${screenInfoForPrompt != null}, ImageUris: ${imageUrisForChat != null}")
        _uiState.value = PhotoReasoningUiState.Loading
        Log.d(TAG, "Setting _showStopNotificationFlow to true")
        _showStopNotificationFlow.value = true
        Log.d(TAG, "_showStopNotificationFlow value is now: ${_showStopNotificationFlow.value}")
        stopExecutionFlag.set(false)

        val combinedPromptTextBuilder = StringBuilder(userInput)
        if (screenInfoForPrompt != null && screenInfoForPrompt.isNotBlank()) { // Added isNotBlank check
            combinedPromptTextBuilder.append("\n\nScreen Context:\n$screenInfoForPrompt")
        }
        val aiPromptText = combinedPromptTextBuilder.toString()

        val prompt = "FOLLOW THE INSTRUCTIONS STRICTLY: $aiPromptText"

        // Store the current user input and selected images
        currentUserInput = userInput // This should ideally store aiPromptText or handle context separately if needed for retry. For now, task is specific to prompt to AI and chat.
        currentSelectedImages = selectedImages

        // Clear previous commands
        _detectedCommands.value = emptyList()
        _commandExecutionStatus.value = ""

        // Add user message to chat history
        val userMessage = PhotoReasoningMessage(
            text = aiPromptText, // Use the combined text
            participant = PhotoParticipant.USER,
            imageUris = imageUrisForChat ?: emptyList(), // Use the new parameter here
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

        currentReasoningJob?.cancel() // Cancel any previous reasoning job
        currentReasoningJob = PhotoReasoningApplication.applicationScope.launch(Dispatchers.IO) {
            var shouldContinueProcessing = true
            // Create content with the current images and prompt
            val inputContent = content {
                // Ensure line for original request: 136
                if (currentReasoningJob?.isActive != true) {
                    shouldContinueProcessing = false
                    // No return here
                }
                if (shouldContinueProcessing) { // Check flag before proceeding
                    for (bitmap in selectedImages) {
                        // Ensure line for original request: 138
                        if (currentReasoningJob?.isActive != true) {
                            shouldContinueProcessing = false
                            break // Break from the for loop
                        }
                        if (!shouldContinueProcessing) break // Check flag again in case it was set by the outer check
                        image(bitmap)
                    }
                }
                if (shouldContinueProcessing) { // Check flag before proceeding
                    // Ensure line for original request: 141
                    if (currentReasoningJob?.isActive != true) {
                        shouldContinueProcessing = false
                        // No return here
                    }
                }
                if (shouldContinueProcessing) { // Check flag before proceeding
                    text(prompt)
                }
            }

            if (!shouldContinueProcessing) {
                // If processing should not continue, we might need to update UI state
                // For now, the existing check below should handle it.
                // If specific UI updates are needed here, they can be added.
                return@launch
            }

            if (currentReasoningJob?.isActive != true) return@launch // Check for cancellation outside content block
            sendMessageWithRetry(inputContent, 0)
        }
    }

    fun reason(
        userInput: String,
        selectedImages: List<Bitmap>,
        screenInfoForPrompt: String? = null,
        imageUrisForChat: List<String>? = null
    ) {
        val context = MainActivity.getInstance()?.applicationContext
        if (context == null) {
            Log.e(TAG, "Context not available, cannot proceed with reasoning")
            _uiState.value = PhotoReasoningUiState.Error("Application not ready")
            return
        }
        ensureInitialized(context)
        performReasoning(userInput, selectedImages, screenInfoForPrompt, imageUrisForChat)
    }

    fun onStopClicked() {
        _showStopNotificationFlow.value = false // Hide notification immediately on stop
        stopExecutionFlag.set(true)
        currentReasoningJob?.cancel()
        commandProcessingJob?.cancel()

        val lastMessage = chatMessages.lastOrNull()
        val statusMessage = "Operation stopped by user."

        if (lastMessage != null && lastMessage.participant == PhotoParticipant.MODEL && lastMessage.isPending) {
            _chatState.replaceLastPendingMessage() // Remove pending message
            _chatState.addMessage(
                PhotoReasoningMessage(
                    text = statusMessage,
                    participant = PhotoParticipant.MODEL,
                    isPending = false
                )
            )
        } else if (lastMessage != null && lastMessage.participant == PhotoParticipant.MODEL && !lastMessage.isPending) {
             // If the last message was a successful model response, update it.
            _chatState.updateLastMessageText(lastMessage.text + "\n\n[Stopped by user]")
        } else {
            // If no relevant model message, or last message was user/error, add a new model message
             _chatState.addMessage(
                PhotoReasoningMessage(
                    text = statusMessage,
                    participant = PhotoParticipant.MODEL,
                    isPending = false
                )
            )
        }
        _chatMessagesFlow.value = chatMessages


        // _uiState.value = PhotoReasoningUiState.Stopped; // No longer setting this as the final state.
        _commandExecutionStatus.value = "" // Set to empty string
        _detectedCommands.value = emptyList()
        Log.d(TAG, "Stop clicked, operations cancelled, command status cleared.")

        // Set a success state to indicate the stop operation itself was successful
        // and the UI can return to an idle/interactive state.
        _uiState.value = PhotoReasoningUiState.Success("Operation stopped.")
        Log.d(TAG, "UI updated to Success state after stop.")
    }

    /**
     * Send a message to the AI with retry logic for 503 errors
     *
     * @param inputContent The content to send
     * @param retryCount The current retry count
     */
    // This method now delegates the AI call to ScreenCaptureService
    // to ensure it runs with foreground priority and avoids background network restrictions.
    private suspend fun sendMessageWithRetry(inputContent: Content, retryCount: Int) {
        Log.d(TAG, "sendMessageWithRetry: Delegating AI call to ScreenCaptureService.")

        val context = MainActivity.getInstance()?.applicationContext
        if (context == null) {
            Log.e(TAG, "sendMessageWithRetry: Context is null, cannot delegate AI call.")
            _uiState.value = PhotoReasoningUiState.Error("Application context not available for AI call.")
            _showStopNotificationFlow.value = false
            return
        }

        try {
            // Serialize Content and History.
            // This assumes Content and List<Content> are @Serializable or have custom serializers.
            // Add @file:UseSerializers(ContentSerializer::class, PartSerializer::class etc.) if needed at top of file
            // Or create DTOs. For this subtask, we'll assume direct serialization is possible.
            val inputContentDto = inputContent.toDto(context) // Pass context
            val chatHistoryDtos = chat.history.map { it.toDto(context) } // Pass context

            val inputContentJson = Json.encodeToString(inputContentDto)
            val chatHistoryJson = Json.encodeToString(chatHistoryDtos)

            // Collect Temporary File Paths
            val tempFilePaths = ArrayList<String>()
            inputContentDto.parts.forEach { partDto ->
                if (partDto is ImagePartDto) {
                    tempFilePaths.add(partDto.imageFilePath)
                }
            }
            chatHistoryDtos.forEach { contentDto ->
                contentDto.parts.forEach { partDto ->
                    if (partDto is ImagePartDto) {
                        tempFilePaths.add(partDto.imageFilePath)
                    }
                }
            }
            Log.d(TAG, "Collected temporary file paths to send to service: $tempFilePaths")

            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_EXECUTE_AI_CALL
                putExtra(ScreenCaptureService.EXTRA_AI_INPUT_CONTENT_JSON, inputContentJson)
                putExtra(ScreenCaptureService.EXTRA_AI_CHAT_HISTORY_JSON, chatHistoryJson)
                putExtra(ScreenCaptureService.EXTRA_AI_MODEL_NAME, generativeModel.modelName) // Pass model name
                apiKeyManager?.getCurrentApiKey()?.let { apiKey -> // Pass current API key
                    putExtra(ScreenCaptureService.EXTRA_AI_API_KEY, apiKey)
                }
                // Add the new extra for file paths
                putStringArrayListExtra(ScreenCaptureService.EXTRA_TEMP_FILE_PATHS, tempFilePaths)
            }
            context.startService(serviceIntent)
            Log.d(TAG, "sendMessageWithRetry: Sent intent to ScreenCaptureService to execute AI call.")
            // The UI state (_uiState.value = PhotoReasoningUiState.Loading) and
            // _showStopNotificationFlow.value = true should have been set by the calling method (performReasoning)
            // The receiver will handle setting them to false or success/error state.

        } catch (e: Exception) {
            Log.e(TAG, "sendMessageWithRetry: Error serializing or starting service for AI call.", e)
            _uiState.value = PhotoReasoningUiState.Error("Error preparing AI call: ${e.localizedMessage}")
            _showStopNotificationFlow.value = false
            // Also update chat with this local error
            _chatState.replaceLastPendingMessage()
            _chatState.addMessage(
                PhotoReasoningMessage(
                    text = "Error preparing AI call: ${e.localizedMessage}",
                    participant = PhotoParticipant.ERROR
                )
            )
            _chatMessagesFlow.value = chatMessages
            saveChatHistory(context)
        }
    }

    /**
     * Update the AI message in chat history
     */
    private fun updateAiMessage(text: String, isPending: Boolean = false) {
        // Find the last AI message and update it or add a new one if no suitable message exists
        val messages = _chatState.messages.toMutableList()
        val lastAiMessageIndex = messages.indexOfLast { it.participant == PhotoParticipant.MODEL }

        if (lastAiMessageIndex >= 0 && messages[lastAiMessageIndex].isPending) {
            // If last AI message is pending, update it
            val updatedMessage = messages[lastAiMessageIndex].copy(text = text, isPending = isPending)
            messages[lastAiMessageIndex] = updatedMessage
        } else if (lastAiMessageIndex >=0 && !messages[lastAiMessageIndex].isPending && text.startsWith(messages[lastAiMessageIndex].text)) {
            // If last AI message is not pending, but the new text is an extension, update it (e.g. for stop message)
            val updatedMessage = messages[lastAiMessageIndex].copy(text = text, isPending = isPending)
            messages[lastAiMessageIndex] = updatedMessage
        }
        else {
            // Otherwise, add a new AI message
            messages.add(PhotoReasoningMessage(text = text, participant = PhotoParticipant.MODEL, isPending = isPending))
        }

        // Clear and re-add all messages to maintain order
        _chatState.clearMessages()
        for (message in messages) {
            _chatState.addMessage(message)
        }

        // Update the flow
        _chatMessagesFlow.value = chatMessages

        // Save chat history after updating message
        // Only save if the operation wasn't stopped, or if it's a deliberate update after stopping
        if (!stopExecutionFlag.get() || text.contains("stopped by user", ignoreCase = true)) {
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
    fun loadSystemMessage(context: Context?) {
        if (context == null) {
            Log.w(TAG, "Cannot load system message: context is null")
            return
        }
        val message = SystemMessagePreferences.loadSystemMessage(context)
        _systemMessage.value = message
        
        // Also load chat history
        loadChatHistory(context) // This line calls rebuildChatHistory internally
        chat = createChatWithSystemMessage(context)

        _isInitialized.value = true // Add this line
    }

    // Removed isQuotaExceededError, is503Error, handleQuotaExceededError, and handle503Error methods
    // as this logic is now delegated to the ScreenCaptureService.

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
        commandProcessingJob?.cancel() // Cancel any previous command processing
        commandProcessingJob = PhotoReasoningApplication.applicationScope.launch(Dispatchers.Main) {
            if (commandProcessingJob?.isActive != true || stopExecutionFlag.get()) return@launch // Check for cancellation
            try {
                // Parse commands from the text
                val commands = CommandParser.parseCommands(text)

                if (commands.isNotEmpty()) {
                    if (commandProcessingJob?.isActive != true || stopExecutionFlag.get()) return@launch
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
                        if (commandProcessingJob?.isActive != true || stopExecutionFlag.get()) { // Check for cancellation before executing each command
                            Log.d(TAG, "Command execution stopped before executing: $command")
                            _commandExecutionStatus.value = "Command execution stopped."
                            break // Exit loop if cancelled
                        }
                        try {
                            Log.d(TAG, "Executing command: $command")
                            ScreenOperatorAccessibilityService.executeCommand(command)
                            // Check immediately after execution attempt if a stop was requested
                            if (stopExecutionFlag.get()) {
                                Log.d(TAG, "Command execution stopped after attempting: $command")
                                _commandExecutionStatus.value = "Command execution stopped."
                                break
                            }
                        } catch (e: Exception) {
                            if (commandProcessingJob?.isActive != true || stopExecutionFlag.get()) break // Exit loop if cancelled during error handling
                            Log.e(TAG, "Error executing command: ${e.message}", e)
                            _commandExecutionStatus.value = "Error during command execution: ${e.message}"
                        }
                    }
                     if (stopExecutionFlag.get()){
                        _commandExecutionStatus.value = "Command processing loop was stopped."
                    }
                }
            } catch (e: Exception) {
                 if (commandProcessingJob?.isActive != true || stopExecutionFlag.get()) return@launch
                Log.e(TAG, "Error processing commands: ${e.message}", e)
                _commandExecutionStatus.value = "Error during command processing: ${e.message}"
            } finally {
                if (stopExecutionFlag.get()){
                     _commandExecutionStatus.value = "Command processing finished after stop request."
                }
                // Reset flag after processing is complete or stopped to allow future executions
                // No, don't reset here. Reset at the beginning of 'reason' or when stop is explicitly cleared.
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
        val currentTime = System.currentTimeMillis()
        if (screenshotUri == lastProcessedScreenshotUri && (currentTime - lastProcessedScreenshotTime) < 2000) { // 2-second debounce window
            Log.w(TAG, "addScreenshotToConversation: Debouncing duplicate/rapid call for URI $screenshotUri")
            return // Exit the function early if it's a duplicate call within the window
        }
        lastProcessedScreenshotUri = screenshotUri
        lastProcessedScreenshotTime = currentTime

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
                        val genericAnalysisPrompt = "Analyze the provided screenshot and its context."
                        
                        // Re-send the query with only the latest screenshot
                        reason(
                            userInput = genericAnalysisPrompt,
                            selectedImages = listOf(bitmap),
                            screenInfoForPrompt = screenInfo,
                            imageUrisForChat = listOf(screenshotUri.toString()) // Add this argument
                        )
                        
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

        fun updateLastMessageText(newText: String) {
            if (_messages.isNotEmpty()) {
                val lastMessage = _messages.last()
                _messages[_messages.size -1] = lastMessage.copy(text = newText, isPending = false)
            }
        }
    }
}
