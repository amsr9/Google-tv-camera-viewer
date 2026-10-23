package com.home.nvrviewer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Player using VLC, which plays streams the built-in player rejects: Hikvision's
 * 4K H.265 main stream (setup line "fmtp" missing) and cameras like Kitchen
 * (setup detail "sprop" missing). Used for full screen, and for any grid tile
 * the built-in player can't play. Reports the same statuses as [StreamPlayer].
 *
 * inTile: true inside a grid tile – draws on a TextureView so the picture is
 * trimmed to the tile, and fills the tile the way Settings → Picture says.
 */
class VlcStreamPlayer(
    context: Context,
    private val layout: VLCVideoLayout,
    private val inTile: Boolean = false,
    private val fitMode: Int = NvrConfig.FIT_BARS,
    private val onStatus: (status: StreamPlayer.Status, reason: String?) -> Unit,
) {
    private val libVlc = shared(context)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var url: String? = null
    private var showingVideo = false
    private var everPlayed = false
    private var retryDelayMs = INITIAL_RETRY_MS

    /** Connected but no picture after this long: this stream can't be played here. */
    private val startTimeout = Runnable {
        if (!showingVideo) fail(everPlayed, "no video after ${START_TIMEOUT_MS / 1000} s")
    }

    private val retry = Runnable { url?.let { start(it) } }

    fun play(url: String) {
        this.url = url
        everPlayed = false
        retryDelayMs = INITIAL_RETRY_MS
        start(url)
    }

    private fun start(url: String) {
        stopPlayer()
        showingVideo = false
        onStatus(StreamPlayer.Status.CONNECTING, null)

        val p = MediaPlayer(libVlc)
        p.attachViews(layout, null, false, inTile)
        p.setVideoScale(
            when (fitMode) {
                NvrConfig.FIT_FILL -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN // fill, trim edges
                NvrConfig.FIT_STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT // whole picture
            },
        )
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false) // use the TV's hardware decoder for 4K
        media.addOption(":network-caching=600")
        p.setMedia(media)
        media.release()
        p.setEventListener { event -> handler.post { onEvent(p, event) } }
        p.play()
        player = p
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    private fun onEvent(p: MediaPlayer, event: MediaPlayer.Event) {
        if (p !== player) return // an old player that is being released
        when (event.type) {
            MediaPlayer.Event.Vout -> if (event.voutCount > 0 && !showingVideo) {
                showingVideo = true
                everPlayed = true
                retryDelayMs = INITIAL_RETRY_MS
                handler.removeCallbacks(startTimeout)
                onStatus(StreamPlayer.Status.PLAYING, null)
            }
            MediaPlayer.Event.EncounteredError ->
                fail(everPlayed, "VLC could not play the stream")
            MediaPlayer.Event.EndReached ->
                fail(true, "stream ended") // a live stream ending = connection dropped
        }
    }

    /**
     * transient = it worked before (connection dropped): reconnect.
     * Otherwise report it as unplayable so the screen can switch to snapshots.
     */
    private fun fail(transient: Boolean, reason: String) {
        handler.removeCallbacks(startTimeout)
        if (transient) {
            onStatus(StreamPlayer.Status.UNREACHABLE, reason)
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        } else {
            stopPlayer()
            onStatus(StreamPlayer.Status.RETRYING, reason)
        }
    }

    private fun stopPlayer() {
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(retry)
        player?.let {
            it.setEventListener(null)
            it.stop()
            it.detachViews()
            it.release()
        }
        player = null
    }

    /** Stops playback; the player can be used again with [play]. */
    fun release() {
        url = null
        stopPlayer()
    }

    /** Call when the screen is destroyed. (VLC itself is shared and stays loaded.) */
    fun destroy() = release()

    private companion object {
        private var sharedVlc: LibVLC? = null

        /** One VLC engine for the whole app: starting it is slow and uses memory. */
        fun shared(context: Context): LibVLC = sharedVlc ?: LibVLC(
            context.applicationContext,
            arrayListOf(
                "--rtsp-tcp",            // reliable over Wi-Fi, same as the grid
                "--network-caching=600", // small buffer: close to real time
                "--no-audio",            // camera audio is never played
                "--drop-late-frames",
                "--skip-frames",
            ),
        ).also { sharedVlc = it }

        const val START_TIMEOUT_MS = 15_000L
        const val INITIAL_RETRY_MS = 3_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
