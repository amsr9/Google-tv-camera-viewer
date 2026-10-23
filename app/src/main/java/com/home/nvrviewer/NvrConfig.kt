package com.home.nvrviewer

import android.content.Context

/** One camera channel on the NVR. */
data class Camera(val channel: Int, val name: String)

/** Connection and display settings, stored privately on the TV. */
data class NvrConfig(
    val host: String,
    val httpPort: Int,
    val rtspPort: Int,
    val username: String,
    val password: String,
    /** 0 = auto-detect cameras from the NVR. */
    val manualChannels: Int,
    /** Cameras per row/column: 1, 2, 3 or 4. */
    val gridSize: Int,
    /** Seconds between automatic page changes, 0 = off. */
    val cycleSeconds: Int,
    /** How a picture fills its tile: 0 = fill (trim edges), 1 = stretch, 2 = fit (black bars). */
    val fitMode: Int = FIT_FILL,
) {
    val isComplete: Boolean get() = host.isNotBlank() && username.isNotBlank()

    /**
     * Hikvision NVR stream URL. Channel 3 main stream = 301, sub-stream = 302.
     * stream: 1 = main (full quality), 2 = sub (low resolution, for the grid).
     */
    fun rtspUrl(channel: Int, stream: Int): String {
        val user = encodeUserInfo(username)
        val pass = encodeUserInfo(password)
        return "rtsp://$user:$pass@$host:$rtspPort/Streaming/Channels/${channel * 100 + stream}"
    }

    /**
     * Percent-encodes only what must be encoded in the user:password part of a URL.
     * Characters such as $ ! & * + are allowed as-is (RFC 3986) and are left alone,
     * so the player sends exactly the password the NVR expects.
     */
    private fun encodeUserInfo(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            val safe = c < 0x80 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~!$&'()*+,;=")
            if (safe) append(ch) else append('%').append("%02X".format(c))
        }
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putString(K_HOST, host)
            .putInt(K_HTTP_PORT, httpPort)
            .putInt(K_RTSP_PORT, rtspPort)
            .putString(K_USER, username)
            .putString(K_PASS, password)
            .putInt(K_MANUAL, manualChannels)
            .putInt(K_GRID, gridSize)
            .putInt(K_CYCLE, cycleSeconds)
            .putInt(K_FIT, fitMode)
            .apply()
    }

    companion object {
        private const val PREFS = "nvr"
        private const val K_HOST = "host"
        private const val K_HTTP_PORT = "http_port"
        private const val K_RTSP_PORT = "rtsp_port"
        private const val K_USER = "user"
        private const val K_PASS = "pass"
        private const val K_MANUAL = "manual_channels"
        private const val K_GRID = "grid"
        private const val K_CYCLE = "cycle"
        private const val K_CACHE = "camera_cache"
        private const val K_FIT = "fit"

        const val FIT_FILL = 0
        const val FIT_STRETCH = 1
        const val FIT_BARS = 2

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): NvrConfig {
            val p = prefs(context)
            return NvrConfig(
                host = p.getString(K_HOST, "") ?: "",
                httpPort = p.getInt(K_HTTP_PORT, 80),
                rtspPort = p.getInt(K_RTSP_PORT, 554),
                username = p.getString(K_USER, "") ?: "",
                password = p.getString(K_PASS, "") ?: "",
                manualChannels = p.getInt(K_MANUAL, 0),
                gridSize = p.getInt(K_GRID, 2),
                cycleSeconds = p.getInt(K_CYCLE, 0),
                fitMode = p.getInt(K_FIT, FIT_FILL),
            )
        }

        /** Last successful camera list, used if the NVR can't be queried at startup. */
        fun saveCameraCache(context: Context, cameras: List<Camera>) {
            val text = cameras.joinToString("\n") { "${it.channel}|${it.name.replace("\n", " ")}" }
            prefs(context).edit().putString(K_CACHE, text).apply()
        }

        fun loadCameraCache(context: Context): List<Camera> {
            val text = prefs(context).getString(K_CACHE, null) ?: return emptyList()
            return text.lines().mapNotNull { line ->
                val sep = line.indexOf('|')
                if (sep <= 0) return@mapNotNull null
                val ch = line.substring(0, sep).toIntOrNull() ?: return@mapNotNull null
                Camera(ch, line.substring(sep + 1))
            }
        }

        fun clearCameraCache(context: Context) {
            prefs(context).edit().remove(K_CACHE).apply()
        }
    }
}
