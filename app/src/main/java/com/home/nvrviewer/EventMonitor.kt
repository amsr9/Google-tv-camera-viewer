package com.home.nvrviewer

import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Listens to the NVR's live event stream (/ISAPI/Event/notification/alertStream)
 * and reports each detection on the main thread. Reconnects by itself.
 *
 * The NVR only sends an event here if that camera's event has
 * "Notify Surveillance Center" ticked under Linkage Action.
 */
class EventMonitor(
    private val config: NvrConfig,
    private val onEvent: (AlertEvent) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "nvr-events").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { conn?.disconnect() }
    }

    private fun loop() {
        var backoff = 5_000L
        while (running) {
            var wait: Long
            try {
                // The NVR sends a heartbeat at least every ~10 s; 2 min without data = reconnect.
                val c = NvrClient.openAuthed(config, "/ISAPI/Event/notification/alertStream", readTimeoutMs = 120_000)
                conn = c
                backoff = 5_000L
                c.inputStream.use { read(it) }
                wait = backoff
            } catch (e: NvrClient.AuthException) {
                wait = 10 * 60_000L // wrong password: don't hammer the NVR (account lock)
            } catch (e: Exception) {
                wait = backoff // network drop, NVR rebooting… try again
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                runCatching { conn?.disconnect() }
                conn = null
            }
            if (!running) break
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** The stream is multipart: XML event blocks, sometimes with JPEG pictures between them. */
    private fun read(input: InputStream) {
        val buf = ByteArray(16 * 1024)
        val sb = StringBuilder()
        while (running) {
            val n = input.read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            while (true) {
                val start = sb.indexOf(OPEN)
                if (start < 0) {
                    if (sb.length > 4096) sb.delete(0, sb.length - 64) // picture data: drop it
                    break
                }
                val end = sb.indexOf(CLOSE, start)
                if (end < 0) {
                    if (start > 0) sb.delete(0, start)
                    if (sb.length > 1_000_000) sb.setLength(0)
                    break
                }
                val xml = sb.substring(start, end + CLOSE.length)
                sb.delete(0, end + CLOSE.length)
                parse(xml)?.let { ev -> main.post { if (running) onEvent(ev) } }
            }
        }
    }

    companion object {
        private const val OPEN = "<EventNotificationAlert"
        private const val CLOSE = "</EventNotificationAlert>"

        private fun tag(xml: String, name: String): String? =
            Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

        /** Turns one event block into an [AlertEvent]; ignores heartbeats and "ended" messages. */
        internal fun parse(xml: String): AlertEvent? {
            val type = tag(xml, "eventType") ?: return null
            val state = tag(xml, "eventState") ?: "active"
            if (!state.equals("active", ignoreCase = true)) return null
            if (type.equals("videoloss", ignoreCase = true)) return null // also used as heartbeat
            val channel = (tag(xml, "channelID") ?: tag(xml, "dynChannelID") ?: tag(xml, "inputIOPortID"))
                ?.toIntOrNull() ?: return null
            val target = tag(xml, "targetType")
                ?: Regex("\\b(human|vehicle)\\b", RegexOption.IGNORE_CASE).find(xml)?.value?.lowercase()
            val now = System.currentTimeMillis()
            return AlertEvent(now, now, channel, type, target)
        }
    }
}
