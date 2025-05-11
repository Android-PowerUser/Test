package com.google.ai.sample

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object TrialManager {

    private const val PREFS_NAME = "TrialPrefs"
    const val TRIAL_DURATION_MS = 30 * 60 * 1000L // 30 minutes in milliseconds

    // SharedPreferences keys for flags. The actual timestamp is managed via Keystore aliases.
    private const val KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME = "trialAwaitingFirstInternetTime"
    private const val KEY_PURCHASED_FLAG = "appPurchased"
    // private const val KEY_TRIAL_CONFIRMED_EXPIRED = "trialConfirmedExpired" // REMOVED

    private const val TAG = "TrialManager"

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

    private fun recordTrialEndTimeInKeystore(context: Context, utcEndTimeMs: Long) {
        Log.d(TAG, "recordTrialEndTimeInKeystore called with utcEndTimeMs: $utcEndTimeMs")
        KeystoreHelper.storeTimestampAlias(context, utcEndTimeMs)
        Log.d(TAG, "Requested storage of trial end time alias for $utcEndTimeMs in Keystore.")
    }

    private fun getTrialEndTimeFromKeystore(context: Context): Long? {
        Log.d(TAG, "getTrialEndTimeFromKeystore called")
        val endTime = KeystoreHelper.getStoredTimestampFromAlias()
        if (endTime == null) {
            Log.d(TAG, "No trial end time alias found in Keystore.")
        } else {
            Log.d(TAG, "Retrieved trial end time from Keystore alias: $endTime")
        }
        return endTime
    }

    fun startTrialIfNecessaryWithInternetTime(context: Context, currentUtcTimeMs: Long) {
        Log.d(TAG, "startTrialIfNecessaryWithInternetTime called with currentUtcTimeMs: $currentUtcTimeMs")
        val prefs = getSharedPreferences(context)
        if (isPurchased(context)) {
            Log.d(TAG, "App is purchased, no trial needed. Skipping trial start.")
            return
        }
        
        val existingEndTimeInKeystore = getTrialEndTimeFromKeystore(context)
        val isAwaitingFirstInternet = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        Log.d(TAG, "Checking conditions to start trial: existingEndTimeInKeystore: $existingEndTimeInKeystore, isAwaitingFirstInternet: $isAwaitingFirstInternet")

        if (existingEndTimeInKeystore == null && isAwaitingFirstInternet) {
            val utcEndTimeMs = currentUtcTimeMs + TRIAL_DURATION_MS
            Log.d(TAG, "Conditions met to start trial. Calculated utcEndTimeMs: $utcEndTimeMs")
            recordTrialEndTimeInKeystore(context, utcEndTimeMs)
            
            Log.d(TAG, "Setting KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to false.")
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
            Log.i(TAG, "Trial started with internet time (Keystore alias). Ends at UTC: $utcEndTimeMs. Awaiting flag set to false.")
        } else {
            if (existingEndTimeInKeystore != null) {
                Log.d(TAG, "Trial not started: End time already exists in Keystore ($existingEndTimeInKeystore).")
                if (isAwaitingFirstInternet) {
                     Log.w(TAG, "Inconsistency: Trial end time found in Keystore, but KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME is true. Setting to false.")
                     prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
                }
            } else { 
                 Log.d(TAG, "Trial not started: Not awaiting first internet (flag is false), but no Keystore alias found. This might mean Keystore was cleared or failed to save previously.")
            }
        }
    }

    fun getTrialState(context: Context, currentUtcTimeMs: Long?): TrialState {
        Log.d(TAG, "getTrialState called with currentUtcTimeMs: $currentUtcTimeMs")
        val prefs = getSharedPreferences(context) 

        if (isPurchased(context)) {
            Log.d(TAG, "getTrialState: App is purchased. Returning TrialState.PURCHASED")
            return TrialState.PURCHASED
        }

        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        val trialUtcEndTime = getTrialEndTimeFromKeystore(context)
        // val confirmedExpired = prefs.getBoolean(KEY_TRIAL_CONFIRMED_EXPIRED, false) // REMOVED
        Log.d(TAG, "getTrialState: isAwaitingFirstInternetTime: $isAwaitingFirstInternetTime, trialUtcEndTime (from Keystore): $trialUtcEndTime")

        // if (confirmedExpired) { // REMOVED
        //     Log.d(TAG, "getTrialState: Trial previously confirmed expired (flag was true). Returning EXPIRED_INTERNET_TIME_CONFIRMED.")
        //     return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        // }

        if (currentUtcTimeMs == null) {
            Log.d(TAG, "getTrialState: currentUtcTimeMs is null.")
            // If we have an end time from Keystore, but no current time, we can't confirm expiry.
            // However, if that end time *could* be in the past, this is ambiguous.
            // The primary role here is to determine if we're awaiting or if we need internet.
            return if (trialUtcEndTime == null && isAwaitingFirstInternetTime) {
                Log.d(TAG, "getTrialState: Returning NOT_YET_STARTED_AWAITING_INTERNET (no Keystore alias, awaiting internet)")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                // This means either trial has started (endTime exists in Keystore) or it's not awaiting first internet,
                // but we don't have current time to check. Or, endTime is null but we are not awaiting (inconsistent state).
                // Or, Keystore has an end time which *might* be expired, but we can't be sure without current time.
                Log.d(TAG, "getTrialState: Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY (Keystore alias might exist or not awaiting, but no current time)")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }

        // currentUtcTimeMs is NOT null from this point onwards
        Log.d(TAG, "getTrialState: currentUtcTimeMs is $currentUtcTimeMs. Evaluating state based on time.")
        return when {
            trialUtcEndTime == null && isAwaitingFirstInternetTime -> {
                Log.d(TAG, "getTrialState: Case A: trialUtcEndTime (Keystore) is null AND isAwaitingFirstInternetTime is true. Returning NOT_YET_STARTED_AWAITING_INTERNET")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
            trialUtcEndTime == null && !isAwaitingFirstInternetTime -> {
                Log.e(TAG, "INCONSISTENCY: Trial marked as started (not awaiting internet in prefs), but no trial end time alias found in Keystore. Resetting to await internet.")
                prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET 
            }
            trialUtcEndTime != null -> { 
                if(isAwaitingFirstInternetTime) {
                    Log.w(TAG, "Inconsistency: Active trial (Keystore data exists), but KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME is true. Setting to false.")
                    prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
                }
                if (currentUtcTimeMs < trialUtcEndTime) {
                    Log.d(TAG, "getTrialState: Case B: trialUtcEndTime (Keystore: $trialUtcEndTime) > currentUtcTimeMs ($currentUtcTimeMs). Returning ACTIVE_INTERNET_TIME_CONFIRMED")
                    TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
                } else { 
                    Log.i(TAG, "getTrialState: Case C: trialUtcEndTime (Keystore: $trialUtcEndTime) <= currentUtcTimeMs ($currentUtcTimeMs). Trial EXPIRED.")
                    // No longer setting KEY_TRIAL_CONFIRMED_EXPIRED flag here. Expiry is purely based on time comparison.
                    TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
                }
            }
            else -> {
                Log.e(TAG, "Unhandled case in getTrialState. isAwaiting: $isAwaitingFirstInternetTime, endTimeFromKeystore: $trialUtcEndTime, currentTime: $currentUtcTimeMs. Defaulting to NOT_YET_STARTED_AWAITING_INTERNET.")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
        }
    }

    fun markAsPurchased(context: Context) {
        Log.d(TAG, "markAsPurchased called")
        val editor = getSharedPreferences(context).edit()
        
        Log.d(TAG, "Deleting trial timestamp alias(es) from Keystore.")
        KeystoreHelper.deleteAllTimestampAliases() 

        Log.d(TAG, "Removing SharedPreferences keys: KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME") // KEY_TRIAL_CONFIRMED_EXPIRED removed from this list
        editor.remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        // editor.remove(KEY_TRIAL_CONFIRMED_EXPIRED) // REMOVED
        
        Log.d(TAG, "Setting KEY_PURCHASED_FLAG to true")
        editor.putBoolean(KEY_PURCHASED_FLAG, true)
        editor.apply()
        Log.i(TAG, "App marked as purchased. Trial data (Keystore alias and relevant prefs) cleared.")
    }

    private fun isPurchased(context: Context): Boolean {
        val purchased = getSharedPreferences(context).getBoolean(KEY_PURCHASED_FLAG, false)
        return purchased
    }

    fun initializeTrialStateFlagsIfNecessary(context: Context) {
        Log.d(TAG, "initializeTrialStateFlagsIfNecessary called")
        val prefs = getSharedPreferences(context)
        
        if (isPurchased(context)) {
            Log.d(TAG, "App is purchased. No trial initialization needed.")
            KeystoreHelper.deleteAllTimestampAliases()
            if (prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)) {
                prefs.edit().remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME).apply()
            }
            return
        }

        val awaitingFlagExists = prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        val trialEndTimeInKeystore = getTrialEndTimeFromKeystore(context)

        Log.d(TAG, "Checking for existing flags/state: awaitingFlagExists=$awaitingFlagExists, trialEndTimeInKeystore=$trialEndTimeInKeystore")

        if (trialEndTimeInKeystore == null) {
            if (!awaitingFlagExists) {
                Log.d(TAG, "No Keystore trial data and no awaiting flag. Initializing KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true.")
                prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
            } else {
                val isAwaiting = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
                if (!isAwaiting) {
                    Log.w(TAG, "Inconsistency: No Keystore trial data, but KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME is false. Resetting to true.")
                    prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
                } else {
                    Log.d(TAG, "No Keystore trial data, and KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME is true. State is consistent for pre-trial.")
                }
            }
        } else {
            Log.d(TAG, "Keystore has trial data (ends at $trialEndTimeInKeystore). Ensuring KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME is false.")
            if (!awaitingFlagExists || prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false)) { // If flag exists and is true, or doesn't exist
                prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
                Log.d(TAG, "Set KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to false as Keystore has trial data.")
            }
        }
    }
}
