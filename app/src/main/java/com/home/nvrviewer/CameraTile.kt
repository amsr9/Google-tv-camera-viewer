package com.home.nvrviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.util.VLCVideoLayout

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
    /**
     * The large camera in the "1 big + 5 small" layout. It plays the sharp 4K
     * main stream through VLC instead of the small grid stream.
     */
    var big = false
        private set

    interface Listener {
        /** Reported once per startLive(): did live video start? */
        fun onLiveResult(tile: CameraTile, ok: Boolean, authFailed: Boolean, reason: String?)
    }

    val view: View = LayoutInflater.from(context).inflate(R.layout.item_tile, null, false)
    private val label: TextView = view.findViewById(R.id.label)
    private val status: TextView = view.findViewById(R.id.tile_status)
    private val playerView: PlayerView = view.findViewById(R.id.player_view)
    private val image: ImageView = view.findViewById(R.id.snapshot)
    private val vlcView: VLCVideoLayout = view.findViewById(R.id.vlc_view)
    private var vlc: VlcStreamPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var baseLabel: CharSequence = camera.name
    private var isMoving = false
    private var stream: StreamPlayer? = null
    private var snapshots: SnapshotFeed? = null
    private var reported = false

    private val probeTimeout = Runnable { report(false, false, "no video after 15 s") }

    init {
        view.tag = TAG
        setLabel(camera.name)
        applyFit(config.fitMode)
    }

    /** Fill the tile (trim edges), stretch, or fit with black bars, as chosen in Settings. */
    @OptIn(UnstableApi::class)
    private fun applyFit(mode: Int) {
        when (mode) {
            NvrConfig.FIT_STRETCH -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                image.scaleType = ImageView.ScaleType.FIT_XY
            }
            NvrConfig.FIT_BARS -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                image.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            else -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                image.scaleType = ImageView.ScaleType.CENTER_CROP
            }
        }
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

    /** Becoming / no longer being the big camera restarts the right stream. */
    fun setBig(value: Boolean) {
        if (value == big) return
        big = value
        if (stream != null || vlc != null || snapshots != null) {
            if (big) startVlc(main = true) else startLive()
        }
    }

    /** Live RTSP video (light sub-stream), or the 4K stream for the big camera. */
    fun startLive() {
        if (big) { startVlc(main = true); return }
        stopAll()
        reported = false
        image.visibility = View.GONE
        vlcView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        setLabel(camera.name)
        val s = StreamPlayer(context, playerView, preferSoftwareDecoder = true) { st, reason ->
            when (st) {
                StreamPlayer.Status.CONNECTING -> {
                    show("Connecting…")
                    // Time each connection attempt: connected but no picture = can't play.
                    if (!reported) {
                        handler.removeCallbacks(probeTimeout)
                        handler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS)
                    }
                }
                StreamPlayer.Status.PLAYING -> {
                    status.visibility = View.GONE
                    report(true, false, null)
                }
                StreamPlayer.Status.UNREACHABLE -> {
                    // NVR/camera not reachable right now (e.g. still booting after a
                    // power cut): not a reason to give up on live video. Keep retrying.
                    handler.removeCallbacks(probeTimeout)
                    show("Can't reach camera · retrying\n${reason ?: ""}".trim())
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
    }

    /**
     * Live video through VLC, for a camera the built-in player can't play.
     * If VLC can't play it either, falls back to snapshots.
     */
    fun startVlc(main: Boolean = big) {
        stopAll()
        playerView.visibility = View.GONE
        image.visibility = View.GONE
        vlcView.visibility = View.VISIBLE
        setLabel(camera.name)
        val v = VlcStreamPlayer(context, vlcView, inTile = true, fitMode = config.fitMode) { st, reason ->
            when (st) {
                StreamPlayer.Status.CONNECTING -> show("Connecting…")
                StreamPlayer.Status.PLAYING -> status.visibility = View.GONE
                StreamPlayer.Status.UNREACHABLE -> show("Can't reach camera · retrying\n${reason ?: ""}".trim())
                // 4K didn't play: try the small stream; if VLC can't play that either, pictures.
                StreamPlayer.Status.RETRYING, StreamPlayer.Status.AUTH_FAILED ->
                    handler.post { if (main && st == StreamPlayer.Status.RETRYING) startVlc(main = false) else startSnapshots() }
            }
        }
        vlc = v
        v.play(config.rtspUrl(camera.channel, if (main) 1 else 2))
    }

    /** Near-live JPEG snapshots over HTTP, about one per second. */
    fun startSnapshots() {
        stopAll()
        vlcView.visibility = View.GONE
        playerView.visibility = View.GONE
        image.visibility = View.VISIBLE
        setLabel("${camera.name}  ·  snapshots")
        show("Loading…")
        val streamNo = if (big) 1 else 2
        snapshots = SnapshotFeed(config, camera.channel * 100 + streamNo, if (big) 1920 else 960, image) { err ->
            if (err == null) status.visibility = View.GONE else show(err)
        }.also { it.start() }
    }

    private fun stopAll() {
        handler.removeCallbacks(probeTimeout)
        stream?.release()
        stream = null
        vlc?.release()
        vlc = null
        snapshots?.stop()
        snapshots = null
    }

    fun release() = stopAll()

    /** Sets the camera's label (kept underneath the "Moving" label while moving). */
    private fun setLabel(text: CharSequence) {
        baseLabel = text
        if (!isMoving) label.text = text
    }

    /** Orange frame and a "moving" label while you rearrange this camera. */
    fun setMoving(moving: Boolean) {
        isMoving = moving
        if (moving) {
            label.text = "◀ ▲ ▼ ▶  Moving ${camera.name}"
            view.foreground = context.getDrawable(R.drawable.tile_moving)
        } else {
            label.text = baseLabel
            view.foreground = context.getDrawable(R.drawable.tile_focus)
        }
    }

    companion object {
        const val TAG = "camera_tile"
        private const val PROBE_TIMEOUT_MS = 15_000L
    }
}
