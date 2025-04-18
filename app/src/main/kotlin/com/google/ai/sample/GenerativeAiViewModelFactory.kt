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
import com.google.ai.sample.util.ModelPreferences // <<< KORREKTUR: Fehlender Import hinzugefügt
import android.util.Log // Import für Log

val GenerativeViewModelFactory = object : ViewModelProvider.Factory {
    // <<< KORREKTUR: 'const' entfernt, da es hier nicht erlaubt ist >>>
    private val TAG = "GenerativeViewModelFactory" // Tag für Logging

    override fun <T : ViewModel> create(
        viewModelClass: Class<T>,
        extras: CreationExtras
    ): T {
        // Holen des Application Context aus CreationExtras
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: throw IllegalStateException("APPLICATION_KEY must be provided by ViewModelProvider")

        val context = application.applicationContext

        // Lade den Modellnamen aus den Preferences
        val modelNameToUse = ModelPreferences.loadModelName(context) // <<< KORREKTUR: Verwendet jetzt den korrekten Import
        Log.i(TAG, "Creating ViewModel (${viewModelClass.simpleName}) using model: $modelNameToUse")

        val config = generationConfig {
            temperature = 0.0f
        }

        // Erstelle das Modell mit dem geladenen Namen
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
// --- END OF FILE GenerativeAiViewModelFactory.kt.txt ---
