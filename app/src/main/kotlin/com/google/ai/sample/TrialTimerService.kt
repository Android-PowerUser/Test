package com.google.ai.sample

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        private const val WORLD_TIME_API_URL = "https://worldtimeapi.org/api/timezone/Etc/UTC"
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
        return START_STICKY // Keep service running if killed by system
    }

    private fun startTimerLogic() {
        isTimerRunning = true
        scope.launch {
            while (isTimerRunning) {
                try {
                    val url = URL(WORLD_TIME_API_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val inputStream = connection.inputStream
                        val result = inputStream.bufferedReader().use { it.readText() }
                        inputStream.close()
                        val jsonObject = JSONObject(result)
                        val currentUtcTimeMs = jsonObject.getLong("unixtime") * 1000L
                        Log.d(TAG, "Successfully fetched internet time: $currentUtcTimeMs")

                        val trialState = TrialManager.getTrialState(applicationContext, currentUtcTimeMs)
                        when (trialState) {
                            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                                TrialManager.startTrialIfNecessaryWithInternetTime(applicationContext, currentUtcTimeMs)
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED -> {
                                // Trial is active, continue checking
                                sendBroadcast(Intent(ACTION_INTERNET_TIME_AVAILABLE).putExtra(EXTRA_CURRENT_UTC_TIME_MS, currentUtcTimeMs))
                            }
                            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                                Log.d(TAG, "Trial expired based on internet time.")
                                sendBroadcast(Intent(ACTION_TRIAL_EXPIRED))
                                stopTimerLogic() // Stop further checks if expired
                            }
                            TrialManager.TrialState.PURCHASED -> {
                                Log.d(TAG, "App is purchased. Stopping timer.")
                                stopTimerLogic()
                            }
                            else -> {
                                // Should not happen if logic is correct
                                Log.w(TAG, "Unhandled trial state: $trialState")
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch internet time. Response code: ${connection.responseCode}")
                        sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching internet time or processing trial state", e)
                    sendBroadcast(Intent(ACTION_INTERNET_TIME_UNAVAILABLE))
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun stopTimerLogic() {
        isTimerRunning = false
        job.cancel() // Cancel all coroutines started by this scope
        stopSelf() // Stop the service itself
        Log.d(TAG, "Timer stopped and service is stopping.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are not using binding
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimerLogic() // Ensure timer is stopped when service is destroyed
        Log.d(TAG, "Service Destroyed")
    }
}

