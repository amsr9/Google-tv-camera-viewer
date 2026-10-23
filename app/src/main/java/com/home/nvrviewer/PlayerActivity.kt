package com.home.nvrviewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.PlayerView

/**
 * One camera full screen in full quality (or full-size snapshots if live video
 * isn't available). Left/right switches camera, Back returns to the grid.
 */
class PlayerActivity : Activity() {

    private lateinit var config: NvrConfig
    private lateinit var channels: IntArray
    private lateinit var names: Array<String>
    private lateinit var label: TextView
    private lateinit var statusText: TextView
    private lateinit var playerView: PlayerView
    private lateinit var image: ImageView
    private lateinit var stream: StreamPlayer
    private var snapshots: SnapshotFeed? = null

    private var index = 0
    private var liveReported = false
    private var okPressed = false
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
        playerView = findViewById(R.id.player_view)
        image = findViewById(R.id.snapshot)
        stream = StreamPlayer(this, playerView) { s, reason ->
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
                    val auth = s == StreamPlayer.Status.AUTH_FAILED
                    // Full-quality video never started here (e.g. the TV can't decode 4K),
                    // or the login was refused: show full-size snapshots instead.
                    if (auth || !liveReported) {
                        VideoMode.fullScreenFailed.add(channels[index])
                        startSnapshots(channels[index])
                    } else {
                        showStatus("Camera offline · retrying\n${reason ?: ""}".trim())
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::stream.isInitialized) show(index)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        if (::stream.isInitialized) stopAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun stopAll() {
        stream.release()
        snapshots?.stop()
        snapshots = null
    }

    private fun show(i: Int) {
        index = (i + channels.size) % channels.size
        stopAll()
        liveReported = false
        val ch = channels[index]
        if (ch in VideoMode.fullScreenFailed) {
            startSnapshots(ch) // full-quality live video already failed once: skip straight to pictures
        } else {
            image.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            stream.play(config.rtspUrl(ch, 1))
        }

        val name = names.getOrNull(index) ?: "Camera $ch"
        label.text = buildString {
            append(name)
            if (channels.size > 1) append("   (${index + 1} of ${channels.size})   ◀ ▶ other cameras")
            append("   ·   OK or Back: all cameras")
        }
        label.alpha = 1f
        handler.removeCallbacks(hideLabel)
        handler.postDelayed(hideLabel, 3_000)

        // Tell the grid which camera to highlight when we go back.
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index))
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                show(index + 1); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                show(index - 1); return true
            }
            // OK again goes back to the grid (on release, see onKeyUp).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                okPressed = event.repeatCount == 0
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Show the camera name again.
                label.alpha = 1f
                handler.removeCallbacks(hideLabel)
                handler.postDelayed(hideLabel, 3_000)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Acts on release, and only for a press that started on this screen, so the
     * OK press that opened full screen (or a long press) can't close it again.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (okPressed) finish()
                okPressed = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_NAMES = "names"
        const val EXTRA_INDEX = "index"
    }
}
