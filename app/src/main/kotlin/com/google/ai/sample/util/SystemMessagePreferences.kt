package com.google.ai.sample.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Utility class to manage system message persistence
 */
object SystemMessagePreferences {
    private const val TAG = "SystemMessagePrefs"
    private const val PREFS_NAME = "system_message_prefs"
    private const val KEY_SYSTEM_MESSAGE = "system_message"

    /**
     * Save system message to SharedPreferences
     */
    fun saveSystemMessage(context: Context, message: String) {
        try {
            Log.d(TAG, "Saving system message: $message")
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString(KEY_SYSTEM_MESSAGE, message)
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving system message: ${e.message}", e)
        }
    }

    /**
     * Load system message from SharedPreferences
     */
    fun loadSystemMessage(context: Context): String {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val message = sharedPreferences.getString(KEY_SYSTEM_MESSAGE, "") ?: ""
            Log.d(TAG, "Loaded system message: $message")
            return message
        } catch (e: Exception) {
            Log.e(TAG, "Error loading system message: ${e.message}", e)
            return ""
        }
    }
}
