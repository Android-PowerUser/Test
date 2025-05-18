package io.github.Android-PowerUser

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manager class for handling API key storage and retrieval
 */
class ApiKeyManager(context: Context) {
    private val TAG = "ApiKeyManager"
    private val PREFS_NAME = "api_key_prefs"
    private val API_KEYS = "api_keys"
    private val CURRENT_KEY_INDEX = "current_key_index"
    private val FAILED_KEYS = "failed_keys"
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get the current API key
     * @return The current API key or null if none exists
     */
    fun getCurrentApiKey(): String? {
        val keys = getApiKeys()
        if (keys.isEmpty()) {
            Log.d(TAG, "No API keys found")
            return null
        }
        
        val currentIndex = prefs.getInt(CURRENT_KEY_INDEX, 0)
        val safeIndex = if (currentIndex >= keys.size) 0 else currentIndex
        
        Log.d(TAG, "Getting current API key at index $safeIndex")
        return keys[safeIndex]
    }
    
    /**
     * Get all stored API keys
     * @return List of API keys
     */
    fun getApiKeys(): List<String> {
        val keysString = prefs.getString(API_KEYS, "") ?: ""
        return if (keysString.isEmpty()) {
            emptyList()
        } else {
            keysString.split(",")
        }
    }
    
    /**
     * Add a new API key
     * @param apiKey The API key to add
     * @return True if the key was added, false if it already exists
     */
    fun addApiKey(apiKey: String): Boolean {
        if (apiKey.isBlank()) {
            Log.d(TAG, "Attempted to add blank API key")
            return false
        }
        
        val keys = getApiKeys().toMutableList()
        
        // Check if key already exists
        if (keys.contains(apiKey)) {
            Log.d(TAG, "API key already exists")
            return false
        }
        
        keys.add(apiKey)
        saveApiKeys(keys)
        
        // If this is the first key, set it as current
        if (keys.size == 1) {
            setCurrentKeyIndex(0)
        }
        
        // Clear this key from failed keys if it was previously marked as failed
        removeFailedKey(apiKey)
        
        Log.d(TAG, "Added new API key, total keys: ${keys.size}")
        return true
    }
    
    /**
     * Remove an API key
     * @param apiKey The API key to remove
     * @return True if the key was removed, false if it doesn't exist
     */
    fun removeApiKey(apiKey: String): Boolean {
        val keys = getApiKeys().toMutableList()
        val removed = keys.remove(apiKey)
        
        if (removed) {
            saveApiKeys(keys)
            
            // Adjust current index if needed
            val currentIndex = prefs.getInt(CURRENT_KEY_INDEX, 0)
            if (currentIndex >= keys.size && keys.isNotEmpty()) {
                setCurrentKeyIndex(0)
            }
            
            // Also remove from failed keys if present
            removeFailedKey(apiKey)
            
            Log.d(TAG, "Removed API key, remaining keys: ${keys.size}")
        } else {
            Log.d(TAG, "API key not found for removal")
        }
        
        return removed
    }
    
    /**
     * Set the current API key index
     * @param index The index of the API key to set as current
     * @return True if successful, false if index is invalid
     */
    fun setCurrentKeyIndex(index: Int): Boolean {
        val keys = getApiKeys()
        if (index < 0 || index >= keys.size) {
            Log.d(TAG, "Invalid API key index: $index, max: ${keys.size - 1}")
            return false
        }
        
        prefs.edit().putInt(CURRENT_KEY_INDEX, index).apply()
        Log.d(TAG, "Set current API key index to $index")
        return true
    }
    
    /**
     * Get the current API key index
     * @return The current API key index
     */
    fun getCurrentKeyIndex(): Int {
        return prefs.getInt(CURRENT_KEY_INDEX, 0)
    }
    
    /**
     * Mark an API key as failed (e.g., due to 503 error)
     * @param apiKey The API key to mark as failed
     */
    fun markKeyAsFailed(apiKey: String) {
        val failedKeys = getFailedKeys().toMutableList()
        if (!failedKeys.contains(apiKey)) {
            failedKeys.add(apiKey)
            saveFailedKeys(failedKeys)
            Log.d(TAG, "Marked API key as failed: ${apiKey.take(5)}...")
        }
    }
    
