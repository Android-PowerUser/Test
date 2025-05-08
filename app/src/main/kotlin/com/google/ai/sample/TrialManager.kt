package com.google.ai.sample

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.Charset
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TrialManager {

    private const val PREFS_NAME = "TrialPrefs"
    const val TRIAL_DURATION_MS = 30 * 60 * 1000L // 30 minutes in milliseconds

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_TRIAL_END_TIME_KEY = "TrialEndTimeEncryptionKeyAlias"
    private const val KEY_ENCRYPTED_TRIAL_UTC_END_TIME = "encryptedTrialUtcEndTime"
    private const val KEY_ENCRYPTION_IV = "encryptionIv"
    private const val KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME = "trialAwaitingFirstInternetTime"
    private const val KEY_PURCHASED_FLAG = "appPurchased"

    private const val TAG = "TrialManager"

    // AES/GCM/NoPadding is a good choice for symmetric encryption
    private const val ENCRYPTION_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENCRYPTION_BLOCK_SIZE = 12 // GCM IV size is typically 12 bytes

    enum class TrialState {
        NOT_YET_STARTED_AWAITING_INTERNET,
        ACTIVE_INTERNET_TIME_CONFIRMED,
        EXPIRED_INTERNET_TIME_CONFIRMED,
        PURCHASED,
        INTERNET_UNAVAILABLE_CANNOT_VERIFY // Used when current internet time is not available
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = getKeyStore()
        if (!keyStore.containsAlias(KEY_ALIAS_TRIAL_END_TIME_KEY)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val parameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS_TRIAL_END_TIME_KEY,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256) // AES-256
                    .build()
                keyGenerator.init(parameterSpec)
                return keyGenerator.generateKey()
            } else {
                // For pre-M, KeyStore offers limited capabilities. This is a simplified fallback.
                // In a real-world scenario for pre-M, you might use a less secure method or disable this feature.
                // For this example, we'll throw an error or handle it gracefully, as robust KeyStore encryption isn't available.
                throw SecurityException("KeyStore encryption for trial end time not supported on this API level.")
            }
        }
        return keyStore.getKey(KEY_ALIAS_TRIAL_END_TIME_KEY, null) as SecretKey
    }

    private fun saveEncryptedTrialUtcEndTime(context: Context, utcEndTimeMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Skipping KeyStore encryption for API < 23. Storing end time in plain text (less secure).")
            getSharedPreferences(context).edit().putLong(KEY_ENCRYPTED_TRIAL_UTC_END_TIME, utcEndTimeMs).apply()
            return
        }
        try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv // Get the IV, GCM will generate one if not specified
            val encryptedEndTime = cipher.doFinal(utcEndTimeMs.toString().toByteArray(Charset.defaultCharset()))

            val editor = getSharedPreferences(context).edit()
            editor.putString(KEY_ENCRYPTED_TRIAL_UTC_END_TIME, Base64.encodeToString(encryptedEndTime, Base64.DEFAULT))
            editor.putString(KEY_ENCRYPTION_IV, Base64.encodeToString(iv, Base64.DEFAULT))
            editor.apply()
            Log.d(TAG, "Encrypted and saved UTC end time.")
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting or saving trial end time", e)
            // Fallback: store unencrypted if Keystore fails, or handle error more strictly
            getSharedPreferences(context).edit().putLong(KEY_ENCRYPTED_TRIAL_UTC_END_TIME + "_unencrypted_fallback", utcEndTimeMs).apply()
        }
    }

    private fun getDecryptedTrialUtcEndTime(context: Context): Long? {
        val prefs = getSharedPreferences(context)
        val encryptedEndTimeString = prefs.getString(KEY_ENCRYPTED_TRIAL_UTC_END_TIME, null)
        val ivString = prefs.getString(KEY_ENCRYPTION_IV, null)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Skipping KeyStore decryption for API < 23. Reading plain text end time.")
            return if(prefs.contains(KEY_ENCRYPTED_TRIAL_UTC_END_TIME)) prefs.getLong(KEY_ENCRYPTED_TRIAL_UTC_END_TIME, 0L) else null
        }

        if (encryptedEndTimeString == null || ivString == null) {
            // Check for unencrypted fallback if main encrypted value is missing
            if (prefs.contains(KEY_ENCRYPTED_TRIAL_UTC_END_TIME + "_unencrypted_fallback")) {
                Log.w(TAG, "Using unencrypted fallback for end time.")
                return prefs.getLong(KEY_ENCRYPTED_TRIAL_UTC_END_TIME + "_unencrypted_fallback", 0L)
            }
            Log.d(TAG, "No encrypted end time or IV found.")
            return null
        }

        return try {
            val secretKey = getOrCreateSecretKey() // Or getKey if sure it exists
            val encryptedEndTime = Base64.decode(encryptedEndTimeString, Base64.DEFAULT)
            val iv = Base64.decode(ivString, Base64.DEFAULT)

            val cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv) // 128 is the GCM tag length in bits
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedEndTime)
            val decryptedString = String(decryptedBytes, Charset.defaultCharset())
            Log.d(TAG, "Decrypted UTC end time successfully.")
            decryptedString.toLongOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting trial end time", e)
            null
        }
    }

    fun startTrialIfNecessaryWithInternetTime(context: Context, currentUtcTimeMs: Long) {
        val prefs = getSharedPreferences(context)
        if (isPurchased(context)) {
            Log.d(TAG, "App is purchased, no trial needed.")
            return
        }
        if (getDecryptedTrialUtcEndTime(context) == null && prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)) {
            val utcEndTimeMs = currentUtcTimeMs + TRIAL_DURATION_MS
            saveEncryptedTrialUtcEndTime(context, utcEndTimeMs)
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, false).apply()
            Log.i(TAG, "Trial started with internet time. Ends at UTC: $utcEndTimeMs")
        } else {
            Log.d(TAG, "Trial already started or not awaiting first internet time.")
        }
    }

    private fun isTrialExpiredBasedOnInternetTime(context: Context, currentUtcTimeMs: Long): Boolean {
        val utcEndTimeMs = getDecryptedTrialUtcEndTime(context)
        return if (utcEndTimeMs != null) {
            currentUtcTimeMs >= utcEndTimeMs
        } else {
            false // If no end time is set, it's not expired (might be awaiting first internet time)
        }
    }

    fun getTrialState(context: Context, currentUtcTimeMs: Long?): TrialState {
        if (isPurchased(context)) {
            return TrialState.PURCHASED
        }

        val prefs = getSharedPreferences(context)
        val isAwaitingFirstInternetTime = prefs.getBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true)
        val decryptedUtcEndTime = getDecryptedTrialUtcEndTime(context)

        if (currentUtcTimeMs == null) {
            // If we don't have current internet time, we can't definitively say if it's active or expired
            // unless it was already marked as awaiting or an end time was never set.
            return if (decryptedUtcEndTime == null && isAwaitingFirstInternetTime) {
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            } else {
                TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY
            }
        }

        return when {
            decryptedUtcEndTime == null && isAwaitingFirstInternetTime -> TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            decryptedUtcEndTime == null && !isAwaitingFirstInternetTime -> {
                // This state should ideally not happen if logic is correct. It means we were not awaiting, but no end time was set.
                // Treat as if needs to start.
                Log.w(TAG, "Inconsistent state: Not awaiting internet time, but no end time found. Resetting to await.")
                prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET
            }
            decryptedUtcEndTime != null && currentUtcTimeMs < decryptedUtcEndTime -> TrialState.ACTIVE_INTERNET_TIME_CONFIRMED
            decryptedUtcEndTime != null && currentUtcTimeMs >= decryptedUtcEndTime -> TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
            else -> {
                Log.e(TAG, "Unhandled case in getTrialState")
                TrialState.NOT_YET_STARTED_AWAITING_INTERNET // Fallback, should be investigated
            }
        }
    }

    fun markAsPurchased(context: Context) {
        val editor = getSharedPreferences(context).edit()
        editor.remove(KEY_ENCRYPTED_TRIAL_UTC_END_TIME)
        editor.remove(KEY_ENCRYPTION_IV)
        editor.remove(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME)
        editor.putBoolean(KEY_PURCHASED_FLAG, true) // Add a clear purchased flag
        editor.apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = getKeyStore()
                if (keyStore.containsAlias(KEY_ALIAS_TRIAL_END_TIME_KEY)) {
                    keyStore.deleteEntry(KEY_ALIAS_TRIAL_END_TIME_KEY)
                    Log.d(TAG, "Trial encryption key deleted from KeyStore.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete trial encryption key from KeyStore", e)
            }
        }
        Log.i(TAG, "App marked as purchased. Trial data cleared.")
    }

    private fun isPurchased(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_PURCHASED_FLAG, false)
    }
    
    // Call this on app's first ever launch if needed to set the awaiting flag.
    // Or, rely on the default value of the SharedPreferences boolean.
    fun initializeTrialStateFlagsIfNecessary(context: Context) {
        val prefs = getSharedPreferences(context)
        if (!prefs.contains(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME) && !prefs.contains(KEY_PURCHASED_FLAG) && !prefs.contains(KEY_ENCRYPTED_TRIAL_UTC_END_TIME)) {
            prefs.edit().putBoolean(KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME, true).apply()
            Log.d(TAG, "Initialized KEY_TRIAL_AWAITING_FIRST_INTERNET_TIME to true for a fresh state.")
        }
    }
}

