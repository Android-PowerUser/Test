package com.google.ai.sample

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.security.auth.x500.X500Principal

object KeystoreHelper {
    private const val TAG = "KeystoreHelper"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "TRIAL_END_TIMESTAMP_ALIAS_"
    // Using RSA as it's widely supported; EC could also be an option.
    // The actual cryptographic operations are not used, only the key entry's existence.
    private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_RSA

    private fun getKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null) // Load the keystore
        return keyStore
    }

    /**
     * Stores a new timestamp alias in the Keystore.
     * Deletes any pre-existing timestamp aliases first to ensure only one is active.
     * The context is required for KeyPairGeneratorSpec on API levels < 23.
     */
    fun storeTimestampAlias(context: Context, utcEndTimeMs: Long) {
        Log.d(TAG, "Attempting to store timestamp alias for: $utcEndTimeMs")
        deleteAllTimestampAliases() // Ensure only one trial timestamp alias exists

        val alias = "$ALIAS_PREFIX$utcEndTimeMs"
        try {
            val keyStore = getKeyStore()
            // Double check if alias somehow still exists (should not due to deleteAll)
            if (keyStore.containsAlias(alias)) {
                Log.w(TAG, "Alias $alias unexpectedly found after deleteAll. Assuming it's current.")
                return
            }

            val kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY // Minimal purpose
                )
                // .setCertificateSubject(X500Principal("CN=$alias")) // For KeyPairGeneratorSpec compatibility
                // .setCertificateSerialNumber(BigInteger.ONE) // For KeyPairGeneratorSpec compatibility
                // .setCertificateNotBefore(Date()) // For KeyPairGeneratorSpec compatibility
                // .setCertificateNotAfter(Date(System.currentTimeMillis() + 25L * 365 * 24 * 60 * 60 * 1000)) // 25 years validity
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512) // Required for signing
                // No user authentication needed for this key
                .setUserAuthenticationRequired(false)
                .build()
                kpg.initialize(spec)
            } else {
                // Fallback for API < 23
                val start = Calendar.getInstance()
                val end = Calendar.getInstance()
                end.add(Calendar.YEAR, 25) // Key valid for 25 years

                @Suppress("DEPRECATION")
                val spec = KeyPairGeneratorSpec.Builder(context)
                    .setAlias(alias)
                    .setSubject(X500Principal("CN=$alias, O=Android Authority"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .build()
                kpg.initialize(spec)
            }
            kpg.generateKeyPair() // This creates the entry in Keystore
            Log.i(TAG, "Successfully stored alias $alias in Keystore.")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing alias $alias in Keystore", e)
            // This is a critical failure for the trial mechanism.
        }
    }

    /**
     * Retrieves a timestamp by finding an alias with the specific prefix in the Keystore.
     * Assumes only one such alias should exist.
     */
    fun getStoredTimestampFromAlias(): Long? {
        Log.d(TAG, "Attempting to retrieve stored timestamp from Keystore alias.")
        try {
            val keyStore = getKeyStore()
            val aliases = keyStore.aliases().asSequence().toList()
            Log.d(TAG, "Available aliases in Keystore: $aliases")

            var foundTimestamp: Long? = null
            var aliasUsed: String? = null

            for (currentAlias in aliases) {
                if (currentAlias.startsWith(ALIAS_PREFIX)) {
                    Log.d(TAG, "Found matching alias: $currentAlias")
                    try {
                        val timestampStr = currentAlias.substring(ALIAS_PREFIX.length)
                        foundTimestamp = timestampStr.toLong()
                        aliasUsed = currentAlias
                        // Assuming deleteAllTimestampAliases works, there should be only one.
                        // If multiple were found, this would take the last one iterated unless we break.
                        // Let's break on first found, relying on deleteAll logic.
                        break
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "Failed to parse timestamp from alias: $currentAlias. Deleting malformed alias.", e)
                        // Clean up malformed alias to prevent future issues
                        try { keyStore.deleteEntry(currentAlias) } catch (delEx: Exception) { Log.e(TAG, "Failed to delete malformed alias $currentAlias", delEx)}
                    }
                }
            }

            if (foundTimestamp != null) {
                Log.i(TAG, "Retrieved timestamp $foundTimestamp from Keystore alias $aliasUsed.")
            } else {
                Log.d(TAG, "No alias matching prefix $ALIAS_PREFIX found in Keystore.")
            }
            return foundTimestamp
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving timestamp from Keystore aliases", e)
            return null
        }
    }

    /**
     * Deletes all Keystore aliases that match the trial timestamp prefix.
     */
    fun deleteAllTimestampAliases() {
        Log.d(TAG, "Attempting to delete all trial timestamp aliases from Keystore.")
        var deletedCount = 0
        try {
            val keyStore = getKeyStore()
            // Iterate over a copy of aliases to avoid ConcurrentModificationException if deletion modifies the underlying list
            val aliasesToDelete = keyStore.aliases().asSequence().filter { it.startsWith(ALIAS_PREFIX) }.toList()

            for (alias in aliasesToDelete) {
                try {
                    keyStore.deleteEntry(alias)
                    Log.i(TAG, "Deleted Keystore alias: $alias")
                    deletedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting Keystore alias: $alias", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing Keystore to delete aliases", e)
        }
        Log.d(TAG, "deleteAllTimestampAliases: Deleted $deletedCount aliases.")
    }
}