package com.home.nvrviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.PlayerView

/**
 * One cell in the grid: live video (or near-live snapshots), the camera name
 * and a status label.
 */
class CameraTile(
    private val context: Context,
    val camera: Camera,
    private val config: NvrConfig,
    private val listener: Listener,
) {
    interface Listener {
        /** Reported once per startLive(): did live video start? */
        fun onLiveResult(tile: CameraTile, ok: Boolean, authFailed: Boolean, reason: String?)
    }

    val view: View = LayoutInflater.from(context).inflate(R.layout.item_tile, null, false)
    private val label: TextView = view.findViewById(R.id.label)
    private val status: TextView = view.findViewById(R.id.tile_status)
    private val playerView: PlayerView = view.findViewById(R.id.player_view)
    private val image: ImageView = view.findViewById(R.id.snapshot)

    private val handler = Handler(Looper.getMainLooper())
    private var stream: StreamPlayer? = null
    private var snapshots: SnapshotFeed? = null
    private var reported = false

    private val probeTimeout = Runnable { report(false, false, "no video after 15 s") }

    init {
        view.tag = TAG
        label.text = camera.name
    }

    private fun show(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun report(ok: Boolean, auth: Boolean, reason: String?) {
        if (reported) return
        reported = true
        handler.removeCallbacks(probeTimeout)
        listener.onLiveResult(this, ok, auth, reason)
    }

    fun showWaiting() {
        stopAll()
        show("Waiting…")
    }

    /** Live RTSP video (light sub-stream). */
    fun startLive() {
        stopAll()
        reported = false
        image.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        label.text = camera.name
        val s = StreamPlayer(context, playerView) { st, reason ->
            when (st) {
                StreamPlayer.Status.CONNECTING -> show("Connecting…")
                StreamPlayer.Status.PLAYING -> {
                    status.visibility = View.GONE
                    report(true, false, null)
                }
                StreamPlayer.Status.RETRYING -> {
                    show("Offline · retrying\n${reason ?: ""}".trim())
                    report(false, false, reason)
                }
                StreamPlayer.Status.AUTH_FAILED -> {
                    show("Video login refused\n${reason ?: ""}".trim())
                    report(false, true, reason)
                }
            }
        }
        stream = s
        s.play(config.rtspUrl(camera.channel, 2))
        handler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS)
    }

    /** Near-live JPEG snapshots over HTTP, about one per second. */
    fun startSnapshots() {
        stopAll()
        playerView.visibility = View.GONE
        image.visibility = View.VISIBLE
        label.text = "${camera.name}  ·  snapshots"
        show("Loading…")
        snapshots = SnapshotFeed(config, camera.channel * 100 + 2, 960, image) { err ->
            if (err == null) status.visibility = View.GONE else show(err)
        }.also { it.start() }
    }

    private fun stopAll() {
        handler.removeCallbacks(probeTimeout)
        stream?.release()
        stream = null
        snapshots?.stop()
        snapshots = null
    }

    fun release() = stopAll()

    companion object {
        const val TAG = "camera_tile"
        private const val PROBE_TIMEOUT_MS = 15_000L
    }
}
