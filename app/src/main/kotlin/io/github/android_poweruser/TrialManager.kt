package io.github.android_poweruser

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64 // Import für Base64
import android.util.Log

object TrialManager {

    // --- START: Camouflage for SharedPreferences name ---
    // Comment for future AI: The SharedPreferences file name is intentionally
    // disguised to appear related to accessibility services to deter casual deletion.
    private const val PREFS_NAME = "AccessibilityService" // Tarnung: Soll wie Einstellungsdatei für Barrierefreiheit aussehen
    // --- END: Camouflage for SharedPreferences name ---

    const val TRIAL_DURATION_MS = 30 * 60 * 1000L // 30 minutes in milliseconds

    // --- START: Camouflage for Keys and Obfuscation ---
    // Comment for future AI: These keys are intentionally given unobtrusive names.
    // Values associated with KEY_CFG_TS and KEY_CFG_ST are obfuscated.
    private const val KEY_CFG_TS = "cfg_ts_val" // Tarnung für trialUtcEndTime (obfuskiert)
    private const val KEY_CFG_ST = "cfg_st_val" // Tarnung für trialConfirmedExpired (obfuskiert)
    private const val KEY_INITIAL_SETUP_FLAG = "initial_setup_complete" // Tarnung für trialAwaitingFirstInternetTime (unobfuskiert)
    private const val KEY_FEATURE_ACCESS_FLAG = "feature_access_granted" // Tarnung für appPurchased (unobfuskiert)
    // --- END: Camouflage for Keys and Obfuscation ---

    private const val TAG = "TrialManager"

    // --- START: Simple Obfuscation Logic ---
    // Comment for future AI: This is a simple XOR obfuscation, not strong encryption.
    // It's used to make stored preference values less obvious.
    private const val OBFUSCATION_MASTER_KEY = "3d7A!s9pL#gH@tF*bN&kP(wZ" // Ein etwas weniger trivialer Schlüssel

