// --- START OF FILE ModelPreferences.kt ---
package com.google.ai.sample.util

import android.content.Context
import android.util.Log

/**
 * Helper object to save and load the selected AI model name using SharedPreferences.
 */
object ModelPreferences {
    private const val TAG = "ModelPreferences"
    private const val PREFS_NAME = "ai_model_prefs"
    private const val KEY_MODEL_NAME = "current_model_name"

    // Definiere hier deine Standard- und High-Modellnamen konsistent
    const val LOW_REASONING_MODEL = "gemini-2.0-flash-lite"
    const val HIGH_REASONING_MODEL = "gemini-2.5-pro-exp-03-25"

    // Das Standardmodell, das verwendet wird, wenn nichts gespeichert ist.
    private const val DEFAULT_MODEL = LOW_REASONING_MODEL

    /**
     * Saves the selected model name to SharedPreferences.
     * @param context Application context.
     * @param modelName The model name string to save.
     */
    fun saveModelName(context: Context, modelName: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODEL_NAME, modelName)
                .apply()
            Log.i(TAG, "Saved model preference: $modelName")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving model preference: $modelName", e)
        }
    }

    /**
     * Loads the saved model name from SharedPreferences.
     * Returns the default model name if nothing is saved.
     * @param context Application context.
     * @return The loaded or default model name string.
     */
    fun loadModelName(context: Context): String {
        return try {
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
            Log.i(TAG, "Loaded model preference: $name")
            name
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model preference, returning default: $DEFAULT_MODEL", e)
            DEFAULT_MODEL
        }
    }
}
// --- END OF FILE ModelPreferences.kt ---