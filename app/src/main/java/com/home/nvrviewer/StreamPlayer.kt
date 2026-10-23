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
    /** reason is a short description of the error for RETRYING / AUTH_FAILED. */
    private val onStatus: (status: Status, reason: String?) -> Unit,
) {
    enum class Status { CONNECTING, PLAYING, RETRYING, AUTH_FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var retryDelayMs = INITIAL_RETRY_MS

    private val retry = Runnable {
        player?.let {
            onStatus(Status.CONNECTING, null)
            it.stop()
            it.prepare()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    retryDelayMs = INITIAL_RETRY_MS
                    onStatus(Status.PLAYING, null)
                }
                Player.STATE_ENDED -> { // a live stream should never "end"
                    onStatus(Status.RETRYING, "stream ended")
                    scheduleRetry(retryDelayMs)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = describe(error)
            if (isAuthError(error)) {
                onStatus(Status.AUTH_FAILED, reason)
                scheduleRetry(AUTH_RETRY_MS) // slow: each attempt counts as a failed login
            } else {
                onStatus(Status.RETRYING, reason)
                scheduleRetry(retryDelayMs)
            }
        }
    }

    fun play(url: String) {
        release()
        onStatus(Status.CONNECTING, null)

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
            .setForceUseRtpTcp(true) // more reliable than UDP, and avoids a known Hikvision bug
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()

        playerView.player = p
        player = p
    }

    private fun scheduleRetry(delayMs: Long) {
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
        if (delayMs == retryDelayMs) retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
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
        const val AUTH_RETRY_MS = 120_000L

        fun causes(e: Throwable): Sequence<Throwable> =
            generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(8)

        fun isAuthError(e: PlaybackException): Boolean =
            causes(e).any { t -> t.message?.let { "401" in it || "Unauthorized" in it } == true }

        /** e.g. "ERROR_CODE_IO_UNSPECIFIED: DESCRIBE 401" */
        fun describe(e: PlaybackException): String {
            val detail = causes(e).mapNotNull { it.message?.takeIf(String::isNotBlank) }.lastOrNull()
            val name = e.errorCodeName.removePrefix("ERROR_CODE_")
            return (if (detail != null) "$name: $detail" else name).take(140)
        }
    }
}
