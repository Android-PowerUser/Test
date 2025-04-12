/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.sample.feature.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
import com.google.ai.sample.MainActivity
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.CommandParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // Process commands found in the AI response
    private fun processCommandsInResponse(modelResponse: String) {
        // Parse commands from the response
        val commands = CommandParser.parseCommands(modelResponse)
        
        // If commands were found, execute them
        if (commands.isNotEmpty()) {
            Log.d(TAG, "Found ${commands.size} commands in response")
            
            // Check if accessibility service is enabled
            val context = MainActivity.getInstance()?.applicationContext
            if (context != null) {
                val isServiceEnabled = ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(context)
                if (!isServiceEnabled) {
                    Log.e(TAG, "Accessibility service is not enabled. Commands will not work.")
                    MainActivity.getInstance()?.updateStatusMessage(
                        "Accessibility service is not enabled. Please enable it in settings to use click commands.",
                        true
                    )
                    return
                }
            }
            
            // Execute each command
            for (command in commands) {
                Log.d(TAG, "Executing command: $command")
                
                // Notify user about command execution
                val commandText = when (command) {
                    is com.google.ai.sample.util.Command.ClickButton -> 
                        "clickOnButton(\"${command.buttonText}\")"
                    is com.google.ai.sample.util.Command.TapCoordinates -> 
                        "tapAtCoordinates(${command.x}, ${command.y})"
                    is com.google.ai.sample.util.Command.TakeScreenshot -> 
                        "takeScreenshot()"
                }
                
                MainActivity.getInstance()?.updateStatusMessage(
                    "AI is attempting to execute: $commandText",
                    false
                )
                
                // Execute the command
                ScreenOperatorAccessibilityService.executeCommand(command)
            }
        } else {
            Log.d(TAG, "No commands found in response")
        }
    }

    fun sendMessage(userMessage: String) {
        // Add a pending message
        _uiState.value.addMessage(
            ChatMessage(
                text = userMessage,
                participant = Participant.USER,
                isPending = true
            )
        )

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
                    processCommandsInResponse(modelResponse)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
                _uiState.value.replaceLastPendingMessage()
                _uiState.value.addMessage(
                    ChatMessage(
                        text = e.localizedMessage ?: "An error occurred",
                        participant = Participant.ERROR
                    )
                )
                
                // Notify user about the error
                MainActivity.getInstance()?.updateStatusMessage(
                    "Error communicating with AI: ${e.message}",
                    true
                )
            }
        }
    }
}
