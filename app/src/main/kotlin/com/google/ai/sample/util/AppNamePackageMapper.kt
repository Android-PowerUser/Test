package com.google.ai.sample.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class to map between app names and package names
 */
class AppNamePackageMapper(private val context: Context) {
    companion object {
        private const val TAG = "AppNamePackageMapper"
        
        // Common variations of app names
        private val APP_NAME_VARIATIONS = mapOf(
            "whatsapp" to listOf("whats app", "whats", "whatsup", "wapp"),
            "facebook" to listOf("fb", "face book", "facebook app"),
            "instagram" to listOf("insta", "ig", "instagram app"),
            "youtube" to listOf("yt", "you tube", "youtube app"),
            "gmail" to listOf("google mail", "email", "mail"),
            "chrome" to listOf("google chrome", "browser", "chrome browser"),
            "maps" to listOf("google maps", "map", "navigation"),
            "netflix" to listOf("netflix app", "movies", "netflix movies"),
            "spotify" to listOf("spotify music", "music", "spotify app"),
            "telegram" to listOf("telegram app", "telegram messenger")
        )
        
        // Manual mappings for popular apps
        private val MANUAL_MAPPINGS = mapOf(
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "netflix" to "com.netflix.mediaclient",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "snapchat" to "com.snapchat.android",
            "amazon" to "com.amazon.mShop.android.shopping",
            "pinterest" to "com.pinterest",
            "uber" to "com.ubercab",
            "linkedin" to "com.linkedin.android",
            "reddit" to "com.reddit.frontpage",
            "twitch" to "tv.twitch.android.app",
            "discord" to "com.discord",
            "skype" to "com.skype.raider",
            "zoom" to "us.zoom.videomeetings",
            "outlook" to "com.microsoft.office.outlook",
            "onedrive" to "com.microsoft.skydrive",
            "dropbox" to "com.dropbox.android",
            "google" to "com.google.android.googlequicksearchbox",
            "play store" to "com.android.vending",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "gallery" to "com.android.gallery3d",
            "photos" to "com.google.android.apps.photos",
            "calculator" to "com.android.calculator2",
            "clock" to "com.android.deskclock",
            "calendar" to "com.android.calendar",
            "contacts" to "com.android.contacts",
            "phone" to "com.android.dialer",
            "messages" to "com.android.messaging"
        )
    }
    
    // Cache for app name to package name mappings
    private val appNameToPackageCache = ConcurrentHashMap<String, String>()
    
    // Cache for package name to app name mappings
    private val packageToAppNameCache = ConcurrentHashMap<String, String>()
    
    // Flag to track if the cache has been initialized
    private var cacheInitialized = false
    
