package com.home.nvrviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
    /**
     * Grid tiles set this: small streams are decoded in software so the TV's few
     * hardware decoders don't run out when many cameras play at once.
     */
    private val preferSoftwareDecoder: Boolean = false,
    /** reason is a short description of the error for RETRYING / AUTH_FAILED. */
    private val onStatus: (status: Status, reason: String?) -> Unit,
) {
    /**
     * UNREACHABLE = network problem (NVR/camera off, booting, Wi-Fi drop): keep retrying.
     * RETRYING    = the stream itself can't be played (format/decoder/404).
     */
    enum class Status { CONNECTING, PLAYING, UNREACHABLE, RETRYING, AUTH_FAILED }

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

    /** Video stuck buffering (connection alive but no pictures): reconnect. */
    private val stallCheck = Runnable {
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_BUFFERING) {
            onStatus(Status.UNREACHABLE, "stream stalled")
            handler.removeCallbacks(retry)
            retry.run()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            handler.removeCallbacks(stallCheck)
            when (state) {
                Player.STATE_READY -> {
                    retryDelayMs = INITIAL_RETRY_MS
                    onStatus(Status.PLAYING, null)
                }
                Player.STATE_BUFFERING -> handler.postDelayed(stallCheck, STALL_TIMEOUT_MS)
                Player.STATE_ENDED -> { // a live stream should never "end"
                    onStatus(Status.RETRYING, "stream ended")
                    scheduleRetry(retryDelayMs)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handler.removeCallbacks(stallCheck)
            val reason = describe(error)
            if (isAuthError(error)) {
                onStatus(Status.AUTH_FAILED, reason)
                scheduleRetry(AUTH_RETRY_MS) // slow: each attempt counts as a failed login
            } else if (isNetworkError(error)) {
                onStatus(Status.UNREACHABLE, reason)
                scheduleRetry(retryDelayMs)
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
        if (preferSoftwareDecoder) renderers.setMediaCodecSelector(SOFTWARE_FIRST)

        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
        p.volume = 0f
        // Cameras' audio is never played: don't spend the TV's CPU decoding it.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
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
        const val STALL_TIMEOUT_MS = 20_000L

        /** Same decoders as normal, but software ones (e.g. c2.android.avc.decoder) first. */
        val SOFTWARE_FIRST = MediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                .sortedBy { if (it.softwareOnly) 0 else 1 }
        }

        fun causes(e: Throwable): Sequence<Throwable> =
            generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(8)

        /** Connection refused/timed out/dropped, host unreachable: a temporary problem. */
        fun isNetworkError(e: PlaybackException): Boolean =
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                causes(e).any {
                    it is java.net.SocketException || it is java.net.SocketTimeoutException ||
                        it is java.net.UnknownHostException || it is java.io.EOFException
                }

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
