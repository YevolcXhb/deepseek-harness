package com.dsh.mobile

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * Foreground service: prevents Android from killing the proot container.
 *
 * Keep-alive layers:
 *   L1: startForeground() — system-level foreground service
 *   L2: WakeLock — CPU keep-alive during sleep
 *   L3: JobScheduler health check — periodic inspection + auto-restart
 *   L4: WorkManager fallback — delayed auto-restart after kill
 *   L5: TCP Socket — receives dsh tool-keepalive commands
 *
 * AI can call via dsh tool-keepalive:
 *   keepalive(action="activate", level="high", durationSeconds=600)
 *   → dsh sends command via TCP to this service
 *   → service activates WakeLock + foreground notification
 */
class DshKeepAliveService : Service() {

    companion object {
        private const val TAG = "DshKeepAlive"
        private const val NOTIFICATION_ID = 0xD5F1
        private const val ACTION_ACTIVATE = "com.dsh.mobile.ACTIVATE"
        private const val ACTION_RELEASE = "com.dsh.mobile.RELEASE"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PID = "pid"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var socketThread: Thread? = null
    private var socketServer: ServerSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVATE -> {
                val level = intent.getStringExtra(EXTRA_LEVEL) ?: "medium"
                val duration = intent.getLongExtra(EXTRA_DURATION, 300_000L)
                val pid = intent.getIntExtra(EXTRA_PID, 0)
                activateKeepAlive(level, duration, pid)
            }
            ACTION_RELEASE -> releaseKeepAlive()
            else -> startDefaultForeground()
        }
        return START_STICKY
    }

    private fun startDefaultForeground() {
        val notification = createNotification(getString(R.string.dsh_notification_title), "medium")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startSocketListener()
    }

    private fun activateKeepAlive(level: String, duration: Long, pid: Int) {
        DshKeepAliveBridge.setMonitoredPid(pid)
        val notifText = getString(R.string.keepalive_activated, level)
        val notification = createNotification(notifText, level)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock?.release()
        val wakeLockFlags = when (level) {
            "low" -> PowerManager.PARTIAL_WAKE_LOCK
            "medium" -> PowerManager.PARTIAL_WAKE_LOCK
            "high" -> PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
            "critical" -> PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE or PowerManager.SCREEN_BRIGHT_WAKE_LOCK
            else -> PowerManager.PARTIAL_WAKE_LOCK
        }
        wakeLock = powerManager.newWakeLock(wakeLockFlags, "DSH::KeepAlive::$level").apply {
            setReferenceCounted(false)
            acquire(duration)
        }
        if (level == "high" || level == "critical") {
            requestBatteryOptimizationExemption()
        }
        Log.i(TAG, "Keep-alive activated: level=$level, duration=${duration}ms, pid=$pid")
    }

    private fun releaseKeepAlive() {
        wakeLock?.release()
        wakeLock = null
        startDefaultForeground()
        Log.i(TAG, "Keep-alive released, downgraded to default monitoring")
    }

    private fun createNotification(title: String, level: String): Notification {
        return NotificationCompat.Builder(this, DshApplication.CHANNEL_ID_KEEPALIVE)
            .setContentTitle(title)
            .setContentText(getString(R.string.dsh_notification_text))
            .setSmallIcon(R.drawable.ic_dsh_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization exemption not available")
        }
    }

    private fun startSocketListener() {
        socketThread = Thread {
            try {
                socketServer = ServerSocket(0)
                java.io.File(DshApplication.KEEPALIVE_PORT_FILE)
                    .writeText(socketServer!!.localPort.toString())
                Log.i(TAG, "Socket listener on port ${socketServer!!.localPort}")
                while (!Thread.currentThread().isInterrupted) {
                    val client = socketServer!!.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val line = reader.readLine()
                    if (line != null) handleSocketCommand(line)
                    client.close()
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "Socket listener error", e)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleSocketCommand(json: String) {
        try {
            val cmd = org.json.JSONObject(json)
            val action = cmd.getString("action")
            val level = cmd.optString("level", "medium")
            val duration = cmd.optLong("duration", 300_000L)
            val pid = cmd.optInt("pid", DshKeepAliveBridge.getMonitoredPid())
            Log.i(TAG, "Received keepalive command: action=$action, level=$level, pid=$pid")
            when (action) {
                "activate" -> activateKeepAlive(level, duration, pid)
                "release" -> releaseKeepAlive()
                "extend" -> wakeLock?.acquire(duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling socket command", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        socketThread?.interrupt()
        socketServer?.close()
        DshRestartWorker.enqueue(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
