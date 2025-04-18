package com.google.ai.sample.feature.multimodal

import android.content.Context // Import für Context
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
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.sample.BuildConfig
import com.google.ai.sample.MainActivity
import com.google.ai.sample.PhotoReasoningApplication
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.ChatHistoryPreferences
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.CommandParser
import com.google.ai.sample.util.ModelPreferences // Import für ModelPreferences
import com.google.ai.sample.util.SystemMessagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoReasoningViewModel(
    initialGenerativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "PhotoReasoningViewModel"

    private var currentGenerativeModel: GenerativeModel = initialGenerativeModel
        private set

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

    // --- NEU: Trigger für UI-Update nach Modellwechsel ---
    private val _modelUpdateTrigger = MutableStateFlow(0) // Einfacher Trigger-State
    val modelUpdateTrigger: StateFlow<Int> = _modelUpdateTrigger.asStateFlow()

    // System message state
    private val _systemMessage = MutableStateFlow<String>("")
    val systemMessage: StateFlow<String> = _systemMessage.asStateFlow()

    // Chat history state
    private val _chatState = ChatState()
    val chatMessages: List<PhotoReasoningMessage> get() = _chatState.messages

    // Chat history state flow for UI updates
    private val _chatMessagesFlow = MutableStateFlow<List<PhotoReasoningMessage>>(emptyList())
    val chatMessagesFlow: StateFlow<List<PhotoReasoningMessage>> = _chatMessagesFlow.asStateFlow()

    // ImageLoader and ImageRequestBuilder for processing images
    private var imageLoader: ImageLoader? = null
    private var imageRequestBuilder: ImageRequest.Builder? = null

    // Chat instance for maintaining conversation context
    private var chat = currentGenerativeModel.startChat(history = emptyList())

    init {
        Log.i(TAG, "ViewModel initialized with model: ${initialGenerativeModel.modelName}")
        // Context wird jetzt in loadSystemMessage übergeben, das von der Route aufgerufen wird
    }


    fun reason(
        userInput: String,
        selectedImages: List<Bitmap>
    ) {
        _uiState.value = PhotoReasoningUiState.Loading
        val systemMessageText = _systemMessage.value
        val prompt = if (systemMessageText.isNotBlank()) {
            "System Message: $systemMessageText\n\nLook at the image(s), and then answer the following question: $userInput"
        } else {
            "Look at the image(s), and then answer the following question: $userInput"
        }
        currentUserInput = userInput
        currentSelectedImages = selectedImages
        _detectedCommands.value = emptyList()
        _commandExecutionStatus.value = ""

        val userMessage = PhotoReasoningMessage(
            text = userInput,
            participant = PhotoParticipant.USER,
            isPending = false,
            // TODO: Hier müssten die URIs der 'selectedImages' übergeben werden,
            //       wenn sie im Chat angezeigt werden sollen. Aktuell werden Bitmaps übergeben.
            imageUris = emptyList() // Platzhalter
        )
        _chatState.addMessage(userMessage)
        _chatMessagesFlow.value = _chatState.messages // Update Flow mit neuer Liste

        val pendingAiMessage = PhotoReasoningMessage(
            text = "",
            participant = PhotoParticipant.MODEL,
            isPending = true
        )
        _chatState.addMessage(pendingAiMessage)
        _chatMessagesFlow.value = _chatState.messages // Update Flow mit neuer Liste

        PhotoReasoningApplication.applicationScope.launch(Dispatchers.IO) {
            try {
                val inputContent = content {
                    for (bitmap in selectedImages) { image(bitmap) }
                    text(prompt)
                }

                Log.d(TAG, "reason: Using chat instance with model: ${currentGenerativeModel.modelName}")
                val response = chat.sendMessage(inputContent)

                var outputContent = ""
                response.text?.let { modelResponse ->
                    outputContent = modelResponse
                    withContext(Dispatchers.Main) {
                        _uiState.value = PhotoReasoningUiState.Success(outputContent)
                        updateAiMessage(outputContent) // Aktualisiert die pending Nachricht
                        processCommands(modelResponse)
                    }
                }
                withContext(Dispatchers.Main) {
                    saveChatHistory(MainActivity.getInstance()?.applicationContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating content: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = PhotoReasoningUiState.Error(e.localizedMessage ?: "Unknown error")
                    _commandExecutionStatus.value = "Fehler bei der Generierung: ${e.localizedMessage}"
                    _chatState.replaceLastPendingMessage()
                    _chatState.addMessage(
                        PhotoReasoningMessage(
                            text = e.localizedMessage ?: "Unknown error generating content",
                            participant = PhotoParticipant.ERROR
                        )
                    )
                    _chatMessagesFlow.value = _chatState.messages // Update Flow mit neuer Liste
                    saveChatHistory(MainActivity.getInstance()?.applicationContext)
                }
            }
        }
    }

    /**
     * Update the AI message in chat history
     */
    private fun updateAiMessage(text: String) {
        val messages = _chatState.messages.toMutableList()
        val lastPendingIndex = messages.indexOfLast { it.participant == PhotoParticipant.MODEL && it.isPending }

        if (lastPendingIndex >= 0) {
            val updatedMessage = messages[lastPendingIndex].copy(text = text, isPending = false)
            messages[lastPendingIndex] = updatedMessage
            _chatState.clearMessages() // Intern leeren
            messages.forEach { _chatState.addMessage(it) } // Wieder hinzufügen
            _chatMessagesFlow.value = _chatState.messages // Flow mit neuer Liste aktualisieren
            saveChatHistory(MainActivity.getInstance()?.applicationContext)
        } else {
             Log.w(TAG, "No pending AI message found to update. Adding new message.")
             _chatState.addMessage(PhotoReasoningMessage(text = text, participant = PhotoParticipant.MODEL, isPending = false))
             _chatMessagesFlow.value = _chatState.messages // Flow mit neuer Liste aktualisieren
             saveChatHistory(MainActivity.getInstance()?.applicationContext)
        }
    }

    /**
     * Update the system message
     */
    fun updateSystemMessage(message: String, context: android.content.Context) {
        _systemMessage.value = message
        SystemMessagePreferences.saveSystemMessage(context, message)
    }

    /**
     * Load the system message from SharedPreferences
     */
    fun loadSystemMessage(context: android.content.Context) {
        val message = SystemMessagePreferences.loadSystemMessage(context)
        _systemMessage.value = message
        loadChatHistory(context) // Lade History direkt danach
    }

    /**
     * Process commands found in the AI response
     */
    private fun processCommands(text: String) {
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.Main) {
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
                    if (!isServiceEnabled) {
                         Log.e(TAG, "Accessibility service is not enabled")
                         _commandExecutionStatus.value = "Accessibility Service ist nicht aktiviert. Bitte aktivieren Sie den Service in den Einstellungen."
                         mainActivity?.checkAccessibilityServiceEnabled()
                         return@launch
                    }
                    if (!ScreenOperatorAccessibilityService.isServiceAvailable()) {
                         Log.e(TAG, "Accessibility service is not available")
                         _commandExecutionStatus.value = "Accessibility Service ist nicht verfügbar. Bitte starten Sie die App neu."
                         mainActivity?.updateStatusMessage("Accessibility Service ist nicht verfügbar. Bitte starten Sie die App neu.", true)
                         return@launch
                    }

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
     * Add a screenshot to the conversation
     */
    fun addScreenshotToConversation(
        screenshotUri: Uri,
        context: android.content.Context,
        screenInfo: String? = null
    ) {
        PhotoReasoningApplication.applicationScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "Adding screenshot to conversation: $screenshotUri")
                latestScreenshotUri = screenshotUri
                if (imageLoader == null) imageLoader = ImageLoader.Builder(context).build()
                if (imageRequestBuilder == null) imageRequestBuilder = ImageRequest.Builder(context)

                _commandExecutionStatus.value = "Verarbeite Screenshot..."
                Toast.makeText(context, "Verarbeite Screenshot...", Toast.LENGTH_SHORT).show()

                val messageText = if (screenInfo != null) "Screenshot aufgenommen\n\n$screenInfo" else "Screenshot aufgenommen"
                val screenshotMessage = PhotoReasoningMessage(
                    text = messageText,
                    participant = PhotoParticipant.USER,
                    imageUris = listOf(screenshotUri.toString())
                )
                _chatState.addMessage(screenshotMessage)
                _chatMessagesFlow.value = _chatState.messages // Update Flow
                saveChatHistory(context)

                val imageRequest = imageRequestBuilder!!.data(screenshotUri).precision(Precision.EXACT).build()
                val result = imageLoader!!.execute(imageRequest)

                if (result is SuccessResult) {
                    Log.d(TAG, "Successfully processed screenshot")
                    val bitmap = (result.drawable as BitmapDrawable).bitmap
                    currentSelectedImages = listOf(bitmap)
                    _commandExecutionStatus.value = "Screenshot hinzugefügt, sende an KI..."
                    Toast.makeText(context, "Screenshot hinzugefügt, sende an KI...", Toast.LENGTH_SHORT).show()
                    val prompt = if (screenInfo != null) "Analysiere diesen Screenshot. Hier sind die verfügbaren Bildschirmelemente: $screenInfo" else "Analysiere diesen Screenshot"
                    reason(prompt, listOf(bitmap))
                } else {
                    Log.e(TAG, "Failed to process screenshot: result is not SuccessResult")
                    _commandExecutionStatus.value = "Fehler bei der Screenshot-Verarbeitung"
                    Toast.makeText(context, "Fehler bei der Screenshot-Verarbeitung", Toast.LENGTH_SHORT).show()
                    _chatState.addMessage(PhotoReasoningMessage(text = "Fehler bei der Screenshot-Verarbeitung", participant = PhotoParticipant.ERROR))
                    _chatMessagesFlow.value = _chatState.messages // Update Flow
                    saveChatHistory(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing/adding screenshot: ${e.message}", e)
                _commandExecutionStatus.value = "Fehler bei der Screenshot-Verarbeitung: ${e.message}"
                Toast.makeText(context, "Fehler bei der Screenshot-Verarbeitung: ${e.message}", Toast.LENGTH_SHORT).show()
                _chatState.addMessage(PhotoReasoningMessage(text = "Fehler bei der Screenshot-Verarbeitung: ${e.message}", participant = PhotoParticipant.ERROR))
                _chatMessagesFlow.value = _chatState.messages // Update Flow
                saveChatHistory(context)
            }
        }
    }


    /**
     * Load saved chat history from SharedPreferences and initialize chat with history
     */
    fun loadChatHistory(context: android.content.Context) {
        val savedMessages = ChatHistoryPreferences.loadChatMessages(context)
        if (savedMessages.isNotEmpty()) {
            _chatState.clearMessages()
            savedMessages.forEach { _chatState.addMessage(it) }
            _chatMessagesFlow.value = _chatState.messages // Update Flow
            rebuildChatHistory()
        } else {
             chat = currentGenerativeModel.startChat(history = emptyList())
             _chatMessagesFlow.value = emptyList()
        }
    }

    /**
     * Rebuild the chat history for the AI based on the current messages
     */
    private fun rebuildChatHistory() {
        val history = mutableListOf<Content>()
        var currentUserContent = ""
        var currentModelContent = ""
        // TODO: Bild-History Rekonstruktion ist komplex und hier nicht implementiert

        for (message in chatMessages) {
            when (message.participant) {
                PhotoParticipant.USER -> {
                    if (currentModelContent.isNotEmpty()) {
                        history.add(content(role = "model") { text(currentModelContent) })
                        currentModelContent = ""
                    }
                    if (currentUserContent.isNotEmpty()) currentUserContent += "\n\n"
                    currentUserContent += message.text
                }
                PhotoParticipant.MODEL -> {
                    if (currentUserContent.isNotEmpty()) {
                        history.add(content(role = "user") { text(currentUserContent) })
                        currentUserContent = ""
                    }
                    if (currentModelContent.isNotEmpty()) currentModelContent += "\n\n"
                    currentModelContent += message.text
                }
                PhotoParticipant.ERROR -> continue
            }
        }
        if (currentUserContent.isNotEmpty()) history.add(content(role = "user") { text(currentUserContent) })
        if (currentModelContent.isNotEmpty()) history.add(content(role = "model") { text(currentModelContent) })

        if (history.isNotEmpty()) {
             try {
                 // Verwende das AKTUELLE Modell zum Starten des Chats mit History
                 chat = currentGenerativeModel.startChat(history = history)
                 Log.d(TAG, "Chat history rebuilt successfully with ${history.size} items using model ${currentGenerativeModel.modelName}.")
             } catch (e: Exception) {
                 Log.e(TAG, "Error rebuilding chat history with model ${currentGenerativeModel.modelName}: ${e.message}", e)
                 chat = currentGenerativeModel.startChat(history = emptyList())
             }
        } else {
             Log.d(TAG, "No history to rebuild, starting empty chat with model ${currentGenerativeModel.modelName}.")
             chat = currentGenerativeModel.startChat(history = emptyList())
        }
    }


    /**
     * Save current chat history to SharedPreferences
     */
    fun saveChatHistory(context: android.content.Context?) {
        context?.let {
            ChatHistoryPreferences.saveChatMessages(it, chatMessages)
        }
    }

    /**
     * Clear the chat history
     */
    fun clearChatHistory(context: android.content.Context? = null) {
        Log.d(TAG, "Clearing chat history...")
        _chatState.clearMessages()
        _chatMessagesFlow.value = emptyList() // Sicherstellen, dass der Flow aktualisiert wird

        // Reset the chat with empty history using the current model
        chat = currentGenerativeModel.startChat(
            history = emptyList()
        )

        // Also clear from SharedPreferences if context is provided
        context?.let {
            ChatHistoryPreferences.clearChatMessages(it)
        }
        // Reset UI state and command states explicitly
        _uiState.value = PhotoReasoningUiState.Initial
        _detectedCommands.value = emptyList()
        _commandExecutionStatus.value = ""
        currentUserInput = "" // Auch User Input zurücksetzen
        currentSelectedImages = emptyList() // Auch Bilder zurücksetzen
        latestScreenshotUri = null // Auch Screenshot URI zurücksetzen

        Log.d(TAG, "Chat history and related states cleared.")
    }


    /**
     * Updates the GenerativeModel instance used by this ViewModel and restarts the chat.
     *
     * @param newModelName The name of the new model to use.
     */
    fun updateGenerativeModel(newModelName: String) {
        if (currentGenerativeModel.modelName == newModelName) {
            Log.i(TAG, "Model $newModelName is already active. No update needed.")
            _commandExecutionStatus.value = "Modell '$newModelName' ist bereits aktiv."
             MainActivity.getInstance()?.updateStatusMessage("Modell '$newModelName' ist bereits aktiv.", false)
            return
        }

        viewModelScope.launch {
            Log.i(TAG, "Updating GenerativeModel from ${currentGenerativeModel.modelName} to: $newModelName")
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

                _chatState.addMessage(
                    PhotoReasoningMessage(
                        text = "Chat-Modell wurde zu '$newModelName' gewechselt.",
                        participant = PhotoParticipant.MODEL,
                        isPending = false
                    )
                )
                _chatMessagesFlow.value = _chatState.messages // Update UI Flow
                saveChatHistory(context) // Speichern nach UI-Update

                // <<< NEU: Trigger für die UI aktualisieren >>>
                _modelUpdateTrigger.value++ // Ändere den Wert, um die UI zu triggern

                _commandExecutionStatus.value = "Modell erfolgreich zu '$newModelName' gewechselt."

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update GenerativeModel to $newModelName: ${e.message}", e)
                _chatState.addMessage(
                    PhotoReasoningMessage(
                        text = "Fehler beim Wechseln des Chat-Modells zu '$newModelName': ${e.localizedMessage}",
                        participant = PhotoParticipant.ERROR,
                        isPending = false
                    )
                )
                _chatMessagesFlow.value = _chatState.messages // Update UI Flow
                saveChatHistory(context) // Speichern nach UI-Update
                _commandExecutionStatus.value = "Fehler beim Modellwechsel: ${e.localizedMessage}"
            }
        }
    }


    /**
     * Chat state management class
     */
    private class ChatState {
        private val _messages = mutableListOf<PhotoReasoningMessage>()
        val messages: List<PhotoReasoningMessage> get() = _messages.toList()
        fun addMessage(message: PhotoReasoningMessage) { _messages.add(message) }
        fun clearMessages() { _messages.clear() }
        fun replaceLastPendingMessage() {
            val lastPendingIndex = _messages.indexOfLast { it.isPending }
            if (lastPendingIndex >= 0) _messages.removeAt(lastPendingIndex)
        }
    }
}
