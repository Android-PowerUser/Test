package io.github.Android-PowerUser.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class to map between app names and package names
 */
class AppNamePackageMapper(private val context: Context) {
    companion object {
        private const val TAG = "AppNamePackageMapper"
    }
    
    // Cache for app name to package name mappings
    private val appNameToPackageCache = ConcurrentHashMap<String, String>()
    
    // Cache for package name to app name mappings
    private val packageToAppNameCache = ConcurrentHashMap<String, String>()
    
    // Common app name variations
    private val appNameVariations = mapOf(
        "whatsapp" to listOf("whats app", "whats", "wa"),
        "facebook" to listOf("fb", "face book"),
        "instagram" to listOf("insta", "ig"),
        "youtube" to listOf("yt", "you tube"),
        "twitter" to listOf("x", "tweet"),
        "telegram" to listOf("tg"),
        "tiktok" to listOf("tik tok"),
        "snapchat" to listOf("snap"),
        "netflix" to listOf("nflx"),
        "spotify" to listOf("music"),
        "chrome" to listOf("google chrome", "browser"),
        "gmail" to listOf("google mail", "email", "mail"),
        "maps" to listOf("google maps", "navigation"),
        "calendar" to listOf("google calendar"),
        "photos" to listOf("google photos", "gallery"),
        "drive" to listOf("google drive"),
        "docs" to listOf("google docs", "documents"),
        "sheets" to listOf("google sheets", "spreadsheets"),
        "slides" to listOf("google slides", "presentations"),
        "keep" to listOf("google keep", "notes"),
        "amazon" to listOf("amazon shopping", "shop"),
        "uber" to listOf("uber driver"),
        "lyft" to listOf("ride"),
        "doordash" to listOf("food delivery"),
        "ubereats" to listOf("uber eats", "food"),
        "grubhub" to listOf("food delivery"),
        "instacart" to listOf("grocery"),
        "zoom" to listOf("zoom meeting"),
        "teams" to listOf("microsoft teams"),
        "skype" to listOf("microsoft skype"),
        "outlook" to listOf("microsoft outlook", "email"),
        "word" to listOf("microsoft word", "documents"),
        "excel" to listOf("microsoft excel", "spreadsheets"),
        "powerpoint" to listOf("microsoft powerpoint", "presentations"),
        "onedrive" to listOf("microsoft onedrive", "cloud"),
        "onenote" to listOf("microsoft onenote", "notes"),
        "linkedin" to listOf("linked in"),
        "pinterest" to listOf("pin"),
        "reddit" to listOf("reddit app"),
        "discord" to listOf("chat"),
        "slack" to listOf("work chat"),
        "twitch" to listOf("streaming"),
        "venmo" to listOf("payment"),
        "paypal" to listOf("payment"),
        "cashapp" to listOf("cash app", "payment"),
        "zelle" to listOf("payment"),
        "robinhood" to listOf("stocks"),
        "coinbase" to listOf("crypto"),
        "binance" to listOf("crypto"),
        "wechat" to listOf("we chat"),
        "line" to listOf("line messenger"),
        "viber" to listOf("viber messenger"),
        "signal" to listOf("signal messenger"),
        "threema" to listOf("threema messenger"),
        "settings" to listOf("system settings", "preferences")
    )
    
    // Manual mappings for common apps
    private val manualMappings = mapOf(
        "whatsapp" to "com.whatsapp",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "youtube" to "com.google.android.youtube",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "telegram" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "snapchat" to "com.snapchat.android",
        "netflix" to "com.netflix.mediaclient",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "calendar" to "com.google.android.calendar",
        "photos" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "docs" to "com.google.android.apps.docs.editors.docs",
        "sheets" to "com.google.android.apps.docs.editors.sheets",
        "slides" to "com.google.android.apps.docs.editors.slides",
        "keep" to "com.google.android.keep",
        "amazon" to "com.amazon.mShop.android.shopping",
        "uber" to "com.ubercab",
        "lyft" to "me.lyft.android",
        "doordash" to "com.dd.doordash",
        "ubereats" to "com.ubercab.eats",
        "grubhub" to "com.grubhub.android",
        "instacart" to "com.instacart.client",
        "zoom" to "us.zoom.videomeetings",
        "teams" to "com.microsoft.teams",
        "skype" to "com.skype.raider",
        "outlook" to "com.microsoft.office.outlook",
        "word" to "com.microsoft.office.word",
        "excel" to "com.microsoft.office.excel",
        "powerpoint" to "com.microsoft.office.powerpoint",
        "onedrive" to "com.microsoft.skydrive",
        "onenote" to "com.microsoft.office.onenote",
        "linkedin" to "com.linkedin.android",
        "pinterest" to "com.pinterest",
        "reddit" to "com.reddit.frontpage",
        "discord" to "com.discord",
        "slack" to "com.Slack",
        "twitch" to "tv.twitch.android.app",
        "venmo" to "com.venmo",
        "paypal" to "com.paypal.android.p2pmobile",
        "cashapp" to "com.squareup.cash",
        "zelle" to "com.zellepay.zelle",
        "robinhood" to "com.robinhood.android",
        "coinbase" to "com.coinbase.android",
        "binance" to "com.binance.dev",
        "wechat" to "com.tencent.mm",
        "line" to "jp.naver.line.android",
        "viber" to "com.viber.voip",
        "signal" to "org.thoughtcrime.securesms",
        "threema" to "ch.threema.app",
        "settings" to "com.android.settings"
    )
    
