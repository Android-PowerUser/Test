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
        EXPIRED_INTERNET_TIME_CONFIRMED, // Dieser Status wird nun auch bei Ablauf durch lokale Zeit gesetzt
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

    fun getTrialState(context: Context, currentUtcTimeMsFromInternet: Long?): TrialState {
        Log.d(TAG, "getTrialState called with currentUtcTimeMsFromInternet: $currentUtcTimeMsFromInternet")
        val prefs = getSharedPreferences(context)

        if (isPurchased(context)) {
            Log.d(TAG, "getTrialState: App is purchased. Returning TrialState.PURCHASED")
            return TrialState.PURCHASED
        }

        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        val trialUtcEndTime = getTrialUtcEndTime(context) // Kann null sein, wenn nicht gestartet oder Fehler
        val confirmedExpired = prefs.getBoolean(KEY_TRIAL_CONFIRMED_EXPIRED, false)
        val currentLocalUtcTimeMs = System.currentTimeMillis() // Aktuelle lokale Zeit als UTC Millisekunden

        Log.d(TAG, "getTrialState Details: isAwaitingFirstInternetTime: $isAwaitingFirstInternetTime, trialUtcEndTime: $trialUtcEndTime, confirmedExpired: $confirmedExpired, currentLocalUtcTimeMs: $currentLocalUtcTimeMs, currentUtcTimeMsFromInternet: $currentUtcTimeMsFromInternet")

        if (confirmedExpired) {
            Log.d(TAG, "getTrialState: Trial previously confirmed expired (flag KEY_TRIAL_CONFIRMED_EXPIRED was true). Returning EXPIRED_INTERNET_TIME_CONFIRMED.")
            return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        }

        // Fall 1: Die Test-Endzeit wurde noch nicht per Internetzeit festgelegt.
        if (trialUtcEndTime == null) {
            return if (isAwaitingFirstInternetTime) {
                Log.d(TAG, "getTrialState: No trialUtcEndTime set, and awaiting first internet time. Returning NOT_YET_STARTED_AWAITING_INTERNET.")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                // Inkonsistenter Zustand: Sollte nicht passieren, wenn startTrialIfNecessaryWithInternetTime korrekt funktioniert.
                // Bedeutet, KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME ist false, aber es gibt keine Endzeit.
                Log.e(TAG, "getTrialState: Inconsistent state - trialUtcEndTime is null, but KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME is false. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }

        // Ab hier ist trialUtcEndTime NICHT null, d.h. der Trial wurde initial gestartet.

        // Fall 2: Prüfung auf Ablauf, wenn Internetzeit verfügbar ist.
        if (currentUtcTimeMsFromInternet != null) {
            if (currentUtcTimeMsFromInternet >= trialUtcEndTime || currentLocalUtcTimeMs >= trialUtcEndTime) {
                Log.i(TAG, "getTrialState: Trial EXPIRED. InternetTime: $currentUtcTimeMsFromInternet, LocalUTCTime: $currentLocalUtcTimeMs, EndTime: $trialUtcEndTime. Setting KEY_TRIAL_CONFIRMED_EXPIRED=true.")
                prefs.edit().putBoolean(KEY_TRIAL_CONFIRMED_EXPIRED, true).apply()
                return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            } else {
                Log.d(TAG, "getTrialState: Trial ACTIVE (confirmed by available internet time). Returning ACTIVE_INTERNET_TIME_CONFIRMED.")
                return TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
            }
        } else {
            // Fall 3: Internetzeit ist NICHT verfügbar. Prüfung auf Ablauf nur mit lokaler UTC-Zeit.
            if (currentLocalUtcTimeMs >= trialUtcEndTime) {
                Log.i(TAG, "getTrialState: Trial EXPIRED (based on local UTC time, no internet time). LocalUTCTime: $currentLocalUtcTimeMs, EndTime: $trialUtcEndTime. Setting KEY_TRIAL_CONFIRMED_EXPIRED=true.")
                prefs.edit().putBoolean(KEY_TRIAL_CONFIRMED_EXPIRED, true).apply()
                return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            } else {
                // Nicht abgelaufen gemäß lokaler Zeit, aber Internetzeit fehlt zur Bestätigung des "Aktiv"-Status.
                Log.d(TAG, "getTrialState: Trial NOT YET EXPIRED by local UTC time, but internet time is unavailable. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                return TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }
    }


    fun markAsPurchased(context: Context) {
        Log.d(TAG, "markAsPurchased called")
        val editor = getSharedPreferences(context).edit()
        Log.d(TAG, "Removing trial-related keys: KEY_TRIAL_END_TIME_UNENCRYPTED, KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, KEY_TRIAL_CONFIRMED_EXPIRED")
        editor.remove(KEY_TRIAL_END_TIME_UNENCRYPTED)
        editor.remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        editor.remove(KEY_TRIAL_CONFIRMED_EXPIRED) // Sicherstellen, dass dieser Flag bei Kauf entfernt wird
        Log.d(TAG, "Setting KEY_PURCHASED_FLAG to true")
        editor.putBoolean(KEY_PURCHASED_FLAG, true)
        editor.apply()
        Log.i(TAG, "App marked as purchased. Trial data (including unencrypted end time and confirmed expired flag) cleared.")
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
            Log.d(TAG, "No core trial-related flags found. Initializing KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true.")
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
            Log.d(TAG, "Initialized KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true for a fresh state (unencrypted storage)." )
        } else {
            Log.d(TAG, "One or more core trial-related flags already exist. No initialization needed for KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME.")
        }
    }
}