package com.home.nvrviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * One live RTSP stream bound to a PlayerView, with low-latency buffering and
 * automatic reconnect when the stream drops.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    private val context: Context,
    private val playerView: PlayerView,
    private val onStatus: (Status) -> Unit,
) {
    enum class Status { CONNECTING, PLAYING, RETRYING }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var retryDelayMs = INITIAL_RETRY_MS

    private val retry = Runnable {
        player?.let {
            onStatus(Status.CONNECTING)
            it.stop()
            it.prepare()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    retryDelayMs = INITIAL_RETRY_MS
                    onStatus(Status.PLAYING)
                }
                Player.STATE_ENDED -> scheduleRetry() // live stream should never "end"
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry()
        }
    }

    fun play(url: String) {
        release()
        onStatus(Status.CONNECTING)

        // Small buffers keep the picture close to real time.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 250, 250)
            .build()

        // TV boxes have only a few hardware video decoders. When they're all busy
        // (e.g. 9-camera grid), fall back to another decoder instead of failing.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
        p.volume = 0f
        p.addListener(listener)

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // more reliable than UDP over Wi-Fi
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()

        playerView.player = p
        player = p
    }

    private fun scheduleRetry() {
        onStatus(Status.RETRYING)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.removeListener(listener)
        player?.release()
        player = null
    }

    private companion object {
        const val INITIAL_RETRY_MS = 3_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