    /**
     * Initialize the cache with installed apps
     */
    fun initializeCache() {
        Log.d(TAG, "Initializing app name to package name cache")
        
        try {
            // Get the package manager
            val packageManager = context.packageManager
            
            // Create an intent to get all apps with launcher activities
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            // Query for all apps with launcher activities
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            // Add all apps to the cache
            for (resolveInfo in resolveInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
                
                // Add to caches
                appNameToPackageCache[appName] = packageName
                packageToAppNameCache[packageName] = resolveInfo.loadLabel(packageManager).toString()
                
                // Add manual mappings to the cache
                for ((key, value) in manualMappings) {
                    appNameToPackageCache[key] = value
                }
                
                // Add variations to the cache
                for ((appName, variations) in appNameVariations) {
                    val packageName = getPackageName(appName)
                    if (packageName != null) {
                        for (variation in variations) {
                            appNameToPackageCache[variation] = packageName
                        }
                    }
                }
            }
            
            Log.d(TAG, "Cache initialized with ${appNameToPackageCache.size} app names and ${packageToAppNameCache.size} package names")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing cache: ${e.message}")
        }
    }
    
    /**
     * Get the package name for an app name
     * 
     * @param appName The app name to look up
     * @return The package name, or null if not found
     */
    fun getPackageName(appName: String): String? {
        // Normalize the app name
        val normalizedAppName = appName.lowercase(Locale.getDefault())
            .trim()
            .replace(Regex("[^a-z0-9]"), "")
        
        // Check if the app name is already a package name
        if (normalizedAppName.contains(".")) {
            return normalizedAppName
        }
        
        // Check the cache first
        appNameToPackageCache[normalizedAppName]?.let {
            Log.d(TAG, "Found package name in cache for app name '$appName': $it")
            return it
        }
        
        // Check manual mappings
        manualMappings[normalizedAppName]?.let {
            Log.d(TAG, "Found package name in manual mappings for app name '$appName': $it")
            appNameToPackageCache[normalizedAppName] = it
            return it
        }
        
        // Try to find a match in installed apps
        try {
            // Get the package manager
            val packageManager = context.packageManager
            
            // Create an intent to get all apps with launcher activities
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            // Query for all apps with launcher activities
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            // Find the best match
            var bestMatch: ResolveInfo? = null
            var bestMatchScore = 0
            
            for (resolveInfo in resolveInfoList) {
                val currentAppName = resolveInfo.loadLabel(packageManager).toString()
                val normalizedCurrentAppName = currentAppName.lowercase(Locale.getDefault())
                    .trim()
                    .replace(Regex("[^a-z0-9]"), "")
                
                // Calculate match score
                val score = calculateMatchScore(normalizedAppName, normalizedCurrentAppName)
                
                if (score > bestMatchScore) {
                    bestMatchScore = score
                    bestMatch = resolveInfo
                }
            }
            
            // If we found a good match, return its package name
            if (bestMatchScore >= 70) { // 70% match threshold
                val packageName = bestMatch!!.activityInfo.packageName
                Log.d(TAG, "Found package name for app name '$appName': $packageName (match score: $bestMatchScore%)")
                
                // Add to cache
                appNameToPackageCache[normalizedAppName] = packageName
                packageToAppNameCache[packageName] = bestMatch.loadLabel(packageManager).toString()
                
                return packageName
            }
            
            Log.d(TAG, "No good match found for app name '$appName' (best match score: $bestMatchScore%)")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting package name for app name '$appName': ${e.message}")
            return null
        }
    }
    
    /**
     * Get the app name for a package name
     * 
     * @param packageName The package name to look up
     * @return The app name, or the package name if not found
     */
    fun getAppName(packageName: String): String {
        // Check the cache first
        packageToAppNameCache[packageName]?.let {
            Log.d(TAG, "Found app name in cache for package name '$packageName': $it")
            return it
        }
        
        // Try to get the app name from the package manager
        try {
            // Get the package manager
            val packageManager = context.packageManager
            
            // Try to get the app info
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(applicationInfo).toString()
            
            Log.d(TAG, "Found app name for package name '$packageName': $appName")
            
            // Add to cache
            packageToAppNameCache[packageName] = appName
            appNameToPackageCache[appName.lowercase(Locale.getDefault())] = packageName
            
            return appName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package name '$packageName': ${e.message}")
            return packageName
        }
    }
    
    /**
     * Calculate a match score between two app names
     * 
     * @param query The query app name
     * @param target The target app name
     * @return A score from 0 to 100 indicating how well the names match
     */
    private fun calculateMatchScore(query: String, target: String): Int {
        // Exact match
        if (query == target) {
            return 100
        }
        
        // Target contains query
        if (target.contains(query)) {
            return 90
        }
        
        // Query contains target
        if (query.contains(target)) {
            return 80
        }
        
        // Calculate Levenshtein distance
        val distance = levenshteinDistance(query, target)
        val maxLength = maxOf(query.length, target.length)
        
        // Convert distance to similarity percentage
        val similarity = ((maxLength - distance) / maxLength.toFloat()) * 100
        
        return similarity.toInt()
    }
    
    /**
     * Calculate the Levenshtein distance between two strings
     * 
     * @param s1 The first string
     * @param s2 The second string
     * @return The Levenshtein distance
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        
        // Create a matrix of size (m+1) x (n+1)
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        // Initialize the matrix
        for (i in 0..m) {
            dp[i][0] = i
        }
        
        for (j in 0..n) {
            dp[0][j] = j
        }
        
        // Fill the matrix
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1, // deletion
                    dp[i][j - 1] + 1, // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[m][n]
    }
}
