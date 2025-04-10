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

package com.google.ai.sample.feature.multimodal

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.CommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PhotoReasoningViewModel(
    private val generativeModel: GenerativeModel
) : ViewModel() {
    private val TAG = "PhotoReasoningViewModel"

    private val _uiState: MutableStateFlow<PhotoReasoningUiState> =
        MutableStateFlow(PhotoReasoningUiState.Initial)
    val uiState: StateFlow<PhotoReasoningUiState> =
        _uiState.asStateFlow()

    fun reason(
        userInput: String,
        selectedImages: List<Bitmap>
    ) {
        _uiState.value = PhotoReasoningUiState.Loading
        val prompt = "Look at the image(s), and then answer the following question: $userInput"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputContent = content {
                    for (bitmap in selectedImages) {
                        image(bitmap)
                    }
                    text(prompt)
                }

                var outputContent = ""

                generativeModel.generateContentStream(inputContent)
                    .collect { response ->
                        val newText = response.text ?: ""
                        outputContent += newText
                        _uiState.value = PhotoReasoningUiState.Success(outputContent)
                        
                        // Parse and execute commands from the response
                        processCommands(newText)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating content: ${e.message}", e)
                _uiState.value = PhotoReasoningUiState.Error(e.localizedMessage ?: "Unknown error")
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
                    
                    // Execute each command
                    commands.forEach { command ->
                        Log.d(TAG, "Executing command: $command")
                        ScreenOperatorAccessibilityService.executeCommand(command)
                        
                        // Add a small delay between commands to avoid overwhelming the system
                        kotlinx.coroutines.delay(500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing commands: ${e.message}", e)
            }
        }
    }
}
