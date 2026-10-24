package com.home.nvrviewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * One camera full screen in full 4K quality (VLC), or full-size snapshots if
 * live video isn't available.
 *
 * Remote: ◀ ▶ other cameras · ▼ menu (Zoom / Playback / Events) · OK or Back: grid.
 * When opened by a detection ("pop-up"), it returns to the grid by itself.
 */
class PlayerActivity : Activity() {

    private lateinit var config: NvrConfig
    private lateinit var channels: IntArray
    private lateinit var names: Array<String>
    private lateinit var label: TextView
    private lateinit var statusText: TextView
    private lateinit var banner: TextView
    private lateinit var zoomHint: TextView
    private lateinit var actionBar: View
    private lateinit var zoomFrame: View
    private lateinit var playerView: VLCVideoLayout
    private lateinit var image: ImageView
    private lateinit var stream: VlcStreamPlayer
    private var snapshots: SnapshotFeed? = null

    private var index = 0
    private var liveReported = false
    private var okPressed = false

    // Digital zoom
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    // Pop-up: opened by a detection, goes back to the grid after a while.
    private var popupActive = false
    private val endPopup = Runnable { if (popupActive) finish() }
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private val handler = Handler(Looper.getMainLooper())
    private val hideLabel = Runnable { label.animate().alpha(0f).setDuration(400).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        config = NvrConfig.load(this)
        channels = intent.getIntArrayExtra(EXTRA_CHANNELS) ?: intArrayOf()
        names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        index = savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)
        if (channels.isEmpty()) { finish(); return }
        index = index.coerceIn(0, channels.size - 1)

        label = findViewById(R.id.label)
        statusText = findViewById(R.id.status)
        banner = findViewById(R.id.popup_banner)
        zoomHint = findViewById(R.id.zoom_hint)
        actionBar = findViewById(R.id.action_bar)
        zoomFrame = findViewById(R.id.zoom_frame)
        playerView = findViewById(R.id.player_view)
        image = findViewById(R.id.snapshot)

        // TextureView (inTile = true) so the picture can be zoomed; whole picture, no trimming.
        stream = VlcStreamPlayer(this, playerView, inTile = true, fitMode = NvrConfig.FIT_BARS) { s, reason ->
            when (s) {
                StreamPlayer.Status.CONNECTING -> showStatus("Connecting…")
                StreamPlayer.Status.PLAYING -> {
                    statusText.visibility = View.GONE
                    liveReported = true
                    VideoMode.fullScreenFailed.remove(channels[index])
                }
                StreamPlayer.Status.UNREACHABLE ->
                    showStatus("Can't reach camera · retrying\n${reason ?: ""}".trim())
                StreamPlayer.Status.RETRYING, StreamPlayer.Status.AUTH_FAILED -> {
                    // Full-quality video never started here, or the login was refused:
                    // show full-size snapshots instead.
                    if (s == StreamPlayer.Status.AUTH_FAILED || !liveReported) {
                        VideoMode.fullScreenFailed.add(channels[index])
                        startSnapshots(channels[index])
                    } else {
                        showStatus("Camera offline · retrying\n${reason ?: ""}".trim())
                    }
                }
            }
        }

        findViewById<Button>(R.id.act_zoom).setOnClickListener { hideBar(); setZoom(2f) }
        findViewById<Button>(R.id.act_playback).setOnClickListener {
            hideBar()
            startActivity(
                Intent(this, PlaybackActivity::class.java)
                    .putExtra(PlaybackActivity.EXTRA_CHANNEL, channels[index])
                    .putExtra(PlaybackActivity.EXTRA_NAME, nameOf(index)),
            )
        }
        findViewById<Button>(R.id.act_events).setOnClickListener {
            hideBar()
            startActivity(Intent(this, EventsActivity::class.java))
        }

