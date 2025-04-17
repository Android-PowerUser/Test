package com.google.ai.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.sample.feature.chat.ChatViewModel
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel
import com.google.ai.sample.feature.text.SummarizeViewModel

val GenerativeViewModelFactory = object : ViewModelProvider.Factory {
    // Current selected model name
    private var currentModelName = "gemini-2.0-flash-lite"
    
    /**
     * Set the model to high reasoning capability (gemini-2.5-pro-preview-03-25)
     */
    fun highReasoningModel() {
        currentModelName = "gemini-2.5-pro-preview-03-25"
    }
    
    /**
     * Set the model to low reasoning capability (gemini-2.0-flash-lite)
     */
    fun lowReasoningModel() {
        currentModelName = "gemini-2.0-flash-lite"
    }
    
    override fun <T : ViewModel> create(
        viewModelClass: Class<T>,
        extras: CreationExtras
    ): T {
        val config = generationConfig {
            temperature = 0.0f
        }

        return with(viewModelClass) {
            when {
                isAssignableFrom(SummarizeViewModel::class.java) -> {
                    // Initialize a GenerativeModel with the currently selected model
                    // for text generation
                    val generativeModel = GenerativeModel(
                        modelName = currentModelName,
                        apiKey = BuildConfig.apiKey,
                        generationConfig = config
                    )
                    SummarizeViewModel(generativeModel)
                }

                isAssignableFrom(PhotoReasoningViewModel::class.java) -> {
                    // Initialize a GenerativeModel with the currently selected model
                    // for multimodal text generation
                    val generativeModel = GenerativeModel(
                        modelName = currentModelName,
                        apiKey = BuildConfig.apiKey,
                        generationConfig = config
                    )
                    PhotoReasoningViewModel(generativeModel)
                }

                isAssignableFrom(ChatViewModel::class.java) -> {
                    // Initialize a GenerativeModel with the currently selected model for chat
                    val generativeModel = GenerativeModel(
                        modelName = currentModelName,
                        apiKey = BuildConfig.apiKey,
                        generationConfig = config
                    )
                    ChatViewModel(generativeModel)
                }

                else ->
                    throw IllegalArgumentException("Unknown ViewModel class: ${viewModelClass.name}")
            }
        } as T
    }
}
