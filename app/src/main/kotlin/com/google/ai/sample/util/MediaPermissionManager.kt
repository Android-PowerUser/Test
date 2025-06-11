package com.google.ai.sample.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class MediaPermissionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun incrementMediaPermissionDenialCount() {
        val currentCount = prefs.getInt(KEY_MEDIA_PERMISSION_DENIAL_COUNT, 0) // Read directly before incrementing
        val newCount = currentCount + 1
        prefs.edit().putInt(KEY_MEDIA_PERMISSION_DENIAL_COUNT, newCount).apply()
        Log.d(TAG, "Incremented media permission denial count. New count: $newCount")
    }

    fun getMediaPermissionDenialCount(): Int {
        val count = prefs.getInt(KEY_MEDIA_PERMISSION_DENIAL_COUNT, 0)
        Log.d(TAG, "Retrieved media permission denial count: $count")
        return count
    }

    fun resetMediaPermissionDenialCount() {
        prefs.edit().putInt(KEY_MEDIA_PERMISSION_DENIAL_COUNT, 0).apply()
        Log.d(TAG, "Reset media permission denial count to 0.")
    }

    companion object {
        private const val TAG = "PermissionWorkflow" // Consistent tag
        private const val PREFS_NAME = "MediaPermissionPrefs"
        private const val KEY_MEDIA_PERMISSION_DENIAL_COUNT = "mediaPermissionDenialCount"
    }
}
