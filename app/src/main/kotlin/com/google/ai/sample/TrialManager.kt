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

    private const val TAG = "TrialManager_DEBUG" // Changed TAG for clarity

    enum class TrialState {
        NOT_YET_STARTED_AWAITING_INTERNET,
        ACTIVE_INTERNET_TIME_CONFIRMED,
        EXPIRED_INTERNET_TIME_CONFIRMED,
        PURCHASED,
        INTERNET_UNAVAILABLE_CANNOT_VERIFY
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        Log.d(TAG, "getSharedPreferences called")
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Using unencrypted storage as per previous diagnostic step, now with more logging
    private fun saveTrialUtcEndTime(context: Context, utcEndTimeMs: Long) {
        Log.i(TAG, "saveTrialUtcEndTime: Attempting to save UTC end time: $utcEndTimeMs")
        val editor = getSharedPreferences(context).edit()
        editor.putLong(KEY_TRIAL_END_TIME_UNENCRYPTED, utcEndTimeMs)
        editor.apply()
        Log.i(TAG, "saveTrialUtcEndTime: Successfully saved unencrypted UTC end time: $utcEndTimeMs to key $KEY_TRIAL_END_TIME_UNENCRYPTED")
    }

    private fun getTrialUtcEndTime(context: Context): Long? {
        Log.d(TAG, "getTrialUtcEndTime: Attempting to retrieve trial UTC end time.")
        val prefs = getSharedPreferences(context)
        if (!prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED)) {
            Log.d(TAG, "getTrialUtcEndTime: No unencrypted trial end time found for key $KEY_TRIAL_END_TIME_UNENCRYPTED.")
            return null
        }
        val endTime = prefs.getLong(KEY_TRIAL_END_TIME_UNENCRYPTED, -1L)
        return if (endTime == -1L) {
            Log.w(TAG, "getTrialUtcEndTime: Found unencrypted end time key $KEY_TRIAL_END_TIME_UNENCRYPTED, but value was -1L (default), treating as not found.")
            null
        } else {
            Log.i(TAG, "getTrialUtcEndTime: Retrieved unencrypted UTC end time: $endTime from key $KEY_TRIAL_END_TIME_UNENCRYPTED")
            endTime
        }
    }

    fun startTrialIfNecessaryWithInternetTime(context: Context, currentUtcTimeMs: Long) {
        Log.i(TAG, "startTrialIfNecessaryWithInternetTime: Called with currentUtcTimeMs: $currentUtcTimeMs")
        val prefs = getSharedPreferences(context)
        if (isPurchased(context)) {
            Log.d(TAG, "startTrialIfNecessaryWithInternetTime: App is purchased, no trial needed.")
            return
        }

        val existingEndTime = getTrialUtcEndTime(context)
        val isAwaiting = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        Log.d(TAG, "startTrialIfNecessaryWithInternetTime: Existing EndTime: $existingEndTime, isAwaitingFirstInternetTime: $isAwaiting")

        // Only start if no end time is set AND we are awaiting the first internet time.
        if (existingEndTime == null && isAwaiting) {
            val utcEndTimeMs = currentUtcTimeMs + TRIAL_DURATION_MS
            Log.d(TAG, "startTrialIfNecessaryWithInternetTime: Conditions met to start trial. Calculated utcEndTimeMs: $utcEndTimeMs")
            saveTrialUtcEndTime(context, utcEndTimeMs)
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
            Log.i(TAG, "startTrialIfNecessaryWithInternetTime: Trial started. Ends at UTC: $utcEndTimeMs. KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME set to false.")
        } else {
            Log.d(TAG, "startTrialIfNecessaryWithInternetTime: Trial not started or already started. Existing EndTime: $existingEndTime, AwaitingInternet: $isAwaiting")
        }
    }

    fun getTrialState(context: Context, currentUtcTimeMs: Long?): TrialState {
        Log.i(TAG, "getTrialState: Called with currentUtcTimeMs: ${currentUtcTimeMs ?: "null"}")
        if (isPurchased(context)) {
            Log.d(TAG, "getTrialState: App is purchased. Returning PURCHASED.")
            return TrialState.PURCHASED
        }

        val prefs = getSharedPreferences(context)
        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        val trialUtcEndTime = getTrialUtcEndTime(context)
        Log.d(TAG, "getTrialState: isAwaitingFirstInternetTime: $isAwaitingFirstInternetTime, trialUtcEndTime: ${trialUtcEndTime ?: "null"}")

        if (currentUtcTimeMs == null) {
            val stateToReturn = if (trialUtcEndTime == null && isAwaitingFirstInternetTime) {
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                // If end time exists, or if not awaiting, but no internet, we can't verify.
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
            Log.d(TAG, "getTrialState: currentUtcTimeMs is null. Returning $stateToReturn")
            return stateToReturn
        }

        // currentUtcTimeMs is NOT null from here
        val calculatedState = when {
            trialUtcEndTime == null && isAwaitingFirstInternetTime -> TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            trialUtcEndTime == null && !isAwaitingFirstInternetTime -> {
                Log.e(TAG, "getTrialState: CRITICAL INCONSISTENCY: Trial marked as started (not awaiting internet), but no trial end time found. Check save/load logic. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
            trialUtcEndTime != null && currentUtcTimeMs < trialUtcEndTime -> TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
            trialUtcEndTime != null && currentUtcTimeMs >= trialUtcEndTime -> TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            else -> {
                Log.e(TAG, "getTrialState: Unhandled case. isAwaiting: $isAwaitingFirstInternetTime, endTime: $trialUtcEndTime. Defaulting to NOT_YET_STARTED_AWAITING_INTERNET.")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
        }
        Log.i(TAG, "getTrialState: Calculated state: $calculatedState")
        return calculatedState
    }

    fun markAsPurchased(context: Context) {
        Log.i(TAG, "markAsPurchased: Marking app as purchased.")
        val editor = getSharedPreferences(context).edit()
        editor.remove(KEY_TRIAL_END_TIME_UNENCRYPTED)
        editor.remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        editor.putBoolean(KEY_PURCHASED_FLAG, true)
        editor.apply()
        Log.i(TAG, "markAsPurchased: App marked as purchased. Trial data (unencrypted end time) cleared.")
    }

    private fun isPurchased(context: Context): Boolean {
        val purchased = getSharedPreferences(context).getBoolean(KEY_PURCHASED_FLAG, false)
        Log.d(TAG, "isPurchased: Returning $purchased")
        return purchased
    }

    fun initializeTrialStateFlagsIfNecessary(context: Context) {
        Log.d(TAG, "initializeTrialStateFlagsIfNecessary: Checking if flags need initialization.")
        val prefs = getSharedPreferences(context)
        if (!prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME) &&
            !prefs.contains(KEY_PURCHASED_FLAG) &&
            !prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED)
        ) {
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
            Log.i(TAG, "initializeTrialStateFlagsIfNecessary: Initialized KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true for a fresh state.")
        } else {
            Log.d(TAG, "initializeTrialStateFlagsIfNecessary: Flags already exist or not a fresh state. Awaiting: ${prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)}, Purchased: ${prefs.contains(KEY_PURCHASED_FLAG)}, EndTime: ${prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED)}")
        }
    }
}