    /**
     * Initialize the cache with installed apps
     */
    fun initializeCache() {
        if (cacheInitialized) {
            return
        }
        
        try {
            Log.d(TAG, "Initializing app name to package name cache")
            
            // Add manual mappings to cache
            for ((appName, packageName) in MANUAL_MAPPINGS) {
                appNameToPackageCache[appName.lowercase()] = packageName
                packageToAppNameCache[packageName] = appName
            }
            
            // Get all apps with launcher intent
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = context.packageManager.queryIntentActivities(intent, 0)
            
            for (resolveInfo in resolveInfoList) {
                try {
                    val packageName = resolveInfo.activityInfo.packageName
                    val appName = resolveInfo.loadLabel(context.packageManager).toString().lowercase()
                    
                    // Add to cache if not already present
                    if (!appNameToPackageCache.containsKey(appName)) {
                        appNameToPackageCache[appName] = packageName
                    }
                    
                    // Always update package to app name mapping
                    packageToAppNameCache[packageName] = resolveInfo.loadLabel(context.packageManager).toString()
                    
                    // Add variations for known apps
                    for ((baseAppName, variations) in APP_NAME_VARIATIONS) {
                        if (appName.contains(baseAppName)) {
                            for (variation in variations) {
                                if (!appNameToPackageCache.containsKey(variation)) {
                                    appNameToPackageCache[variation] = packageName
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing app: ${e.message}")
                }
            }
            
            // Try to get TV apps as well
            try {
                val tvIntent = Intent(Intent.ACTION_MAIN)
                tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                
                val tvResolveInfoList = context.packageManager.queryIntentActivities(tvIntent, 0)
                
                for (resolveInfo in tvResolveInfoList) {
                    try {
                        val packageName = resolveInfo.activityInfo.packageName
                        val appName = resolveInfo.loadLabel(context.packageManager).toString().lowercase()
                        
                        // Add to cache if not already present
                        if (!appNameToPackageCache.containsKey(appName)) {
                            appNameToPackageCache[appName] = packageName
                        }
                        
                        // Always update package to app name mapping
                        packageToAppNameCache[packageName] = resolveInfo.loadLabel(context.packageManager).toString()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing TV app: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying TV apps: ${e.message}")
            }
            
            Log.d(TAG, "Cache initialized with ${appNameToPackageCache.size} app names and ${packageToAppNameCache.size} package names")
            cacheInitialized = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing cache: ${e.message}")
        }
    }
    
    /**
     * Get package name for an app name
     * @param appName The name of the app
     * @return The package name, or null if not found
     */
    fun getPackageName(appName: String): String? {
        if (!cacheInitialized) {
            initializeCache()
        }
        
        // Normalize input
        val normalizedAppName = appName.trim().lowercase()
        
        // Check if it's already a package name
        if (normalizedAppName.contains(".") && isPackageName(normalizedAppName)) {
            return normalizedAppName
        }
        
        // Check cache for exact match
        appNameToPackageCache[normalizedAppName]?.let {
            Log.d(TAG, "Found exact match in cache for '$appName': $it")
            return it
        }
        
        // Check for partial matches
        for ((cachedAppName, packageName) in appNameToPackageCache) {
            if (cachedAppName.contains(normalizedAppName) || normalizedAppName.contains(cachedAppName)) {
                Log.d(TAG, "Found partial match in cache for '$appName': $packageName (matched with '$cachedAppName')")
                return packageName
            }
        }
        
        // Check manual mappings for variations
        for ((baseAppName, variations) in APP_NAME_VARIATIONS) {
            if (variations.any { it == normalizedAppName || it.contains(normalizedAppName) || normalizedAppName.contains(it) }) {
                MANUAL_MAPPINGS[baseAppName]?.let {
                    Log.d(TAG, "Found match through variations for '$appName': $it")
                    return it
                }
            }
        }
        
        // Try to find by querying all installed apps (last resort, more expensive)
        try {
            val packageManager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            for (resolveInfo in resolveInfoList) {
                val label = resolveInfo.loadLabel(packageManager).toString().lowercase()
                if (label == normalizedAppName || label.contains(normalizedAppName) || normalizedAppName.contains(label)) {
                    val foundPackageName = resolveInfo.activityInfo.packageName
                    
                    // Add to cache for future lookups
                    appNameToPackageCache[normalizedAppName] = foundPackageName
                    packageToAppNameCache[foundPackageName] = resolveInfo.loadLabel(packageManager).toString()
                    
                    Log.d(TAG, "Found match by querying installed apps for '$appName': $foundPackageName")
                    return foundPackageName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching installed apps: ${e.message}")
        }
        
        Log.d(TAG, "No package name found for app name: $appName")
        return null
    }
    
    /**
     * Get app name for a package name
     * @param packageName The package name
     * @return The app name, or the package name if not found
     */
    fun getAppName(packageName: String): String {
        if (!cacheInitialized) {
            initializeCache()
        }
        
        // Check cache
        packageToAppNameCache[packageName]?.let {
            return it
        }
        
        // Try to get from package manager
        try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(applicationInfo).toString()
            
            // Add to cache for future lookups
            packageToAppNameCache[packageName] = appName
            appNameToPackageCache[appName.lowercase()] = packageName
            
            return appName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package: ${e.message}")
        }
        
        // Return package name as fallback
        return packageName
    }
    
    /**
     * Check if a string is likely a package name
     */
    private fun isPackageName(str: String): Boolean {
        // Package names typically have at least two segments separated by dots
        val segments = str.split(".")
        if (segments.size < 2) {
            return false
        }
        
        // Check if all segments are valid Java identifiers
        return segments.all { segment ->
            segment.isNotEmpty() && 
            segment.first().isLetter() && 
            segment.all { it.isLetterOrDigit() || it == '_' }
        }
    }
    
    /**
     * Refresh the cache to pick up newly installed apps
     */
    fun refreshCache() {
        Log.d(TAG, "Refreshing app name to package name cache")
        cacheInitialized = false
        appNameToPackageCache.clear()
        packageToAppNameCache.clear()
        initializeCache()
    }
    
    /**
     * Get a list of all installed apps with their package names
     * @return A map of app names to package names
     */
    fun getAllInstalledApps(): Map<String, String> {
        if (!cacheInitialized) {
            initializeCache()
        }
        
        val result = mutableMapOf<String, String>()
        
        try {
            val packageManager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            for (resolveInfo in resolveInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(packageManager).toString()
                result[appName] = packageName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed apps: ${e.message}")
        }
        
        return result
    }
}
