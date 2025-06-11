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

    fun reason(
        userInput: String,
        selectedImages: List<Bitmap>
    ) {
        _uiState.value = PhotoReasoningUiState.Loading
        _showStopNotificationFlow.value = true // Show notification when loading starts
        stopExecutionFlag.set(false) // Reset flag at the beginning of a new reason call

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
    private suspend fun sendMessageWithRetry(inputContent: Content, retryCount: Int) {
        if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) {
            if (stopExecutionFlag.get()) {
                // User initiated stop, onStopClicked will handle UI and message
                return // _showStopNotificationFlow is already false from onStopClicked
            } else {
                // Cancellation not by user stop button
                _uiState.value = PhotoReasoningUiState.Error("Operation cancelled unexpectedly before sending.")
                updateAiMessage("Operation cancelled unexpectedly.")
                _showStopNotificationFlow.value = false
                return
            }
        }
        var shouldProceed = true // Flag to control further processing

        try {
            // Send the message to the chat to maintain context
            val response = chat.sendMessage(inputContent)
            if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) {
                if (stopExecutionFlag.get()) {
                    // User initiated stop, onStopClicked will handle UI and message
                    return // _showStopNotificationFlow is already false from onStopClicked
                } else {
                    // Cancellation not by user stop button
                    _uiState.value = PhotoReasoningUiState.Error("Operation cancelled unexpectedly after sending.")
                    updateAiMessage("Operation cancelled unexpectedly.")
                    _showStopNotificationFlow.value = false
                    return
                }
            }

            var outputContent = ""

            // Process the response
            response.text?.let { modelResponse ->
                outputContent = modelResponse

                if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) {
                    if (stopExecutionFlag.get()) {
                        // User initiated stop, onStopClicked will handle UI and message
                        shouldProceed = false // Signal to skip further processing
                        // _showStopNotificationFlow handled by onStopClicked or subsequent checks if shouldProceed is false
                    } else {
                        // Cancellation not by user stop button
                        _uiState.value = PhotoReasoningUiState.Error("Operation cancelled unexpectedly during response processing.")
                        updateAiMessage("Operation cancelled unexpectedly.")
                        _showStopNotificationFlow.value = false
                        shouldProceed = false // Signal to skip further processing
                    }
                }
            }

            if (shouldProceed) { // Only proceed if not cancelled in the 'let' block
                withContext(Dispatchers.Main) {
                    if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) { // Re-check for cancellation
                        if (stopExecutionFlag.get()) {
                            // User initiated stop, onStopClicked will handle UI and message
                            // _showStopNotificationFlow handled by onStopClicked
                        } else {
                            _uiState.value = PhotoReasoningUiState.Error("Operation cancelled unexpectedly before UI update.")
                            updateAiMessage("Operation cancelled unexpectedly.")
                            _showStopNotificationFlow.value = false
                        }
                        // No return@withContext, logic will naturally skip due to outer 'if (shouldProceed)' and this check
                        // or if shouldProceed was set to false earlier.
                    } else {
                        _uiState.value = PhotoReasoningUiState.Success(outputContent)
                        _showStopNotificationFlow.value = false // Operation successful

                        // Update the AI message in chat history
                        updateAiMessage(outputContent)

                        // Parse and execute commands from the response
                        // Ensure modelResponse is accessible or passed correctly if needed here
                        // Assuming outputContent is what's needed for processCommands if modelResponse was scoped to 'let'
                        response.text?.let { modelResponse -> // Re-access modelResponse safely
                            processCommands(modelResponse)
                        }
                    }
                }
            } else {
                // If shouldProceed is false, and it wasn't due to stopExecutionFlag, ensure notification is cancelled.
                // If stopExecutionFlag was true, onStopClicked already handled it.
                if (!stopExecutionFlag.get()) {
                    _showStopNotificationFlow.value = false
                }
            }


            // Save chat history after successful response
            // Ensure this runs only if processing was successful and not cancelled
            if (shouldProceed && currentReasoningJob?.isActive == true && !stopExecutionFlag.get()) {
                withContext(Dispatchers.Main) {
                    // Ensure we are still active before saving
                    if (currentReasoningJob?.isActive == true && !stopExecutionFlag.get()) {
                        saveChatHistory(MainActivity.getInstance()?.applicationContext)
                        // _showStopNotificationFlow already set to false if successful
                    }
                }
            }
        } catch (e: Exception) {
            if (stopExecutionFlag.get()) {
                // If user already stopped, just log the error and return.
                // Do not update UI or send chat messages as onStopClicked handles this.
                Log.w(TAG, "Exception caught after stop flag was set: ${e.message}", e)
                // _showStopNotificationFlow is already false from onStopClicked
                return
            }
            // If the stop flag is not set, but the job is inactive (cancelled by other means)
            if (currentReasoningJob?.isActive != true) {
                _uiState.value = PhotoReasoningUiState.Error("Operation cancelled and then an error occurred.") // Or a more fitting message
                updateAiMessage("Operation cancelled, error during cleanup: ${e.message}")
                Log.e(TAG, "Error generating content after job was cancelled: ${e.message}", e)
                _showStopNotificationFlow.value = false
                return
            }

            // If stopExecutionFlag is false AND currentReasoningJob is active, proceed with normal error handling.
            Log.e(TAG, "Error generating content: ${e.message}", e)

            // Check specifically for quota exceeded errors first
            if (isQuotaExceededError(e) && apiKeyManager != null) {
                // The check currentReasoningJob?.isActive != true is still relevant if the job gets cancelled
                // by other means after the top checks in this catch block.
                // stopExecutionFlag.get() is less likely to be true here due to the top check, but kept for defense.
                if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()){
                     if (!stopExecutionFlag.get()) _showStopNotificationFlow.value = false
                     return
                }
                handleQuotaExceededError(e, inputContent, retryCount) // This might retry or be terminal
                // If handleQuotaExceededError doesn't lead to a retry (i.e., it's terminal), we need to set flow to false.
                // This logic is tricky as handleQuotaExceededError has its own returns.
                // For now, assume if it returns here, it might retry. If it's terminal, it sets UI state and should set flow.
                // This will be refined by inspecting handleQuotaExceededError.
                // For now, if it's truly terminal and doesn't retry, the general error path below will catch it.
                // Let's assume retries handle the flow, and terminal errors are handled below or within the handler.
                return // if handleQuotaExceededError is not terminal and retries, it will reset the flow value at reason() start
            }

            // Check for other 503 errors
            if (is503Error(e) && apiKeyManager != null) {
                if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()){
                    if (!stopExecutionFlag.get()) _showStopNotificationFlow.value = false
                    return
                }
                handle503Error(e, inputContent, retryCount) // Similar to above, assumes retries handle flow
                return
            }

            // If we get here, it's not a 503 error or quota exceeded error that led to a retry path
            // The stopExecutionFlag.get() check here is mostly redundant due to the top check,
            // but currentReasoningJob?.isActive is still a valid check.
            if (currentReasoningJob?.isActive == true && !stopExecutionFlag.get()) {
                withContext(Dispatchers.Main) {
                     if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) {
                        if (!stopExecutionFlag.get()) {
                            _showStopNotificationFlow.value = false
                        }
                        // If cancelled (possibly between the outer check and here, or due to job inactivity)
                        // Potentially log or update UI to reflect this specific state if desired.
                        // For now, this means the error e won't be set as the primary UI state.
                     } else {
                        _uiState.value = PhotoReasoningUiState.Error(e.localizedMessage ?: "Unknown error")
                        _commandExecutionStatus.value = "Error during generation: ${e.localizedMessage}"
                        _showStopNotificationFlow.value = false // Terminal error

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
            } else { // This 'else' covers cases where job became inactive or stop flag was set concurrently
                 if (!stopExecutionFlag.get()) { // If not stopped by user, then it's a cancellation
                    _uiState.value = PhotoReasoningUiState.Error("Operation error processing or cancelled.")
                    updateAiMessage("Operation error processing or cancelled.")
                    _showStopNotificationFlow.value = false
                 }
                 // If stopExecutionFlag.get() is true, onStopClicked handles the notification flow.
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
        if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) return // Check for cancellation
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
                    if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) return // Check for cancellation
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
        if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) return // Check for cancellation
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
                    if (currentReasoningJob?.isActive != true || stopExecutionFlag.get()) return // Check for cancellation
                    sendMessageWithRetry(inputContent, retryCount + 1)
                    return
                }
            }
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
    fun loadSystemMessage(context: Context) {
        val message = SystemMessagePreferences.loadSystemMessage(context)
        _systemMessage.value = message
        
        // Also load chat history
        loadChatHistory(context) // This line calls rebuildChatHistory internally

        _isInitialized.value = true // Add this line
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

        fun updateLastMessageText(newText: String) {
            if (_messages.isNotEmpty()) {
                val lastMessage = _messages.last()
                _messages[_messages.size -1] = lastMessage.copy(text = newText, isPending = false)
            }
        }
    }
}
