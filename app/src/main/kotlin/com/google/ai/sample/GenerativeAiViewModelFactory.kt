package com.google.ai.sample

import android.content.Context
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
        currentModelName = "gemini-2.5-pro-exp-03-25"
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

        // Get the application context from extras
        val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        
        // Get the API key from MainActivity
        val mainActivity = MainActivity.getInstance()
        val apiKey = mainActivity?.getCurrentApiKey() ?: ""
        
        if (apiKey.isEmpty()) {
            throw IllegalStateException("API key is not available. Please set an API key.")
        }

        return with(viewModelClass) {
            when {
                isAssignableFrom(SummarizeViewModel::class.java) -> {
                    // Initialize a GenerativeModel with the currently selected model
                    // for text generation
                    val generativeModel = GenerativeModel(
                        modelName = currentModelName,
                        apiKey = apiKey,
                        generationConfig = config
                    )
                    SummarizeViewModel(generativeModel)
                }

                isAssignableFrom(PhotoReasoningViewModel::class.java) -> {
                    // Initialize a GenerativeModel with the currently selected model
                    // for multimodal text generation
                    val generativeModel = GenerativeModel(
                        modelName = currentModelName,
                        apiKey = apiKey,
                        generationConfig = config
                    )
                    // Pass the ApiKeyManager to the ViewModel for key rotation
                    val apiKeyManager = ApiKeyManager.getInstance(application)
                    PhotoReasoningViewModel(generativeModel, apiKeyManager)
                }

                isAssignableFrom(ChatViewModel::class.java) -> {
                    // Initialize a GenerativeModel with the currently selected model for chat
                    val generativeModel = GenerativeModel(
                        modelName = currentModelName,
                        apiKey = apiKey,
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

// Add companion object with static methods for easier access
object GenerativeAiViewModelFactory {
    // Current selected model name - duplicated from GenerativeViewModelFactory
    private var currentModelName = "gemini-2.0-flash-lite"
    
    /**
     * Set the model to high reasoning capability (gemini-2.5-pro-preview-03-25)
     */
    fun highReasoningModel() {
        currentModelName = "gemini-2.5-pro-exp-03-25"
        // Also update the original factory to keep them in sync
        (GenerativeViewModelFactory as ViewModelProvider.Factory).apply {
            if (this is ViewModelProvider.Factory) {
                try {
                    val field = this.javaClass.getDeclaredField("currentModelName")
                    field.isAccessible = true
                    field.set(this, currentModelName)
                } catch (e: Exception) {
                    // Fallback if reflection fails
                }
            }
        }
    }
    
    /**
     * Set the model to low reasoning capability (gemini-2.0-flash-lite)
     */
    fun lowReasoningModel() {
        currentModelName = "gemini-2.0-flash-lite"
        // Also update the original factory to keep them in sync
        (GenerativeViewModelFactory as ViewModelProvider.Factory).apply {
            if (this is ViewModelProvider.Factory) {
                try {
                    val field = this.javaClass.getDeclaredField("currentModelName")
                    field.isAccessible = true
                    field.set(this, currentModelName)
                } catch (e: Exception) {
                    // Fallback if reflection fails
                }
            }
        }
    }
}
