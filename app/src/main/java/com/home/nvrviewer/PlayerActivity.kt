package com.home.nvrviewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.ui.PlayerView

/** One camera full screen in full quality. Left/right switches camera, Back returns to the grid. */
class PlayerActivity : Activity() {

    private lateinit var config: NvrConfig
    private lateinit var channels: IntArray
    private lateinit var names: Array<String>
    private lateinit var label: TextView
    private lateinit var statusText: TextView
    private lateinit var stream: StreamPlayer

    private var index = 0
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
        stream = StreamPlayer(this, findViewById<PlayerView>(R.id.player_view)) { s ->
            when (s) {
                StreamPlayer.Status.CONNECTING -> showStatus("Connecting…")
                StreamPlayer.Status.RETRYING -> showStatus("Camera offline · retrying")
                StreamPlayer.Status.PLAYING -> statusText.visibility = View.GONE
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
        if (::stream.isInitialized) stream.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun show(i: Int) {
        index = (i + channels.size) % channels.size
        stream.play(config.rtspUrl(channels[index], 1))

        val name = names.getOrNull(index) ?: "Camera ${channels[index]}"
        label.text = if (channels.size > 1) "$name   (${index + 1} / ${channels.size})   ◀ ▶" else name
        label.alpha = 1f
        handler.removeCallbacks(hideLabel)
        handler.postDelayed(hideLabel, 3_000)

        // Tell the grid which camera to highlight when we go back.
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index))
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                // Show the camera name again.
                label.alpha = 1f
                handler.removeCallbacks(hideLabel)
                handler.postDelayed(hideLabel, 3_000)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_NAMES = "names"
        const val EXTRA_INDEX = "index"
    }
}
