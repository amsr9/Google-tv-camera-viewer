package com.home.nvrviewer

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Watch the NVR's recording of one camera from a chosen time.
 * ◀ ▶ 30 s · ▼ ▲ 10 min · OK pause/play · Menu: pick another time · Back exit.
 *
 * Uses the NVR's playback address (rtsp://…/Streaming/tracks/101?starttime=…),
 * which Hikvision reads in the NVR's own local time.
 */
class PlaybackActivity : Activity() {

    private lateinit var config: NvrConfig
    private lateinit var video: VLCVideoLayout
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var hint: View
    private lateinit var picker: View
    private lateinit var day: OptionSelector
    private lateinit var hour: OptionSelector
    private lateinit var minute: OptionSelector

    private var channel = 1
    private var name = ""
    private var player: MediaPlayer? = null
    /** Wall-clock time where the current playback started. */
    private var sessionStart = 0L
    private var paused = false
    private var playing = false
    private var okPressed = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 500)
        }
    }
    private val hideHint = Runnable { hint.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)
        config = NvrConfig.load(this)
        channel = intent.getIntExtra(EXTRA_CHANNEL, 1)
        name = intent.getStringExtra(EXTRA_NAME) ?: "Camera $channel"

        video = findViewById(R.id.video)
        info = findViewById(R.id.info)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        picker = findViewById(R.id.picker)
        findViewById<TextView>(R.id.picker_title).text = "Playback · $name"

        val start = intent.getLongExtra(EXTRA_TIME, -1L)
        val suggested = if (start > 0) start else System.currentTimeMillis() - 5 * 60_000L
        buildPicker(suggested)
        findViewById<Button>(R.id.p_play).setOnClickListener { playFromPicker() }

        if (start > 0) play(start) else showPicker()
    }

    override fun onStop() {
        super.onStop()
        stopPlayer()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------- Time picker ----------

    private fun buildPicker(t: Long) {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val daysAgo = daysBetween(t, System.currentTimeMillis()).coerceIn(0, 29)
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.US)
        day = OptionSelector(findViewById(R.id.p_day), "Day", (0..29).toList(), daysAgo) { ago ->
            when (ago) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> dayFmt.format(Date(System.currentTimeMillis() - ago * DAY_MS))
            }
        }
        hour = OptionSelector(findViewById(R.id.p_hour), "Hour", (0..23).toList(), c.get(Calendar.HOUR_OF_DAY)) { h ->
            val am = if (h < 12) "am" else "pm"
            val h12 = if (h % 12 == 0) 12 else h % 12
            "$h12 $am"
        }
        minute = OptionSelector(findViewById(R.id.p_minute), "Minute", (0..59).toList(), c.get(Calendar.MINUTE)) {
            ":" + it.toString().padStart(2, '0')
        }
    }

    private fun daysBetween(earlier: Long, later: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = earlier; set(Calendar.HOUR_OF_DAY, 12) }
        val b = Calendar.getInstance().apply { timeInMillis = later; set(Calendar.HOUR_OF_DAY, 12) }
        return ((b.timeInMillis - a.timeInMillis) / DAY_MS).toInt()
    }

    private fun showPicker() {
        picker.visibility = View.VISIBLE
        findViewById<Button>(R.id.p_play).requestFocus()
    }

    private fun playFromPicker() {
        val c = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() - day.value * DAY_MS
            set(Calendar.HOUR_OF_DAY, hour.value)
            set(Calendar.MINUTE, minute.value)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        picker.visibility = View.GONE
        play(c.timeInMillis)
    }

    // ---------- Playing ----------

    private fun play(at: Long) {
        // Recordings exist up to a few seconds ago, not the future.
        val t = at.coerceAtMost(System.currentTimeMillis() - 15_000L)
        stopPlayer()
        sessionStart = t
        paused = false
        playing = false
        showStatus("Loading recording…")

        val vlc = VlcEngine.get(this)
        val p = MediaPlayer(vlc)
        p.attachViews(video, null, false, false)
        val media = Media(vlc, Uri.parse(url(t)))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1000")
        p.setMedia(media)
        media.release()
        p.setEventListener { e -> handler.post { onEvent(p, e) } }
        p.play()
        player = p
        handler.removeCallbacks(tick)
        handler.post(tick)
        flashHint()
    }

    private fun url(t: Long): String {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US) // NVR local time, despite the Z
        val base = config.rtspUrl(channel, 1).replace("/Streaming/Channels/", "/Streaming/tracks/")
        return "$base?starttime=${f.format(Date(t))}"
    }

    private fun onEvent(p: MediaPlayer, e: MediaPlayer.Event) {
        if (p !== player) return
        when (e.type) {
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) {
                playing = true
                status.visibility = View.GONE
            }
            MediaPlayer.Event.EncounteredError ->
                showStatus("No recording found at this time.\nHold OK (or press Menu) to pick another time.")
            MediaPlayer.Event.EndReached ->
                showStatus("End of the recording.\n◀ to go back, or pick another time.")
        }
    }

    /** Wall-clock time of the picture on screen. */
    private fun position(): Long = sessionStart + (player?.time ?: 0L).coerceAtLeast(0L)

    private fun jump(ms: Long) = play(position() + ms)

    private fun togglePause() {
        val p = player ?: return
        if (paused) p.play() else p.pause()
        paused = !paused
        updateInfo()
    }

    private fun stopPlayer() {
        player?.let {
            it.setEventListener(null)
            it.stop()
            it.detachViews()
            it.release()
        }
        player = null
    }

    private fun updateInfo() {
        val f = SimpleDateFormat("EEE d MMM  ·  h:mm:ss a", Locale.US)
        val state = when {
            paused -> "⏸ Paused"
            playing -> "▶ Recording"
            else -> "…"
        }
        info.text = "$name   ·   ${f.format(Date(position()))}   ·   $state"
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun flashHint() {
        hint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 6_000)
    }

    // ---------- Remote ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        val code = event.keyCode

        if (picker.visibility == View.VISIBLE) {
            // Back closes the picker if something is playing behind it.
            if (code == KeyEvent.KEYCODE_BACK && player != null) {
                if (up) picker.visibility = View.GONE
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (down) flashHint()
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) jump(-30_000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) jump(30_000); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) jump(-10 * 60_000L); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) jump(10 * 60_000L); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (down) { buildPicker(position()); showPicker() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) togglePause(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) okPressed = true
                else if (down && event.repeatCount == 1) {
                    // Hold OK: pick another time (for remotes without a Menu key).
                    okPressed = false
                    buildPicker(position())
                    showPicker()
                } else if (up && okPressed) { okPressed = false; togglePause() }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NAME = "name"
        /** Wall-clock time to start at (ms). Missing = show the time picker. */
        const val EXTRA_TIME = "time"
        private const val DAY_MS = 24 * 3600_000L
    }
}
