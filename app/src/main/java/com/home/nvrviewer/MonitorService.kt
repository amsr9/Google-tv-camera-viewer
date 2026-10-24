package com.home.nvrviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * Runs in the background while the TV is on: listens for detections, checks
 * the NVR's health, and (if enabled) opens the camera wall when the TV wakes up.
 */
class MonitorService : Service() {

    private var events: EventMonitor? = null
    private var health: HealthMonitor? = null
    /** Connection details the monitors use; display settings don't need a restart. */
    private var connectionInUse: List<Any>? = null

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) openCameraWallIfWanted(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Camera monitor", NotificationManager.IMPORTANCE_MIN),
        )
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Home Cameras")
            .setContentText("Watching for detections")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, note)
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOn, filter)
    }

    /** Called on start and whenever settings are saved: (re)starts with the current settings. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = NvrConfig.load(this)
        if (!config.isComplete) {
            stopMonitors()
            return START_STICKY
        }
        val connection = listOf(config.host, config.httpPort, config.username, config.password)
        if (connection != connectionInUse) {
            stopMonitors()
            connectionInUse = connection
            val app = application as App
            events = EventMonitor(config) { app.onAlert(it) }.also { it.start() }
            health = HealthMonitor(config, { NvrConfig.loadCameraCache(this) }) { app.setWarnings(it) }
                .also { it.start() }
        }
        return START_STICKY
    }

    private fun stopMonitors() {
        events?.stop()
        events = null
        health?.stop()
        health = null
        connectionInUse = null
    }

    override fun onDestroy() {
        stopMonitors()
        runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTE_ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MonitorService::class.java)) }
        }

        /**
         * Opens the camera wall if "Open on TV start-up" is on and nothing of ours is
         * already showing. Android only allows this with "Display over other apps".
         */
        fun openCameraWallIfWanted(context: Context) {
            val config = NvrConfig.load(context)
            if (!config.autoStart || !config.isComplete) return
            if (App.instance.foreground != null) return
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
    }
}