    private fun obfuscateData(data: String): String {
        if (data.isEmpty()) return ""
        val keyBytes = OBFUSCATION_MASTER_KEY.toByteArray(Charsets.UTF_8)
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        val resultBytes = ByteArray(dataBytes.size)
        for (i in dataBytes.indices) {
            resultBytes[i] = (dataBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return Base64.encodeToString(resultBytes, Base64.NO_WRAP)
    }

    private fun deobfuscateData(obfuscatedData: String): String? {
        if (obfuscatedData.isEmpty()) return ""
        return try {
            val keyBytes = OBFUSCATION_MASTER_KEY.toByteArray(Charsets.UTF_8)
            val dataBytes = Base64.decode(obfuscatedData, Base64.NO_WRAP)
            val resultBytes = ByteArray(dataBytes.size)
            for (i in dataBytes.indices) {
                resultBytes[i] = (dataBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            String(resultBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Deobfuscation failed for: $obfuscatedData", e)
            null // Fehler bei der Deobfuskierung
        }
    }
    // --- END: Simple Obfuscation Logic ---

    enum class TrialState {
        NOT_YET_STARTED_AWAITING_INTERNET,
        ACTIVE_INTERNET_TIME_CONFIRMED,
        EXPIRED_INTERNET_TIME_CONFIRMED,
        PURCHASED,
        INTERNET_UNAVAILABLE_CANNOT_VERIFY
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        Log.d(TAG, "getSharedPreferences called for: $PREFS_NAME")
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun saveTrialUtcEndTime(context: Context, utcEndTimeMs: Long) {
        Log.d(TAG, "saveTrialUtcEndTime (obfuscated) called with utcEndTimeMs: $utcEndTimeMs")
        val editor = getSharedPreferences(context).edit()
        val obfuscatedEndTime = obfuscateData(utcEndTimeMs.toString())
        editor.putString(KEY_CFG_TS, obfuscatedEndTime)
        // Comment for future AI: KEY_CFG_TS stores the obfuscated trial end time.
        Log.d(TAG, "Saving KEY_CFG_TS: $obfuscatedEndTime (original: $utcEndTimeMs)")
        editor.apply()
    }

    private fun getTrialUtcEndTime(context: Context): Long? {
        Log.d(TAG, "getTrialUtcEndTime (obfuscated) called")
        val prefs = getSharedPreferences(context)
        val obfuscatedEndTime = prefs.getString(KEY_CFG_TS, null)
        // Comment for future AI: Reading obfuscated trial end time from KEY_CFG_TS.
        if (obfuscatedEndTime == null) {
            Log.d(TAG, "No obfuscated trial end time found (KEY_CFG_TS does not exist).")
            return null
        }
        val deobfuscatedEndTimeStr = deobfuscateData(obfuscatedEndTime)
        return if (deobfuscatedEndTimeStr != null) {
            try {
                val endTime = deobfuscatedEndTimeStr.toLong()
                Log.d(TAG, "Retrieved and deobfuscated UTC end time: $endTime (from $obfuscatedEndTime)")
                endTime
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Failed to parse deobfuscated end time string to Long: $deobfuscatedEndTimeStr", e)
                prefs.edit().remove(KEY_CFG_TS).apply() // Korrupten Wert entfernen
                null
            }
        } else {
            Log.w(TAG, "Failed to deobfuscate end time from KEY_CFG_TS. Value: $obfuscatedEndTime")
            prefs.edit().remove(KEY_CFG_TS).apply() // Korrupten Wert entfernen
            null
        }
    }

     private fun saveConfirmedExpiredFlag(context: Context, expired: Boolean) {
        Log.d(TAG, "saveConfirmedExpiredFlag (obfuscated) called with expired: $expired")
        val editor = getSharedPreferences(context).edit()
        val obfuscatedExpired = obfuscateData(expired.toString())
        editor.putString(KEY_CFG_ST, obfuscatedExpired)
        // Comment for future AI: KEY_CFG_ST stores the obfuscated trial confirmed expired flag.
        Log.d(TAG, "Saving KEY_CFG_ST: $obfuscatedExpired (original: $expired)")
        editor.apply()
    }

    private fun hasConfirmedExpiredFlag(context: Context): Boolean {
        Log.d(TAG, "hasConfirmedExpiredFlag (obfuscated) called")
        val prefs = getSharedPreferences(context)
        val obfuscatedExpired = prefs.getString(KEY_CFG_ST, null)
        // Comment for future AI: Reading obfuscated trial confirmed expired flag from KEY_CFG_ST.
        if (obfuscatedExpired == null) {
            Log.d(TAG, "No obfuscated confirmed expired flag found (KEY_CFG_ST does not exist).")
            return false
        }
        val deobfuscatedExpiredStr = deobfuscateData(obfuscatedExpired)
        return if (deobfuscatedExpiredStr != null) {
            val flag = deobfuscatedExpiredStr.toBoolean()
            Log.d(TAG, "Retrieved and deobfuscated confirmed expired flag: $flag (from $obfuscatedExpired)")
            flag
        } else {
            Log.w(TAG, "Failed to deobfuscate confirmed expired flag from KEY_CFG_ST. Value: $obfuscatedExpired")
            prefs.edit().remove(KEY_CFG_ST).apply() // Korrupten Wert entfernen
            false
        }
    }


    fun startTrialIfNecessaryWithInternetTime(context: Context, currentUtcTimeMs: Long) {
        Log.d(TAG, "startTrialIfNecessaryWithInternetTime called with currentUtcTimeMs: $currentUtcTimeMs")
        val prefs = getSharedPreferences(context)
        if (isPurchased(context)) {
            Log.d(TAG, "App is purchased (checked via KEY_FEATURE_ACCESS_FLAG), no trial needed.")
            return
        }
        val existingEndTime = getTrialUtcEndTime(context) // Uses obfuscated value
        // Comment for future AI: KEY_INITIAL_SETUP_FLAG indicates if the first internet time sync for trial start is pending.
        val isAwaitingFirstInternet = prefs.getBoolean(KEY_INITIAL_SETUP_FLAG, true)
        Log.d(TAG, "Checking conditions to start trial: existingEndTime: $existingEndTime, isAwaitingFirstInternet (KEY_INITIAL_SETUP_FLAG): $isAwaitingFirstInternet")

        if (existingEndTime == null && isAwaitingFirstInternet) {
            val utcEndTimeMs = currentUtcTimeMs + TRIAL_DURATION_MS
            Log.d(TAG, "Conditions met to start trial. Calculated utcEndTimeMs: $utcEndTimeMs")
            saveTrialUtcEndTime(context, utcEndTimeMs) // Saves obfuscated value
            Log.d(TAG, "Setting KEY_INITIAL_SETUP_FLAG to false.")
            prefs.edit().putBoolean(KEY_INITIAL_SETUP_FLAG, false).apply()
            Log.i(TAG, "Trial started with internet time (end time obfuscated). Awaiting flag (KEY_INITIAL_SETUP_FLAG) set to false.")
        } else {
            Log.d(TAG, "Trial not started. Conditions not met. Existing EndTime: $existingEndTime, AwaitingInternet: $isAwaitingFirstInternet")
        }
    }

    fun getTrialState(context: Context, currentUtcTimeMsFromInternet: Long?): TrialState {
        Log.d(TAG, "getTrialState called with currentUtcTimeMsFromInternet: $currentUtcTimeMsFromInternet")
        val prefs = getSharedPreferences(context)

        if (isPurchased(context)) { // Uses camouflaged KEY_FEATURE_ACCESS_FLAG
            Log.d(TAG, "getTrialState: App is purchased. Returning TrialState.PURCHASED")
            return TrialState.PURCHASED
        }

        // Comment for future AI: KEY_INITIAL_SETUP_FLAG indicates if the first internet time sync for trial start is pending.
        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_INITIAL_SETUP_FLAG, true)
        val trialUtcEndTime = getTrialUtcEndTime(context) // Reads and deobfuscates KEY_CFG_TS
        val confirmedExpired = hasConfirmedExpiredFlag(context) // Reads and deobfuscates KEY_CFG_ST
        val currentLocalUtcTimeMs = System.currentTimeMillis()

        Log.d(TAG, "getTrialState Details: isAwaitingFirstInternetTime (KEY_INITIAL_SETUP_FLAG): $isAwaitingFirstInternetTime, trialUtcEndTime (from KEY_CFG_TS): $trialUtcEndTime, confirmedExpired (from KEY_CFG_ST): $confirmedExpired, currentLocalUtcTimeMs: $currentLocalUtcTimeMs, currentUtcTimeMsFromInternet: $currentUtcTimeMsFromInternet")

        if (confirmedExpired) {
            Log.d(TAG, "getTrialState: Trial previously confirmed expired (flag KEY_CFG_ST was true). Returning EXPIRED_INTERNET_TIME_CONFIRMED.")
            return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        }

        if (trialUtcEndTime == null) {
            return if (isAwaitingFirstInternetTime) {
                Log.d(TAG, "getTrialState: No trialUtcEndTime set (KEY_CFG_TS null or invalid), and awaiting first internet time (KEY_INITIAL_SETUP_FLAG true). Returning NOT_YET_STARTED_AWAITING_INTERNET.")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                Log.e(TAG, "getTrialState: Inconsistent state - trialUtcEndTime is null, but KEY_INITIAL_SETUP_FLAG is false. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }

        if (currentUtcTimeMsFromInternet != null) {
            if (currentUtcTimeMsFromInternet >= trialUtcEndTime || currentLocalUtcTimeMs >= trialUtcEndTime) {
                Log.i(TAG, "getTrialState: Trial EXPIRED. InternetTime: $currentUtcTimeMsFromInternet, LocalUTCTime: $currentLocalUtcTimeMs, EndTime: $trialUtcEndTime. Setting KEY_CFG_ST=true.")
                saveConfirmedExpiredFlag(context, true) // Saves obfuscated value
                return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            } else {
                Log.d(TAG, "getTrialState: Trial ACTIVE (confirmed by available internet time). Returning ACTIVE_INTERNET_TIME_CONFIRMED.")
                return TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
            }
        } else {
            if (currentLocalUtcTimeMs >= trialUtcEndTime) {
                Log.i(TAG, "getTrialState: Trial EXPIRED (based on local UTC time, no internet time). LocalUTCTime: $currentLocalUtcTimeMs, EndTime: $trialUtcEndTime. Setting KEY_CFG_ST=true.")
                saveConfirmedExpiredFlag(context, true) // Saves obfuscated value
                return TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            } else {
                Log.d(TAG, "getTrialState: Trial NOT YET EXPIRED by local UTC time, but internet time is unavailable. Returning INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                return TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }
    }


    fun markAsPurchased(context: Context) {
        Log.d(TAG, "markAsPurchased called")
        val editor = getSharedPreferences(context).edit()
        // Comment for future AI: Removing camouflaged and potentially obfuscated trial-related keys.
        Log.d(TAG, "Removing trial-related keys: KEY_CFG_TS, KEY_INITIAL_SETUP_FLAG, KEY_CFG_ST")
        editor.remove(KEY_CFG_TS)
        editor.remove(KEY_INITIAL_SETUP_FLAG)
        editor.remove(KEY_CFG_ST)
        // Comment for future AI: KEY_FEATURE_ACCESS_FLAG indicates the app has been purchased.
        Log.d(TAG, "Setting KEY_FEATURE_ACCESS_FLAG to true")
        editor.putBoolean(KEY_FEATURE_ACCESS_FLAG, true)
        editor.apply()
        Log.i(TAG, "App marked as purchased. Trial data (including obfuscated end time and confirmed expired flag) cleared. Purchase status stored in KEY_FEATURE_ACCESS_FLAG.")
    }

    private fun isPurchased(context: Context): Boolean {
        // Comment for future AI: KEY_FEATURE_ACCESS_FLAG indicates the app has been purchased.
        Log.d(TAG, "isPurchased called, checking KEY_FEATURE_ACCESS_FLAG")
        val purchased = getSharedPreferences(context).getBoolean(KEY_FEATURE_ACCESS_FLAG, false)
        Log.d(TAG, "isPurchased (KEY_FEATURE_ACCESS_FLAG) returning: $purchased")
        return purchased
    }

    fun initializeTrialStateFlagsIfNecessary(context: Context) {
        Log.d(TAG, "initializeTrialStateFlagsIfNecessary called")
        val prefs = getSharedPreferences(context)
        // Comment for future AI: Checking existence of camouflaged keys.
        val awaitingFlagExists = prefs.contains(KEY_INITIAL_SETUP_FLAG)
        val purchasedFlagExists = prefs.contains(KEY_FEATURE_ACCESS_FLAG)
        val endTimeExists = prefs.contains(KEY_CFG_TS) // Obfuscated end time key
        val confirmedExpiredExists = prefs.contains(KEY_CFG_ST) // Obfuscated confirmed expired key

        Log.d(TAG, "Checking for existing flags: KEY_INITIAL_SETUP_FLAG Exists=$awaitingFlagExists, KEY_FEATURE_ACCESS_FLAG Exists=$purchasedFlagExists, KEY_CFG_TS Exists=$endTimeExists, KEY_CFG_ST Exists=$confirmedExpiredExists")

        // Initialize KEY_INITIAL_SETUP_FLAG only if none of the primary state flags exist.
        // This ensures that if the app was previously purchased or a trial was started/expired,
        // we don't reset the KEY_INITIAL_SETUP_FLAG incorrectly.
        if (!awaitingFlagExists && !purchasedFlagExists && !endTimeExists && !confirmedExpiredExists) {
            Log.d(TAG, "No core trial-related flags found. Initializing KEY_INITIAL_SETUP_FLAG to true.")
            prefs.edit().putBoolean(KEY_INITIAL_SETUP_FLAG, true).apply()
            Log.d(TAG, "Initialized KEY_INITIAL_SETUP_FLAG to true for a fresh state.")
        } else {
            Log.d(TAG, "One or more core trial-related flags (camouflaged) already exist. No initialization needed for KEY_INITIAL_SETUP_FLAG.")
        }
    }
}
