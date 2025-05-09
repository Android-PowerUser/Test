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
        // Changed API URL to timeapi.io for UTC time
        private const val TIME_API_URL = "https://timeapi.io/api/time/current/zone?timeZone=Etc/UTC"
        private const val CONNECTION_TIMEOUT_MS = 15000 // 15 seconds
        private const val READ_TIMEOUT_MS = 15000 // 15 seconds
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = listOf(5000L, 15000L, 30000L) // 5s, 15s, 30s
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service creating.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}, flags: $flags, startId: $startId")
        when (intent?.action) {
            ACTION_START_TIMER -> {
                Log.d(TAG, "onStartCommand: ACTION_START_TIMER received.")
                if (!isTimerRunning) {
                    Log.d(TAG, "onStartCommand: Timer not running, calling startTimerLogic().")
                    startTimerLogic()
                } else {
                    Log.d(TAG, "onStartCommand: Timer already running.")
                }
            }
            ACTION_STOP_TIMER -> {
                Log.d(TAG, "onStartCommand: ACTION_STOP_TIMER received, calling stopTimerLogic().")
                stopTimerLogic()
            }
            else -> {
                Log.w(TAG, "onStartCommand: Received unknown or null action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    private fun startTimerLogic() {
        Log.d(TAG, "startTimerLogic: Entered. Setting isTimerRunning = true.")
        isTimerRunning = true
        Log.d(TAG, "startTimerLogic: Launching coroutine on scope: $scope")
        scope.launch {
            var attempt = 0
            Log.d(TAG, "startTimerLogic: Coroutine started. isTimerRunning: $isTimerRunning, isActive: $isActive")
            while (isTimerRunning && isActive) {
                var success = false
                Log.d(TAG, "startTimerLogic: Loop iteration. Attempt: ${attempt + 1}/$MAX_RETRIES. isTimerRunning: $isTimerRunning, isActive: $isActive")
                try {
                    Log.i(TAG, "Attempting to fetch internet time (attempt ${attempt + 1}/$MAX_RETRIES). URL: $TIME_API_URL")
                    val url = URL(TIME_API_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECTION_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    Log.d(TAG, "startTimerLogic: Connection configured. Timeout: $CONNECTION_TIMEOUT_MS ms. About to connect.")
                    connection.connect() // Explicit connect call
                    Log.d(TAG, "startTimerLogic: Connection established.")

                    val responseCode = connection.responseCode
                    val responseMessage = connection.responseMessage // Get response message for logging
                    Log.i(TAG, "Time API response code: $responseCode, Message: $responseMessage")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        Log.d(TAG, "startTimerLogic: HTTP_OK received. Reading input stream.")
                        val inputStream = connection.inputStream
                        val result = inputStream.bufferedReader().use { it.readText() }
                        inputStream.close()
                        Log.d(TAG, "startTimerLogic: Input stream closed. Raw JSON result: $result")
                        connection.disconnect()
                        Log.d(TAG, "startTimerLogic: Connection disconnected.")

                        val jsonObject = JSONObject(result)
                        // Updated to parse "dateTime" field from timeapi.io
                        val currentDateTimeStr = jsonObject.getString("dateTime") 
                        Log.d(TAG, "startTimerLogic: Parsed dateTime string: $currentDateTimeStr")
                        // Parse ISO 8601 string to milliseconds since epoch
                        val currentUtcTimeMs = OffsetDateTime.parse(currentDateTimeStr).toInstant().toEpochMilli()

                        Log.i(TAG, "Successfully fetched and parsed internet time. UTC Time MS: $currentUtcTimeMs (from string: $currentDateTimeStr)")

                        val trialState = TrialManager.getTrialState(applicationContext, currentUtcTimeMs)
                        Log.i(TAG, "Current trial state from TrialManager: $trialState (based on time $currentUtcTimeMs)")
                        when (trialState) {
                            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                                Log.d(TAG, "TrialState: NOT_YET_STARTED_AWAITING_INTERNET. Calling startTrialIfNecessaryWithInternetTime and broadcasting ACTION_INTERNET_TIME_AVAILABLE.")
                                TrialManager.startTrialIfNecessaryWithInternetTime(applicationContext, currentUtcTimeMs)
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED -> {
                                Log.d(TAG, "TrialState: ACTIVE_INTERNET_TIME_CONFIRMED. Broadcasting ACTION_INTERNET_TIME_AVAILABLE.")
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                                Log.i(TAG, "TrialState: EXPIRED_INTERNET_TIME_CONFIRMED. Trial expired based on internet time. Broadcasting ACTION_TRIAL_EXPIRED and stopping timer.")
                                sendBroadcast(Intent(ACTION_TRIAL_EXPIRED))
                                stopTimerLogic()
                            }
                            TrialManager.TrialState.PURCHASED -> {
                                Log.i(TAG, "TrialState: PURCHASED. App is purchased. Stopping timer.")
                                stopTimerLogic()
                            }
                            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                                Log.w(TAG, "TrialState: INTERNET_UNAVAILABLE_CANNOT_VERIFY from TrialManager, but we just fetched time. This is unexpected. Broadcasting ACTION_INTERNET_TIME_AVAILABLE anyway.")
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                        }
                        success = true
                        Log.d(TAG, "startTimerLogic: Time fetch successful. success = true. Resetting attempt counter.")
                        attempt = 0 // Reset attempts on success
                    } else {
                        Log.e(TAG, "Failed to fetch internet time. HTTP Response code: $responseCode - $responseMessage")
                        connection.disconnect()
                        Log.d(TAG, "startTimerLogic: Connection disconnected after error.")
                        if (responseCode >= 500) {
                            Log.d(TAG, "Server error ($responseCode). Will retry.")
                        } else {
                            Log.d(TAG, "Client or other error ($responseCode). Will follow general retry logic.")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Failed to fetch internet time: Socket Timeout after $CONNECTION_TIMEOUT_MS ms (connect) or $READ_TIMEOUT_MS ms (read). Attempt ${attempt + 1}", e)
                } catch (e: MalformedURLException) {
                   Log.e(TAG, "Failed to fetch internet time: Malformed URL 	$TIME_API_URL	. Stopping timer logic.", e)
                    stopTimerLogic() // URL is wrong, no point in retrying
                    return@launch
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to fetch internet time: IO Exception (e.g., network issue, connection reset). Attempt ${attempt + 1}", e)
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse JSON response from time API. Response might not be valid JSON. Attempt ${attempt + 1}", e)
                } catch (e: DateTimeParseException) {
                    Log.e(TAG, "Failed to parse date/time string from time API response. API format might have changed. Attempt ${attempt + 1}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "An unexpected error occurred while fetching or processing internet time. Attempt ${attempt + 1}", e)
                }

                if (!isTimerRunning || !isActive) {
                    Log.d(TAG, "startTimerLogic: Loop condition check: isTimerRunning=$isTimerRunning, isActive=$isActive. Breaking loop.")
                    break // Exit loop if timer stopped or coroutine cancelled
                }

                if (!success) {
                    attempt++
                    Log.w(TAG, "startTimerLogic: Time fetch failed for attempt ${attempt} of $MAX_RETRIES.")
                    if (attempt < MAX_RETRIES) {
                        val delayMs = RETRY_DELAYS_MS.getOrElse(attempt -1) { RETRY_DELAYS_MS.last() }
                        Log.i(TAG, "Time fetch failed. Retrying in ${delayMs / 1000}s... (Attempt ${attempt + 1}/$MAX_RETRIES)")
                        Log.d(TAG, "Broadcasting ACTION_INTERNET_TIME_UNAVAILABLE before retry delay.")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                        delay(delayMs)
                    } else {
                        Log.e(TAG, "Failed to fetch internet time after $MAX_RETRIES attempts. Broadcasting ACTION_INTERNET_TIME_UNAVAILABLE.")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                        Log.d(TAG, "Resetting attempt counter to 0 and waiting for CHECK_INTERVAL_MS (${CHECK_INTERVAL_MS / 1000}s) before next cycle of attempts.")
                        attempt = 0 // Reset attempts for next full CHECK_INTERVAL_MS cycle
                        delay(CHECK_INTERVAL_MS) // Wait for the normal check interval after max retries failed
                    }
                } else {
                    Log.d(TAG, "startTimerLogic: Time fetch was successful. Waiting for CHECK_INTERVAL_MS (${CHECK_INTERVAL_MS / 1000}s) before next check.")
                    delay(CHECK_INTERVAL_MS)
                }
            }
            Log.i(TAG, "Timer coroutine ended. isTimerRunning: $isTimerRunning, isActive: $isActive")
        }
    }

    private fun stopTimerLogic() {
        Log.d(TAG, "stopTimerLogic: Entered. Current isTimerRunning: $isTimerRunning")
        if (isTimerRunning) {
            Log.i(TAG, "Stopping timer logic...")
            isTimerRunning = false
            Log.d(TAG, "Cancelling job: $job")
            job.cancel() // Cancel all coroutines started by this scope
            Log.d(TAG, "Calling stopSelf() to stop the service.")
            stopSelf() // Stop the service itself
            Log.i(TAG, "Timer stopped and service is stopping.")
        } else {
            Log.d(TAG, "stopTimerLogic: Timer was not running.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called with intent: ${intent?.action}. Returning null.")
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Service Destroyed. Ensuring timer is stopped via stopTimerLogic().")
        stopTimerLogic()
    }
}

