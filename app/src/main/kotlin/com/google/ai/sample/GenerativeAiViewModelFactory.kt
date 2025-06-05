package com.google.ai.sample

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel

// Model options
enum class ModelOption(val displayName: String, val modelName: String) {
    GEMINI_FLASH_LITE("Gemini 2.0 Flash Lite", "gemini-2.0-flash-lite"),
    GEMINI_FLASH("Gemini 2.0 Flash", "gemini-2.0-flash"),
    GEMINI_FLASH_PREVIEW("Gemini 2.5 Flash Preview", "gemini-2.5-flash-preview-04-17"),
    GEMINI_PRO("Gemini 2.5 Pro (maybe don't work)", "gemini-2.5-pro-preview-05-06")
}

val GenerativeViewModelFactory = object : ViewModelProvider.Factory {
    // Current selected model name
    private var currentModelName = ModelOption.GEMINI_FLASH_PREVIEW.modelName
    
    /**
     * Set the model to high reasoning capability (gemini-2.5-pro-preview-03-25)
     */
    fun highReasoningModel() {
        currentModelName = ModelOption.GEMINI_PRO.modelName
    }
    
    /**
     * Set the model to low reasoning capability (gemini-2.0-flash-lite)
     */
    fun lowReasoningModel() {
        currentModelName = ModelOption.GEMINI_FLASH_LITE.modelName
    }
    
    /**
     * Set the model to a specific model option
     */
    fun setModel(modelOption: ModelOption) {
        currentModelName = modelOption.modelName
    }
    
    /**
     * Get the current model option
     */
    fun getCurrentModel(): ModelOption {
        return ModelOption.values().find { it.modelName == currentModelName } 
            ?: ModelOption.GEMINI_FLASH_LITE
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


                else ->
                    throw IllegalArgumentException("Unknown ViewModel class: ${viewModelClass.name}")
            }
        } as T
    }
}

// Add companion object with static methods for easier access
object GenerativeAiViewModelFactory {
    // Current selected model name - duplicated from GenerativeViewModelFactory
    private var currentModelName = ModelOption.GEMINI_FLASH_PREVIEW.modelName
    
    /**
     * Set the model to high reasoning capability (gemini-2.5-pro-preview-03-25)
     */
    fun highReasoningModel() {
        currentModelName = ModelOption.GEMINI_PRO.modelName
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
        currentModelName = ModelOption.GEMINI_FLASH_LITE.modelName
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
     * Set the model to a specific model option
     */
    fun setModel(modelOption: ModelOption) {
        currentModelName = modelOption.modelName
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
     * Get the current model option
     */
    fun getCurrentModel(): ModelOption {
        return ModelOption.values().find { it.modelName == currentModelName } 
            ?: ModelOption.GEMINI_FLASH_LITE
    }
}
