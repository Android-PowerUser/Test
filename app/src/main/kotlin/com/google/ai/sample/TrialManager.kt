package com.google.ai.sample

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.*
import java.security.cert.CertificateException
import javax.crypto.KeyGenerator

object TrialManager {

    private const val PREFS_NAME = "TrialPrefs"
    private const val KEY_TRIAL_START_TIME = "trialStartTime"
    private const val KEY_TRIAL_EXPIRED_MANUALLY = "trialExpiredManually"
    const val TRIAL_DURATION_MS = 30 * 60 * 1000L // 30 minutes in milliseconds

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_TRIAL_EXPIRED = "TrialExpiredKeyAlias"

    // --- SharedPreferences Methods ---

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveTrialStartTime(context: Context, startTime: Long) {
        val editor = getSharedPreferences(context).edit()
        editor.putLong(KEY_TRIAL_START_TIME, startTime)
        editor.apply()
    }

    fun getTrialStartTime(context: Context): Long {
        return getSharedPreferences(context).getLong(KEY_TRIAL_START_TIME, 0L)
    }

    fun isTrialStarted(context: Context): Boolean {
        return getTrialStartTime(context) > 0L
    }

    private fun setTrialExpiredInPrefs(context: Context, expired: Boolean) {
        val editor = getSharedPreferences(context).edit()
        editor.putBoolean(KEY_TRIAL_EXPIRED_MANUALLY, expired)
        editor.apply()
    }

    private fun isTrialExpiredInPrefs(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_TRIAL_EXPIRED_MANUALLY, false)
    }

    // --- KeyStore Methods ---

    private fun getKeyStore(): KeyStore? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore
        } catch (e: Exception) { // Catch generic exception for brevity in this example
            // Log.e("TrialManager", "Failed to load KeyStore", e) // Proper logging
            null
        }
    }

    fun setTrialExpiredInKeyStore(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = getKeyStore() ?: run {
                    setTrialExpiredInPrefs(context, true) // Fallback if keystore is null
                    return
                }

                if (!keyStore.containsAlias(KEY_ALIAS_TRIAL_EXPIRED)) {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val parameterSpec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS_TRIAL_EXPIRED,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT // Dummy purposes
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM) // Dummy block mode
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE) // Dummy padding
                        .setUserAuthenticationRequired(false)
                        .build()
                    keyGenerator.init(parameterSpec)
                    keyGenerator.generateKey() // Presence of the key indicates trial expired
                }
            } catch (e: Exception) {
                // Log.e("TrialManager", "Failed to set trial expired in KeyStore", e)
                // Fallback: if KeyStore fails, rely on SharedPreferences
            }
        }
        // Always set in prefs as a reliable indicator, especially for pre-M or KeyStore errors
        setTrialExpiredInPrefs(context, true)
    }

    fun isTrialExpired(context: Context): Boolean {
        // Check KeyStore first for persistence across reinstalls (API M+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = getKeyStore()
                if (keyStore?.containsAlias(KEY_ALIAS_TRIAL_EXPIRED) == true) {
                    return true
                }
            } catch (e: Exception) {
                // Log.e("TrialManager", "Failed to check KeyStore for trial expiry", e)
                // Fallback to SharedPreferences if KeyStore check fails
            }
        }
        // Fallback to SharedPreferences (for pre-M or if KeyStore check failed/key not found)
        if (isTrialExpiredInPrefs(context)) {
            return true
        }

        // If not marked as expired, check time
        val startTime = getTrialStartTime(context)
        if (startTime == 0L) {
            return false // Trial hasn't started
        }
        val elapsedTime = System.currentTimeMillis() - startTime
        if (elapsedTime >= TRIAL_DURATION_MS) {
            setTrialExpiredInKeyStore(context) // Mark as expired now
            return true
        }
        return false
    }

    fun startTrialIfNecessary(context: Context) {
        if (!isTrialStarted(context) && !isTrialExpired(context)) {
            saveTrialStartTime(context, System.currentTimeMillis())
        }
    }

    fun markAsPurchased(context: Context) {
        // 1. Clear SharedPreferences related to trial
        val editor = getSharedPreferences(context).edit()
        editor.remove(KEY_TRIAL_START_TIME)
        editor.remove(KEY_TRIAL_EXPIRED_MANUALLY)
        editor.apply()

        // 2. Remove KeyStore entry (API M+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = getKeyStore()
                if (keyStore?.containsAlias(KEY_ALIAS_TRIAL_EXPIRED) == true) {
                    keyStore.deleteEntry(KEY_ALIAS_TRIAL_EXPIRED)
                }
            } catch (e: Exception) {
                // Log.e("TrialManager", "Failed to delete KeyStore entry on purchase", e)
            }
        }
    }
}

