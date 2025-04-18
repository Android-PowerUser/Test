// --- START OF FILE GenerativeAiViewModelFactory.kt.txt ---
package com.google.ai.sample

import android.app.Application // Import für Application benötigt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.sample.feature.chat.ChatViewModel
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel
import com.google.ai.sample.feature.text.SummarizeViewModel
import com.google.ai.sample.util.ModelPreferences // Import für ModelPreferences
import android.util.Log // Import für Log

val GenerativeViewModelFactory = object : ViewModelProvider.Factory {
    private const val TAG = "GenerativeViewModelFactory" // Tag für Logging

    // Die interne Variable currentModelName wird nicht mehr benötigt,
    // da wir immer aus den Preferences laden.
    // private var currentModelName = ModelPreferences.LOW_REASONING_MODEL // Entfernt

    // Die Methoden highReasoningModel() und lowReasoningModel() in der Factory
    // sind jetzt überflüssig, da der Wechsel im ViewModel + Preferences passiert.
    /*
    fun highReasoningModel() {
        // currentModelName = ModelPreferences.HIGH_REASONING_MODEL // Veraltet
    }
    fun lowReasoningModel() {
        // currentModelName = ModelPreferences.LOW_REASONING_MODEL // Veraltet
    }
    */

    override fun <T : ViewModel> create(
        viewModelClass: Class<T>,
        extras: CreationExtras
    ): T {
        // Holen des Application Context aus CreationExtras
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: throw IllegalStateException("APPLICATION_KEY must be provided by ViewModelProvider")

        val context = application.applicationContext

        // --- GEÄNDERT: Lade den Modellnamen aus den Preferences ---
        val modelNameToUse = ModelPreferences.loadModelName(context)
        Log.i(TAG, "Creating ViewModel (${viewModelClass.simpleName}) using model: $modelNameToUse")

        val config = generationConfig {
            temperature = 0.0f
        }

        // --- GEÄNDERT: Erstelle das Modell mit dem geladenen Namen ---
        val generativeModel = GenerativeModel(
            modelName = modelNameToUse,
            apiKey = BuildConfig.apiKey,
            generationConfig = config
        )

        // Erstelle das spezifische ViewModel
        return when {
            viewModelClass.isAssignableFrom(SummarizeViewModel::class.java) -> {
                SummarizeViewModel(generativeModel)
            }
            viewModelClass.isAssignableFrom(PhotoReasoningViewModel::class.java) -> {
                PhotoReasoningViewModel(generativeModel) // Übergibt das initial geladene Modell
            }
            viewModelClass.isAssignableFrom(ChatViewModel::class.java) -> {
                ChatViewModel(generativeModel) // Übergibt das initial geladene Modell
            }
            else ->
                throw IllegalArgumentException("Unknown ViewModel class: ${viewModelClass.name}")
        } as T
    }
}

// Das Companion Object ist nicht mehr nötig und sollte entfernt werden,
// da die Factory jetzt den Zustand aus den Preferences lädt.
/*
object GenerativeAiViewModelFactory {
    // ... (veralteter Code) ...
}
*/
// --- END OF FILE GenerativeAiViewModelFactory.kt.txt ---
