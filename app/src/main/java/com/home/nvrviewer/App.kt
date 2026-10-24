package com.home.nvrviewer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.Settings

/**
 * App-wide state shared by the screens and the background monitor:
 * the event log, current health warnings, and which screen is in front
 * (so a detection can pop up the right way).
 */
class App : Application() {

    lateinit var eventLog: EventLog
        private set

    /** Current problems (empty = all fine). */
    var warnings: List<String> = emptyList()
        private set

    /** Screens that want to know about new events / warnings. */
    private val listeners = mutableSetOf<() -> Unit>()

    /** The screen currently in front, or null if another app is on screen. */
    var foreground: Activity? = null
        private set

    private val lastPopup = mutableMapOf<Int, Long>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        eventLog = EventLog(java.io.File(filesDir, "events.txt"))
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { foreground = a }
            override fun onActivityPaused(a: Activity) { if (foreground === a) foreground = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun setWarnings(w: List<String>) {
        warnings = w
        notifyListeners()
    }

    /** A detection arrived from the NVR (main thread). */
    fun onAlert(ev: AlertEvent) {
        val isNew = eventLog.add(ev)
        if (isNew) notifyListeners()

        val config = NvrConfig.load(this)
        if (!ev.triggersPopup(config.popupMode)) return
        val now = System.currentTimeMillis()
        if (now - (lastPopup[ev.channel] ?: 0L) < POPUP_COOLDOWN_MS) return

        val cameras = NvrConfig.applyCameraOrder(this, NvrConfig.loadCameraCache(this))
        val camera = cameras.firstOrNull { it.channel == ev.channel } ?: return
        val banner = "${ev.what} · ${camera.name} · ${ev.timeText()}"

        val shown = when (val fg = foreground) {
            is PlayerActivity -> { fg.popup(ev.channel, banner); true }
            is MainActivity -> { fg.showPopup(ev.channel, banner); true }
            // You're busy in Settings, Events or Playback: don't interrupt.
            is SettingsActivity, is EventsActivity, is PlaybackActivity -> false
            else -> if (config.popupOverApps && Settings.canDrawOverlays(this)) {
                startActivity(PlayerActivity.popupIntent(this, cameras, camera.channel, banner)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } else false
        }
        if (shown) {
            lastPopup[ev.channel] = now
            if (config.chime) chime()
        }
    }

    private fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            android.os.Handler(mainLooper).postDelayed({ tone.release() }, 1_000)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        /** The same camera pops up again at most once a minute. */
        private const val POPUP_COOLDOWN_MS = 60_000L
    }
}
