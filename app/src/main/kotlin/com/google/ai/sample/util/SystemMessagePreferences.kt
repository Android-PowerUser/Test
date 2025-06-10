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
    private const val KEY_FIRST_START_COMPLETED = "first_start_completed" // New flag

    // Content from pasted_content.txt
    private const val DEFAULT_SYSTEM_MESSAGE_ON_FIRST_START = """You are on an App on a Smartphone. You're app is called Screen Operator. You start from this app. Proceed step by step! DON'T USE TOOL CODE! You must operate the screen with exactly following commands: "`home()`" "`back()`" "`recentApps()`" for buttons and words: "`clickOnButton("sample")`" "`tapAtCoordinates(x, y)`"  "`tapAtCoordinates(x percent of screen%, y percent of screen%)`""`scrollDown()`" "`scrollUp()`" "`scrollLeft()`" "`scrollRight()`" "`scrollDown(x, y, how much pixel to scroll, duration in milliseconds)`" "`scrollUp(x, y, how much pixel to scroll, duration in milliseconds)`" "`scrollLeft(x, y, how much pixel to scroll, duration in milliseconds)`" "`scrollRight(x, y, how much pixel to scroll, duration in milliseconds)`" "`scrollDown(x percent of screen%, y percent of screen%, how much percent to scroll%, duration in milliseconds)`" "`scrollUp(x percent of screen%, y percent of screen%, how much percent to scroll, duration in milliseconds)`" "`scrollLeft(x percent of screen%, y percent of screen%, how much percent to scroll, duration in milliseconds)`" "`scrollRight(x percent of screen%, y percent of screen%, how much percent to scroll, duration in milliseconds)`" scroll status bar down:  "`scrollUp(540, 0, 1100, 50)`" "`takeScreenshot()`" To write text, search and click the textfield thereafter: "`writeText("sample text")`" You need to write the already existing text, if it should continue exist. If the keyboard is displayed, you can press "`Enter()`". Otherwise, you have to open the keyboard by clicking on the text field. You can see the screen and get additional Informations about them with: "`takeScreenshot()`" You need this command at the end of every message until you are finish. When you're done don't say "`takeScreenshot()`" Your task is:"""

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
     * Load system message from SharedPreferences.
     * On first start, it loads a default message, saves it, and marks first start as completed.
     */
    fun loadSystemMessage(context: Context): String {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isFirstStartCompleted = sharedPreferences.getBoolean(KEY_FIRST_START_COMPLETED, false)

            if (!isFirstStartCompleted) {
                Log.d(TAG, "First start detected. Loading and saving default system message.")
                // Save the default message and the flag
                val editor = sharedPreferences.edit()
                editor.putString(KEY_SYSTEM_MESSAGE, DEFAULT_SYSTEM_MESSAGE_ON_FIRST_START)
                editor.putBoolean(KEY_FIRST_START_COMPLETED, true)
                editor.apply()
                Log.d(TAG, "Loaded default system message: $DEFAULT_SYSTEM_MESSAGE_ON_FIRST_START")
                return DEFAULT_SYSTEM_MESSAGE_ON_FIRST_START
            } else {
                val message = sharedPreferences.getString(KEY_SYSTEM_MESSAGE, "") ?: ""
                Log.d(TAG, "Loaded system message from prefs: $message")
                return message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading system message: ${e.message}", e)
            return "" // Return empty string in case of error, consistent with original behavior
        }
    }
}