    /**
     * Remove an API key from the failed keys list
     * @param apiKey The API key to remove from failed keys
     */
    fun removeFailedKey(apiKey: String) {
        val failedKeys = getFailedKeys().toMutableList()
        if (failedKeys.remove(apiKey)) {
            saveFailedKeys(failedKeys)
            Log.d(TAG, "Removed API key from failed keys: ${apiKey.take(5)}...")
        }
    }
    
    /**
     * Get all failed API keys
     * @return List of failed API keys
     */
    fun getFailedKeys(): List<String> {
        val keysString = prefs.getString(FAILED_KEYS, "") ?: ""
        return if (keysString.isEmpty()) {
            emptyList()
        } else {
            keysString.split(",")
        }
    }
    
    /**
     * Check if an API key is marked as failed
     * @param apiKey The API key to check
     * @return True if the key is marked as failed, false otherwise
     */
    fun isKeyFailed(apiKey: String): Boolean {
        return getFailedKeys().contains(apiKey)
    }
    
    /**
     * Reset all failed keys
     */
    fun resetFailedKeys() {
        prefs.edit().remove(FAILED_KEYS).apply()
        Log.d(TAG, "Reset all failed keys")
    }
    
    /**
     * Check if all API keys are marked as failed
     * @return True if all keys are failed, false otherwise
     */
    fun areAllKeysFailed(): Boolean {
        val keys = getApiKeys()
        val failedKeys = getFailedKeys()
        return keys.isNotEmpty() && failedKeys.size >= keys.size
    }
    
    /**
     * Get the count of available API keys
     * @return The number of API keys
     */
    fun getKeyCount(): Int {
        return getApiKeys().size
    }
    
    /**
     * Switch to the next available API key that is not marked as failed
     * @return The new API key or null if no valid keys are available
     */
    fun switchToNextAvailableKey(): String? {
        val keys = getApiKeys()
        if (keys.isEmpty()) {
            Log.d(TAG, "No API keys available to switch to")
            return null
        }
        
        val failedKeys = getFailedKeys()
        val currentIndex = getCurrentKeyIndex()
        
        // If all keys are failed, reset failed keys and start from the beginning
        if (failedKeys.size >= keys.size) {
            Log.d(TAG, "All keys are marked as failed, resetting failed keys")
            resetFailedKeys()
            setCurrentKeyIndex(0)
            return keys[0]
        }
        
        // Find the next key that is not failed
        var nextIndex = (currentIndex + 1) % keys.size
        var attempts = 0
        
        while (attempts < keys.size) {
            if (!failedKeys.contains(keys[nextIndex])) {
                setCurrentKeyIndex(nextIndex)
                Log.d(TAG, "Switched to next available key at index $nextIndex")
                return keys[nextIndex]
            }
            nextIndex = (nextIndex + 1) % keys.size
            attempts++
        }
        
        // If we get here, all keys are failed (shouldn't happen due to earlier check)
        Log.d(TAG, "Could not find a non-failed key, resetting failed keys")
        resetFailedKeys()
        setCurrentKeyIndex(0)
        return keys[0]
    }
    
    /**
     * Save the list of API keys to SharedPreferences
     * @param keys The list of API keys to save
     */
    private fun saveApiKeys(keys: List<String>) {
        val keysString = keys.joinToString(",")
        prefs.edit().putString(API_KEYS, keysString).apply()
    }
    
    /**
     * Save the list of failed API keys to SharedPreferences
     * @param keys The list of failed API keys to save
     */
    private fun saveFailedKeys(keys: List<String>) {
        val keysString = keys.joinToString(",")
        prefs.edit().putString(FAILED_KEYS, keysString).apply()
    }
    
    /**
     * Clear all stored API keys
     */
    fun clearAllKeys() {
        prefs.edit().remove(API_KEYS).remove(CURRENT_KEY_INDEX).remove(FAILED_KEYS).apply()
        Log.d(TAG, "Cleared all API keys")
    }
    
    companion object {
        @Volatile
        private var INSTANCE: ApiKeyManager? = null
        
        fun getInstance(context: Context): ApiKeyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiKeyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
