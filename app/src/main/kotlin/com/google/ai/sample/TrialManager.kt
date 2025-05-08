package com.google.ai.sample

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

object TrialManager {

    private const val PREFS_NAME = "TrialPrefs"
    const val TRIAL_DURATION_MS = 30 * 60 * 1000L // 30 minutes in milliseconds

    // Key for storing the trial end time as a plain Long (unencrypted)
    private const val KEY_TRIAL_END_TIME_UNENCRYPTED = "trialUtcEndTimeUnencrypted"
    private const val KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME = "trialAwaitingFirstInternetTime"
    private const val KEY_PURCHASED_FLAG = "appPurchased"

    private const val TAG = "TrialManager"

    // Keystore and encryption related constants are no longer used for storing trial end time
    // but kept here in case they are used for other purposes or future reinstatement.
    // private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    // private const val KEY_ALIAS_TRIAL_END_TIME_KEY = "TrialEndTimeEncryptionKeyAlias"
    // private const val KEY_ENCRYPTED_TRIAL_UTC_END_TIME = "encryptedTrialUtcEndTime" // No longer used for saving
    // private const val KEY_ENCRYPTION_IV = "encryptionIv" // No longer used for saving
    // private const val ENCRYPTION_TRANSFORMATION = "AES/GCM/NoPadding"
    // private const val ENCRYPTION_BLOCK_SIZE = 12

    enum class TrialState {
        NOT_YET_STARTED_AWAITING_INTERNET,
        ACTIVE_INTERNET_TIME_CONFIRMED,
        EXPIRED_INTERNET_TIME_CONFIRMED,
        PURCHASED,
        INTERNET_UNAVAILABLE_CANNOT_VERIFY
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Simplified function to save trial end time as a plain Long
    private fun saveTrialUtcEndTime(context: Context, utcEndTimeMs: Long) {
        val editor = getSharedPreferences(context).edit()
        editor.putLong(KEY_TRIAL_END_TIME_UNENCRYPTED, utcEndTimeMs)
        editor.apply()
        Log.d(TAG, "Saved unencrypted UTC end time: $utcEndTimeMs")
    }

    // Simplified function to get trial end time as a plain Long
    private fun getTrialUtcEndTime(context: Context): Long? {
        val prefs = getSharedPreferences(context)
        if (!prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED)) {
            Log.d(TAG, "No unencrypted trial end time found.")
            return null
        }
        val endTime = prefs.getLong(KEY_TRIAL_END_TIME_UNENCRYPTED, -1L)
        return if (endTime == -1L) {
            Log.w(TAG, "Found unencrypted end time key, but value was -1L, treating as not found.")
            null
        } else {
            Log.d(TAG, "Retrieved unencrypted UTC end time: $endTime")
            endTime
        }
    }

    fun startTrialIfNecessaryWithInternetTime(context: Context, currentUtcTimeMs: Long) {
        val prefs = getSharedPreferences(context)
        if (isPurchased(context)) {
            Log.d(TAG, "App is purchased, no trial needed.")
            return
        }
        // Only start if no end time is set AND we are awaiting the first internet time.
        if (getTrialUtcEndTime(context) == null && prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)) {
            val utcEndTimeMs = currentUtcTimeMs + TRIAL_DURATION_MS
            saveTrialUtcEndTime(context, utcEndTimeMs) // Use simplified save function
            // Crucially, set awaiting flag to false *after* attempting to save.
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
            Log.i(TAG, "Trial started with internet time (unencrypted). Ends at UTC: $utcEndTimeMs. Awaiting flag set to false.")
        } else {
            val existingEndTime = getTrialUtcEndTime(context)
            Log.d(TAG, "Trial not started: Existing EndTime: $existingEndTime, AwaitingInternet: ${prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)}")
        }
    }

    fun getTrialState(context: Context, currentUtcTimeMs: Long?): TrialState {
        if (isPurchased(context)) {
            return TrialState.PURCHASED
        }

        val prefs = getSharedPreferences(context)
        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        val trialUtcEndTime = getTrialUtcEndTime(context) // Use simplified get function

        if (currentUtcTimeMs == null) {
            return if (trialUtcEndTime == null && isAwaitingFirstInternetTime) {
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }

        return when {
            trialUtcEndTime == null && isAwaitingFirstInternetTime -> TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            trialUtcEndTime == null && !isAwaitingFirstInternetTime -> {
                Log.e(TAG, "CRITICAL INCONSISTENCY: Trial marked as started (not awaiting internet), but no trial end time found. Check save/load logic. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
            trialUtcEndTime != null && currentUtcTimeMs < trialUtcEndTime -> TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
            trialUtcEndTime != null && currentUtcTimeMs >= trialUtcEndTime -> TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            else -> {
                Log.e(TAG, "Unhandled case in getTrialState. isAwaiting: $isAwaitingFirstInternetTime, endTime: $trialUtcEndTime. Defaulting to NOT_YET_STARTED_AWAITING_INTERNET.")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
        }
    }

    fun markAsPurchased(context: Context) {
        val editor = getSharedPreferences(context).edit()
        // Remove old encryption keys if they exist, and the new unencrypted key
        // editor.remove("encryptedTrialUtcEndTime") // Key name from previous versions if needed for cleanup
        // editor.remove("encryptionIv") // Key name from previous versions if needed for cleanup
        // editor.remove("encryptedTrialUtcEndTime_unencrypted_fallback") // Key name from previous versions
        editor.remove(KEY_TRIAL_END_TIME_UNENCRYPTED)
        editor.remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        editor.putBoolean(KEY_PURCHASED_FLAG, true)
        editor.apply()

        // Keystore cleanup is not strictly necessary if the key wasn't used for this unencrypted version,
        // but good practice if we want to ensure no old trial keys remain.
        // However, to minimize changes, we will skip Keystore interactions for this diagnostic step.
        /*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                if (keyStore.containsAlias("TrialEndTimeEncryptionKeyAlias")) {
                    keyStore.deleteEntry("TrialEndTimeEncryptionKeyAlias")
                    Log.d(TAG, "Trial encryption key deleted from KeyStore.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete trial encryption key from KeyStore", e)
            }
        }
        */
        Log.i(TAG, "App marked as purchased. Trial data (including unencrypted end time) cleared.")
    }

    private fun isPurchased(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_PURCHASED_FLAG, false)
    }

    fun initializeTrialStateFlagsIfNecessary(context: Context) {
        val prefs = getSharedPreferences(context)
        // Check if any trial-related flags or the new unencrypted end time key exist.
        // If none exist, it's likely a fresh install or data cleared state.
        if (!prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME) &&
            !prefs.contains(KEY_PURCHASED_FLAG) &&
            !prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED) // Check for the new unencrypted key
            // !prefs.contains("encryptedTrialUtcEndTime") && // Check for old keys if comprehensive cleanup is desired
            // !prefs.contains("encryptedTrialUtcEndTime_unencrypted_fallback")
        ) {
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
            Log.d(TAG, "Initialized KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true for a fresh state (unencrypted storage)." )
        }
    }
}

