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
    private const val KEY_TRIAL_CONFIRMED_EXPIRED = "trialConfirmedExpired" // Added for persisting confirmed expiry

    private const val TAG = "TrialManager"

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

    private fun saveTrialUtcEndTime(context: Context, utcEndTimeMs: Long) {
        Log.d(TAG, "saveTrialUtcEndTime called with utcEndTimeMs: $utcEndTimeMs")
        val editor = getSharedPreferences(context).edit()
        editor.putLong(KEY_TRIAL_END_TIME_UNENCRYPTED, utcEndTimeMs)
        Log.d(TAG, "Saving KEY_TRIAL_END_TIME_UNENCRYPTED: $utcEndTimeMs")
        editor.apply()
        Log.d(TAG, "Saved unencrypted UTC end time: $utcEndTimeMs")
    }

    private fun getTrialUtcEndTime(context: Context): Long? {
        Log.d(TAG, "getTrialUtcEndTime called")
        val prefs = getSharedPreferences(context)
        if (!prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED)) {
            Log.d(TAG, "No unencrypted trial end time found (KEY_TRIAL_END_TIME_UNENCRYPTED does not exist).")
            return null
        }
        val endTime = prefs.getLong(KEY_TRIAL_END_TIME_UNENCRYPTED, -1L)
        Log.d(TAG, "Raw value for KEY_TRIAL_END_TIME_UNENCRYPTED: $endTime")
        return if (endTime == -1L) {
            Log.w(TAG, "Found unencrypted end time key, but value was -1L, treating as not found.")
            null
        } else {
            Log.d(TAG, "Retrieved unencrypted UTC end time: $endTime")
            endTime
        }
    }

    fun startTrialIfNecessaryWithInternetTime(context: Context, currentUtcTimeMs: Long) {
        Log.d(TAG, "startTrialIfNecessaryWithInternetTime called with currentUtcTimeMs: $currentUtcTimeMs")
        val prefs = getSharedPreferences(context)
        if (isPurchased(context)) {
            Log.d(TAG, "App is purchased, no trial needed. Skipping trial start.")
            return
        }
        val existingEndTime = getTrialUtcEndTime(context)
        val isAwaitingFirstInternet = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        Log.d(TAG, "Checking conditions to start trial: existingEndTime: $existingEndTime, isAwaitingFirstInternet: $isAwaitingFirstInternet")

        if (existingEndTime == null && isAwaitingFirstInternet) {
            val utcEndTimeMs = currentUtcTimeMs + TRIAL_DURATION_MS
            Log.d(TAG, "Conditions met to start trial. Calculated utcEndTimeMs: $utcEndTimeMs")
            saveTrialUtcEndTime(context, utcEndTimeMs)
            Log.d(TAG, "Setting KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to false.")
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
            Log.i(TAG, "Trial started with internet time (unencrypted). Ends at UTC: $utcEndTimeMs. Awaiting flag set to false.")
        } else {
            Log.d(TAG, "Trial not started. Conditions not met. Existing EndTime: $existingEndTime, AwaitingInternet: $isAwaitingFirstInternet")
        }
    }

    fun getTrialState(context: Context, currentUtcTimeMs: Long?): TrialState {
        Log.d(TAG, "getTrialState called with currentUtcTimeMs: $currentUtcTimeMs")
        if (isPurchased(context)) {
            Log.d(TAG, "getTrialState: App is purchased. Returning TrialState.PURCHASED")
            return TrialState.PURCHASED
        }

        val prefs = getSharedPreferences(context)
        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        val trialUtcEndTime = getTrialUtcEndTime(context)
        val confirmedExpired = prefs.getBoolean(KEY_TRIAL_CONFIRMED_EXPIRED, false)
        Log.d(TAG, "getTrialState: isAwaitingFirstInternetTime: $isAwaitingFirstInternetTime, trialUtcEndTime: $trialUtcEndTime, confirmedExpired: $confirmedExpired")

        if (confirmedExpired) {
            Log.d(TAG, "getTrialState: Trial previously confirmed expired. Returning EXPIRED_INTERNET_TIME_CONFIRMED.")
            return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        }

        if (currentUtcTimeMs == null) {
            Log.d(TAG, "getTrialState: currentUtcTimeMs is null.")
            return if (trialUtcEndTime == null && isAwaitingFirstInternetTime) {
                Log.d(TAG, "getTrialState: Returning NOT_YET_STARTED_AWAITING_INTERNET (no end time, awaiting internet)")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                Log.d(TAG, "getTrialState: Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY (end time might exist or not awaiting, but no current time)")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }

        Log.d(TAG, "getTrialState: currentUtcTimeMs is $currentUtcTimeMs. Evaluating state based on time.")
        return when {
            trialUtcEndTime == null && isAwaitingFirstInternetTime -> {
                Log.d(TAG, "getTrialState: Case 1: trialUtcEndTime is null AND isAwaitingFirstInternetTime is true. Returning NOT_YET_STARTED_AWAITING_INTERNET")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
            trialUtcEndTime == null && !isAwaitingFirstInternetTime -> {
                Log.e(TAG, "CRITICAL INCONSISTENCY: Trial marked as started (not awaiting internet), but no trial end time found. Check save/load logic. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
            trialUtcEndTime != null && currentUtcTimeMs < trialUtcEndTime -> {
                Log.d(TAG, "getTrialState: Case 2: trialUtcEndTime ($trialUtcEndTime) > currentUtcTimeMs ($currentUtcTimeMs). Returning ACTIVE_INTERNET_TIME_CONFIRMED")
                TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
            }
            trialUtcEndTime != null && currentUtcTimeMs >= trialUtcEndTime -> {
                Log.d(TAG, "getTrialState: Case 3: trialUtcEndTime ($trialUtcEndTime) <= currentUtcTimeMs ($currentUtcTimeMs). Returning EXPIRED_INTERNET_TIME_CONFIRMED")
                TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            }
            else -> {
                Log.e(TAG, "Unhandled case in getTrialState. isAwaiting: $isAwaitingFirstInternetTime, endTime: $trialUtcEndTime, currentTime: $currentUtcTimeMs. Defaulting to NOT_YET_STARTED_AWAITING_INTERNET.")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
        }
    }

    fun markAsPurchased(context: Context) {
        Log.d(TAG, "markAsPurchased called")
        val editor = getSharedPreferences(context).edit()
        Log.d(TAG, "Removing trial-related keys: KEY_TRIAL_END_TIME_UNENCRYPTED, KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME")
        editor.remove(KEY_TRIAL_END_TIME_UNENCRYPTED)
        editor.remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        Log.d(TAG, "Setting KEY_PURCHASED_FLAG to true")
        editor.putBoolean(KEY_PURCHASED_FLAG, true)
        editor.apply()
        Log.i(TAG, "App marked as purchased. Trial data (including unencrypted end time) cleared.")
    }

    private fun isPurchased(context: Context): Boolean {
        Log.d(TAG, "isPurchased called")
        val purchased = getSharedPreferences(context).getBoolean(KEY_PURCHASED_FLAG, false)
        Log.d(TAG, "isPurchased returning: $purchased")
        return purchased
    }

    fun initializeTrialStateFlagsIfNecessary(context: Context) {
        Log.d(TAG, "initializeTrialStateFlagsIfNecessary called")
        val prefs = getSharedPreferences(context)
        val awaitingFlagExists = prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        val purchasedFlagExists = prefs.contains(KEY_PURCHASED_FLAG)
        val endTimeExists = prefs.contains(KEY_TRIAL_END_TIME_UNENCRYPTED)
        Log.d(TAG, "Checking for existing flags: awaitingFlagExists=$awaitingFlagExists, purchasedFlagExists=$purchasedFlagExists, endTimeExists=$endTimeExists")

        if (!awaitingFlagExists && !purchasedFlagExists && !endTimeExists) {
            Log.d(TAG, "No trial-related flags found. Initializing KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true.")
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
            Log.d(TAG, "Initialized KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true for a fresh state (unencrypted storage)." )
        } else {
            Log.d(TAG, "One or more trial-related flags already exist. No initialization needed.")
        }
    }
}

