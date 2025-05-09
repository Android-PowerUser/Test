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
        private const val TAG = "TrialTimerService_DEBUG"
        private const val CHECK_INTERVAL_MS = 60 * 1000L // 1 minute
        private const val TIME_API_URL = "http://worldclockapi.com/api/json/utc/now" // Changed API URL
        private const val CONNECTION_TIMEOUT_MS = 15000 // 15 seconds
        private const val READ_TIMEOUT_MS = 15000 // 15 seconds
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = listOf(5000L, 15000L, 30000L) // 5s, 15s, 30s
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service instance created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: Received action: ${intent?.action}, Flags: $flags, StartId: $startId")
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
        Log.i(TAG, "startTimerLogic: Attempting to start timer logic. isTimerRunning: $isTimerRunning")
        if (isTimerRunning) {
            Log.w(TAG, "startTimerLogic: Timer logic already started. Exiting.")
            return
        }
        isTimerRunning = true
        Log.d(TAG, "startTimerLogic: isTimerRunning set to true.")
        scope.launch {
            Log.i(TAG, "startTimerLogic: Coroutine launched. isTimerRunning: $isTimerRunning, isActive: $isActive")
            var attempt = 0
            while (isTimerRunning && isActive) {
                Log.d(TAG, "startTimerLogic: Loop start. isTimerRunning: $isTimerRunning, isActive: $isActive, Attempt: ${attempt + 1}")
                var success = false
                try {
                    Log.d(TAG, "Attempting to fetch internet time (attempt ${attempt + 1}/$MAX_RETRIES). URL: $TIME_API_URL")
                    val url = URL(TIME_API_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECTION_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    Log.d(TAG, "Connecting to time API...")
                    connection.connect()

                    val responseCode = connection.responseCode
                    Log.i(TAG, "Time API response code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val inputStream = connection.inputStream
                        val result = inputStream.bufferedReader().use { it.readText() }
                        inputStream.close()
                        Log.d(TAG, "Time API response: $result")
                        connection.disconnect()

                        val jsonObject = JSONObject(result)
                        val currentDateTimeStr = jsonObject.getString("currentDateTime")
                        val currentUtcTimeMs = OffsetDateTime.parse(currentDateTimeStr).toInstant().toEpochMilli()

                        Log.i(TAG, "Successfully fetched and parsed internet time: $currentUtcTimeMs ($currentDateTimeStr)")
                        Log.d(TAG, "Calling TrialManager.getTrialState with time: $currentUtcTimeMs")
                        val trialState = TrialManager.getTrialState(applicationContext, currentUtcTimeMs)
                        Log.i(TAG, "Current trial state from TrialManager: $trialState")
                        when (trialState) {
                            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                                Log.d(TAG, "TrialState is NOT_YET_STARTED_AWAITING_INTERNET. Calling startTrialIfNecessaryWithInternetTime.")
                                TrialManager.startTrialIfNecessaryWithInternetTime(applicationContext, currentUtcTimeMs)
                                Log.d(TAG, "Broadcasting ACTION_INTERNET_TIME_AVAILABLE with time: $currentUtcTimeMs")
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED -> {
                                Log.d(TAG, "TrialState is ACTIVE_INTERNET_TIME_CONFIRMED. Broadcasting ACTION_INTERNET_TIME_AVAILABLE with time: $currentUtcTimeMs")
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                                Log.i(TAG, "Trial expired based on internet time. Broadcasting ACTION_TRIAL_EXPIRED and stopping timer.")
                                sendBroadcast(Intent(ACTION_TRIAL_EXPIRED))
                                stopTimerLogic()
                            }
                            TrialManager.TrialState.PURCHASED -> {
                                Log.i(TAG, "App is purchased. Stopping timer.")
                                stopTimerLogic()
                            }
                            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                                Log.w(TAG, "TrialManager reported INTERNET_UNAVAILABLE, but we just fetched time. Broadcasting ACTION_INTERNET_TIME_AVAILABLE with time: $currentUtcTimeMs")
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                        }
                        success = true
                        attempt = 0 
                        Log.d(TAG, "Time fetch successful. Resetting attempt count.")
                    } else {
                        Log.e(TAG, "Failed to fetch internet time. HTTP Response code: $responseCode - ${connection.responseMessage}")
                        connection.disconnect()
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Failed to fetch internet time: Socket Timeout", e)
                } catch (e: MalformedURLException) {
                    Log.e(TAG, "Failed to fetch internet time: Malformed URL. Stopping timer.", e)
                    stopTimerLogic()
                    return@launch
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to fetch internet time: IO Exception (e.g., network issue)", e)
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse JSON response from time API", e)
                } catch (e: DateTimeParseException) {
                    Log.e(TAG, "Failed to parse date/time string from time API response", e)
                } catch (e: Exception) {
                    Log.e(TAG, "An unexpected error occurred while fetching or processing internet time", e)
                }

                if (!isTimerRunning || !isActive) {
                    Log.d(TAG, "startTimerLogic: Loop condition false. Exiting loop. isTimerRunning: $isTimerRunning, isActive: $isActive")
                    break
                }

                if (!success) {
                    attempt++
                    Log.w(TAG, "Time fetch failed. Attempt: $attempt")
                    if (attempt < MAX_RETRIES) {
                        val delayMs = RETRY_DELAYS_MS.getOrElse(attempt -1) { RETRY_DELAYS_MS.last() }
                        Log.d(TAG, "Retrying in ${delayMs / 1000}s... Broadcasting ACTION_INTERNET_TIME_UNAVAILABLE.")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                        delay(delayMs)
                    } else {
                        Log.e(TAG, "Failed to fetch internet time after $MAX_RETRIES attempts. Broadcasting ACTION_INTERNET_TIME_UNAVAILABLE. Waiting for CHECK_INTERVAL_MS.")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                        attempt = 0 
                        delay(CHECK_INTERVAL_MS)
                    }
                } else {
                    Log.d(TAG, "Time fetch was successful. Waiting for CHECK_INTERVAL_MS: $CHECK_INTERVAL_MS ms")
                    delay(CHECK_INTERVAL_MS)
                }
            }
            Log.i(TAG, "Timer coroutine ended. isTimerRunning: $isTimerRunning, isActive: $isActive")
        }
    }

    private fun stopTimerLogic() {
        Log.i(TAG, "stopTimerLogic: Attempting to stop timer. isTimerRunning: $isTimerRunning")
        if (isTimerRunning) {
            isTimerRunning = false
            job.cancel() 
            stopSelf() 
            Log.i(TAG, "Timer stopped and service is stopping.")
        } else {
            Log.d(TAG, "stopTimerLogic: Timer was not running.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called, returning null.")
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Service Destroyed. Ensuring timer is stopped.")
        stopTimerLogic()
    }
}

