package io.github.Android-PowerUser.util

import android.content.Context
import android.content.SharedPreferences
import com.google.ai.sample.feature.multimodal.PhotoParticipant
import com.google.ai.sample.feature.multimodal.PhotoReasoningMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Utility class for persisting chat history across app restarts
 */
object ChatHistoryPreferences {
    private const val PREFS_NAME = "chat_history_prefs"
    private const val KEY_CHAT_MESSAGES = "chat_messages"
    
    // Initialize Gson instance
    private val gson = Gson()
    
    /**
     * Save chat messages to SharedPreferences
     */
    fun saveChatMessages(context: Context, messages: List<PhotoReasoningMessage>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val json = gson.toJson(messages)
        editor.putString(KEY_CHAT_MESSAGES, json)
        editor.apply()
    }
    
    /**
     * Load chat messages from SharedPreferences
     */
    fun loadChatMessages(context: Context): List<PhotoReasoningMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CHAT_MESSAGES, null) ?: return emptyList()
        
        val listType: Type = object : TypeToken<List<PhotoReasoningMessage>>() {}.type
        return try {
            gson.fromJson(json, listType)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Clear all chat messages from SharedPreferences
     */
    fun clearChatMessages(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove(KEY_CHAT_MESSAGES)
        editor.apply()
    }
}
