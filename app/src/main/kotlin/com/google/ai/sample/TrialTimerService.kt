package com.google.ai.sample

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class TrialTimerService : Service() {

    private lateinit var handler: Handler
    private lateinit var runnable: Runnable
    private var remainingTimeMs: Long = 0L

    companion object {
        const val ACTION_START_TIMER = "com.google.ai.sample.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.google.ai.sample.ACTION_STOP_TIMER"
        const val ACTION_TRIAL_EXPIRED = "com.google.ai.sample.ACTION_TRIAL_EXPIRED"
        const val EXTRA_REMAINING_TIME_MS = "extra_remaining_time_ms"
        private const val TAG = "TrialTimerService"
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val trialStartTime = TrialManager.getTrialStartTime(this)
                if (trialStartTime == 0L) {
                    Log.e(TAG, "Trial start time not set, stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val elapsedTimeMs = System.currentTimeMillis() - trialStartTime
                remainingTimeMs = TrialManager.TRIAL_DURATION_MS - elapsedTimeMs

                if (remainingTimeMs <= 0) {
                    Log.d(TAG, "Trial already expired or duration is zero.")
                    notifyTrialExpired()
                    stopSelf()
                } else {
                    Log.d(TAG, "Starting timer with remaining time: $remainingTimeMs ms")
                    startTimer(remainingTimeMs)
                }
            }
            ACTION_STOP_TIMER -> {
                Log.d(TAG, "Stopping timer via action.")
                stopTimer()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown or null action received.")
                // If service is restarted and intent is null, check trial status
                if (intent == null && TrialManager.isTrialStarted(this) && !TrialManager.isTrialExpired(this)) {
                    val trialStartTime = TrialManager.getTrialStartTime(this)
                    val elapsedTimeMs = System.currentTimeMillis() - trialStartTime
                    remainingTimeMs = TrialManager.TRIAL_DURATION_MS - elapsedTimeMs
                    if (remainingTimeMs > 0) {
                        Log.d(TAG, "Service restarted, resuming timer with remaining: $remainingTimeMs ms")
                        startTimer(remainingTimeMs)
                    } else {
                        Log.d(TAG, "Service restarted, trial expired.")
                        notifyTrialExpired()
                        stopSelf()
                    }
                } else if (intent == null) {
                    Log.d(TAG, "Service restarted with null intent, but no active trial or trial already expired. Stopping.")
                    stopSelf()
                }
            }
        }
        // If the service is killed, it will be restarted with the last intent (if available)
        // or null if it was started without sticky flags and killed before onStartCommand returned START_STICKY.
        // Using START_STICKY to ensure it attempts to restart and continue the timer.
        return START_STICKY
    }

    private fun startTimer(durationMs: Long) {
        stopTimer() // Stop any existing timer first
        runnable = Runnable {
            Log.d(TAG, "Timer finished.")
            notifyTrialExpired()
            stopSelf()
        }
        handler.postDelayed(runnable, durationMs)
        Log.d(TAG, "Timer scheduled for $durationMs ms")
    }

    private fun stopTimer() {
        if (::runnable.isInitialized) {
            handler.removeCallbacks(runnable)
            Log.d(TAG, "Timer callbacks removed.")
        }
    }

    private fun notifyTrialExpired() {
        Log.d(TAG, "Trial expired. Notifying MainActivity and setting KeyStore.")
        TrialManager.setTrialExpiredInKeyStore(this)
        val intent = Intent(ACTION_TRIAL_EXPIRED)
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are not using binding
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        Log.d(TAG, "Service Destroyed")
    }
}

