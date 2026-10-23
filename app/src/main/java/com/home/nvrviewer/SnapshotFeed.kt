package com.home.nvrviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Near-live view: fetches a JPEG snapshot from the NVR over HTTP about once a
 * second and shows it. Used when live RTSP video isn't available.
 */
class SnapshotFeed(
    private val config: NvrConfig,
    private val streamId: Int,
    private val maxWidth: Int,
    private val imageView: ImageView,
    private val onStatus: (error: String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var running = false

    fun start() {
        running = true
        fetch(++generation)
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
    }

    private fun fetch(gen: Int) {
        executor.execute {
            val result = runCatching { decode(NvrClient.snapshot(config, streamId)) }
            handler.post {
                if (!running || gen != generation) return@post
                val bitmap = result.getOrNull()
                val error = result.exceptionOrNull()
                val delay = when {
                    bitmap != null -> {
                        imageView.setImageBitmap(bitmap)
                        onStatus(null)
                        INTERVAL_MS
                    }
                    error is NvrClient.AuthException -> {
                        onStatus("Login refused by NVR – wait 30 min if it's locked")
                        AUTH_RETRY_MS // slow down so we don't extend a lockout
                    }
                    else -> {
                        onStatus("No picture: ${error?.message ?: "unreadable image"}".take(80))
                        ERROR_RETRY_MS
                    }
                }
                handler.postDelayed({ if (running && gen == generation) fetch(gen) }, delay)
            }
        }
    }

    /** Decodes a JPEG, scaled down to about maxWidth to save memory on the TV. */
    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw java.io.IOException("unreadable image")
    }

    private companion object {
        val executor = Executors.newFixedThreadPool(4)
        const val INTERVAL_MS = 700L
        const val ERROR_RETRY_MS = 5_000L
        const val AUTH_RETRY_MS = 60_000L
    }
}
