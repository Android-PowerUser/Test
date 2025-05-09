package com.google.ai.sample

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class TrialTimerService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var isTimerRunning = false

    companion object {
        const val ACTION_START_TIMER = "com.google.ai.sample.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.google.ai.sample.ACTION_STOP_TIMER"
        const val ACTION_TRIAL_EXPIRED = "com.google.ai.sample.ACTION_TRIAL_EXPIRED"
        const val ACTION_INTERNET_TIME_UNAVAILABLE = "com.google.ai.sample.ACTION_INTERNET_TIME_UNAVAILABLE"
        const val ACTION_INTERNET_TIME_AVAILABLE = "com.google.ai.sample.ACTION_INTERNET_TIME_AVAILABLE"
        const val EXTRA_CURRENT_UTC_TIME_MS = "extra_current_utc_time_ms"
        private const val TAG = "TrialTimerService"
        private const val CHECK_INTERVAL_MS = 60 * 1000L // 1 minute
        private const val TIME_API_URL = "http://worldclockapi.com/api/json/utc/now" // Changed API URL
        private const val CONNECTION_TIMEOUT_MS = 15000 // 15 seconds
        private const val READ_TIMEOUT_MS = 15000 // 15 seconds
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = listOf(5000L, 15000L, 30000L) // 5s, 15s, 30s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_TIMER -> {
                if (!isTimerRunning) {
                    startTimerLogic()
                }
            }
            ACTION_STOP_TIMER -> {
                stopTimerLogic()
            }
        }
        return START_STICKY
    }

    private fun startTimerLogic() {
        isTimerRunning = true
        scope.launch {
            var attempt = 0
            while (isTimerRunning && isActive) {
                var success = false
                try {
                    Log.d(TAG, "Attempting to fetch internet time (attempt ${attempt + 1}/$MAX_RETRIES). URL: $TIME_API_URL")
                    val url = URL(TIME_API_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECTION_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.connect() // Explicit connect call

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Time API response code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val inputStream = connection.inputStream
                        val result = inputStream.bufferedReader().use { it.readText() }
                        inputStream.close()
                        connection.disconnect()

                        val jsonObject = JSONObject(result)
                        val currentDateTimeStr = jsonObject.getString("currentDateTime")
                        // Parse ISO 8601 string to milliseconds since epoch
                        val currentUtcTimeMs = OffsetDateTime.parse(currentDateTimeStr).toInstant().toEpochMilli()

                        Log.d(TAG, "Successfully fetched and parsed internet time: $currentUtcTimeMs ($currentDateTimeStr)")

                        val trialState = TrialManager.getTrialState(applicationContext, currentUtcTimeMs)
                        Log.d(TAG, "Current trial state from TrialManager: $trialState")
                        when (trialState) {
                            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                                TrialManager.startTrialIfNecessaryWithInternetTime(applicationContext, currentUtcTimeMs)
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED -> {
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                                Log.d(TAG, "Trial expired based on internet time.")
                                sendBroadcast(Intent(ACTION_TRIAL_EXPIRED))
                                stopTimerLogic()
                            }
                            TrialManager.TrialState.PURCHASED -> {
                                Log.d(TAG, "App is purchased. Stopping timer.")
                                stopTimerLogic()
                            }
                            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                                // This case might occur if TrialManager was called with null time before, 
                                // but now we have time. So we should re-broadcast available time.
                                Log.w(TAG, "TrialManager reported INTERNET_UNAVAILABLE, but we just fetched time. Broadcasting available.")
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                        }
                        success = true
                        attempt = 0 // Reset attempts on success
                    } else {
                        Log.e(TAG, "Failed to fetch internet time. HTTP Response code: $responseCode - ${connection.responseMessage}")
                        connection.disconnect()
                        // For server-side errors (5xx), retry is useful. For client errors (4xx), less so unless temporary.
                        if (responseCode >= 500) { 
                            // Retry for server errors
                        } else {
                            // For other errors (e.g. 404), might not be worth retrying indefinitely the same way
                            // but we will follow the general retry logic for now.
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Failed to fetch internet time: Socket Timeout", e)
                } catch (e: MalformedURLException) {
                    Log.e(TAG, "Failed to fetch internet time: Malformed URL", e)
                    stopTimerLogic() // URL is wrong, no point in retrying
                    return@launch
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to fetch internet time: IO Exception (e.g., network issue)", e)
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse JSON response from time API", e)
                    // API might have changed format or returned error HTML, don't retry indefinitely for this specific error on this attempt.
                } catch (e: DateTimeParseException) {
                    Log.e(TAG, "Failed to parse date/time string from time API response", e)
                } catch (e: Exception) {
                    Log.e(TAG, "An unexpected error occurred while fetching or processing internet time", e)
                }

                if (!isTimerRunning || !isActive) break // Exit loop if timer stopped

                if (!success) {
                    attempt++
                    if (attempt < MAX_RETRIES) {
                        val delayMs = RETRY_DELAYS_MS.getOrElse(attempt -1) { RETRY_DELAYS_MS.last() }
                        Log.d(TAG, "Time fetch failed. Retrying in ${delayMs / 1000}s...")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE)) // Notify UI about current unavailability before retry
                        delay(delayMs)
                    } else {
                        Log.e(TAG, "Failed to fetch internet time after $MAX_RETRIES attempts. Broadcasting unavailability.")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                        attempt = 0 // Reset attempts for next full CHECK_INTERVAL_MS cycle
                        delay(CHECK_INTERVAL_MS) // Wait for the normal check interval after max retries failed
                    }
                } else {
                    // Success, wait for the normal check interval
                    delay(CHECK_INTERVAL_MS)
                }
            }
            Log.d(TAG, "Timer coroutine ended.")
        }
    }

    private fun stopTimerLogic() {
        if (isTimerRunning) {
            Log.d(TAG, "Stopping timer logic...")
            isTimerRunning = false
            job.cancel() // Cancel all coroutines started by this scope
            stopSelf() // Stop the service itself
            Log.d(TAG, "Timer stopped and service is stopping.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed. Ensuring timer is stopped.")
        stopTimerLogic()
    }
}