        intent.getStringExtra(EXTRA_POPUP)?.let { startPopup(it) }
    }

    override fun onStart() {
        super.onStart()
        if (::stream.isInitialized) show(index)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideLabel)
        if (::stream.isInitialized) stopAll()
        // Leaving a pop-up (e.g. for Playback) ends it: no automatic close later.
        popupActive = false
        handler.removeCallbacks(endPopup)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::stream.isInitialized) stream.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun nameOf(i: Int) = names.getOrNull(i) ?: "Camera ${channels[i]}"

    private fun stopAll() {
        stream.release()
        snapshots?.stop()
        snapshots = null
    }

    private fun show(i: Int) {
        index = (i + channels.size) % channels.size
        stopAll()
        setZoom(1f)
        liveReported = false
        val ch = channels[index]
        if (ch in VideoMode.fullScreenFailed) {
            startSnapshots(ch) // full-quality live video already failed once: skip straight to pictures
        } else {
            image.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            stream.play(config.rtspUrl(ch, 1))
        }
        label.text = buildString {
            append(nameOf(index))
            if (channels.size > 1) append("   (${index + 1} of ${channels.size})   ◀ ▶ other cameras")
            append("   ·   ▼ zoom / playback   ·   OK: all cameras")
        }
        flashLabel()
        // Tell the grid which camera to highlight when we go back.
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index))
    }

    private fun flashLabel() {
        label.animate().cancel()
        label.alpha = 1f
        handler.removeCallbacks(hideLabel)
        handler.postDelayed(hideLabel, 4_000)
    }

    private fun startSnapshots(channel: Int) {
        stream.release()
        snapshots?.stop()
        playerView.visibility = View.GONE
        image.visibility = View.VISIBLE
        showStatus("Loading…")
        snapshots = SnapshotFeed(config, channel * 100 + 1, 1920, image) { err ->
            if (err == null) statusText.visibility = View.GONE else showStatus(err)
        }.also { it.start() }
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    // ---------- Pop-up ----------

    private fun startPopup(text: String) {
        popupActive = true
        banner.text = "🔔  $text"
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.removeCallbacks(endPopup)
        handler.postDelayed(endPopup, config.popupSeconds * 1000L)
    }

    /** Any key press means you're watching: keep the camera on screen. */
    private fun cancelPopup() {
        if (!popupActive) return
        popupActive = false
        handler.removeCallbacks(endPopup)
        handler.postDelayed(hideBanner, 3_000)
    }

    /** A new detection while this screen is open (called by [App]). */
    fun popup(channel: Int, text: String) {
        val i = channels.indexOf(channel)
        if (popupActive && i >= 0) {
            // Already showing a pop-up: switch to the new camera.
            if (i != index) show(i)
            startPopup(text)
        } else {
            // You're watching on purpose: just mention it, don't switch.
            banner.text = "🔔  $text"
            banner.visibility = View.VISIBLE
            handler.removeCallbacks(hideBanner)
            handler.postDelayed(hideBanner, 8_000)
        }
    }

    // ---------- ▼ menu ----------

    private fun showBar() {
        actionBar.visibility = View.VISIBLE
        zoomHint.visibility = View.GONE
        findViewById<Button>(R.id.act_zoom).requestFocus()
    }

    private fun hideBar() {
        actionBar.visibility = View.GONE
    }

    // ---------- Digital zoom ----------

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, MAX_ZOOM)
        if (zoom <= 1f) { panX = 0f; panY = 0f }
        applyZoom()
        if (zoom > 1f) {
            zoomHint.text = "Zoom ${zoom.toInt()}×    ◀ ▲ ▼ ▶ move    ·    OK zoom more    ·    Back exit zoom"
            zoomHint.visibility = View.VISIBLE
        } else {
            zoomHint.visibility = View.GONE
        }
    }

    private fun nextZoom() = setZoom(if (zoom >= MAX_ZOOM) 1f else zoom + 1f)

    /** dx/dy = -1, 0 or 1: the direction you want to look. */
    private fun pan(dx: Int, dy: Int) {
        val w = zoomFrame.width.toFloat()
        val h = zoomFrame.height.toFloat()
        val maxX = (zoom - 1f) * w / 2f
        val maxY = (zoom - 1f) * h / 2f
        panX = (panX - dx * w * PAN_STEP).coerceIn(-maxX, maxX)
        panY = (panY - dy * h * PAN_STEP).coerceIn(-maxY, maxY)
        applyZoom()
    }

    private fun applyZoom() {
        zoomFrame.pivotX = zoomFrame.width / 2f
        zoomFrame.pivotY = zoomFrame.height / 2f
        zoomFrame.scaleX = zoom
        zoomFrame.scaleY = zoom
        zoomFrame.translationX = panX
        zoomFrame.translationY = panY
    }

    // ---------- Remote control ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        val code = event.keyCode
        if (down) cancelPopup()
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER

        // ▼ menu open: arrows/OK work on its buttons; Back or ▲ closes it.
        if (actionBar.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
                if (up) hideBar()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Zoomed in: arrows move around, OK zooms more, Back zooms out.
        if (zoom > 1f) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) pan(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) pan(1, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) pan(0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) pan(0, 1); return true }
                KeyEvent.KEYCODE_BACK -> { if (up) setZoom(1f); return true }
            }
            if (isOk) {
                if (down && event.repeatCount == 0) okPressed = true
                else if (up && okPressed) { okPressed = false; nextZoom() }
                return true
            }
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (down) show(index + 1); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down) show(index - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { if (down) showBar(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) flashLabel(); return true }
        }
        // OK goes back to the grid – on release, and only for a press that started
        // here, so the OK that opened this screen (or a long press) can't close it.
        if (isOk) {
            if (down && event.repeatCount == 0) okPressed = true
            else if (up && okPressed) { okPressed = false; finish() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_NAMES = "names"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POPUP = "popup"

        private const val MAX_ZOOM = 4f
        private const val PAN_STEP = 0.12f

        /** Full screen opened by a detection, showing [channel], with a banner. */
        fun popupIntent(context: Context, cameras: List<Camera>, channel: Int, banner: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_CHANNELS, cameras.map { it.channel }.toIntArray())
                .putExtra(EXTRA_NAMES, cameras.map { it.name }.toTypedArray())
                .putExtra(EXTRA_INDEX, cameras.indexOfFirst { it.channel == channel }.coerceAtLeast(0))
                .putExtra(EXTRA_POPUP, banner)
    }
}
