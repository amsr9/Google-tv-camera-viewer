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
    /** LAYOUT_GRID = equal tiles ([gridSize] per row), LAYOUT_FEATURED = 1 large + 5 small. */
    val layout: Int = LAYOUT_GRID,
    /** What makes a camera pop up full screen: POPUP_OFF / PEOPLE / PEOPLE_VEHICLES / ANY_MOTION. */
    val popupMode: Int = POPUP_PEOPLE,
    /** How long a pop-up stays before returning to the grid. */
    val popupSeconds: Int = 20,
    val chime: Boolean = true,
    /** Pop up even while another app (TV, YouTube…) is on screen. */
    val popupOverApps: Boolean = false,
    /** Open the camera wall when the TV starts or wakes up. */
    val autoStart: Boolean = false,
) {
    /** Cameras on one screen. */
    val perPage: Int get() = if (layout == LAYOUT_FEATURED) 6 else gridSize * gridSize

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
            .putInt(K_LAYOUT, layout)
            .putInt(K_POPUP, popupMode)
            .putInt(K_POPUP_SECS, popupSeconds)
            .putBoolean(K_CHIME, chime)
            .putBoolean(K_POPUP_OVER, popupOverApps)
            .putBoolean(K_AUTOSTART, autoStart)
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
        private const val K_LAYOUT = "layout"

        const val LAYOUT_GRID = 0
        const val LAYOUT_FEATURED = 1

        private const val K_POPUP = "popup"
        private const val K_POPUP_SECS = "popup_secs"
        private const val K_CHIME = "chime"
        private const val K_POPUP_OVER = "popup_over"
        private const val K_AUTOSTART = "autostart"

        const val POPUP_OFF = 0
        const val POPUP_PEOPLE = 1
        const val POPUP_PEOPLE_VEHICLES = 2
        const val POPUP_ANY_MOTION = 3

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
                layout = p.getInt(K_LAYOUT, LAYOUT_GRID),
                popupMode = p.getInt(K_POPUP, POPUP_PEOPLE),
                popupSeconds = p.getInt(K_POPUP_SECS, 20),
                chime = p.getBoolean(K_CHIME, true),
                popupOverApps = p.getBoolean(K_POPUP_OVER, false),
                autoStart = p.getBoolean(K_AUTOSTART, false),
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

        private const val K_ORDER = "camera_order"

        /** The order you arranged the cameras in (list of channel numbers). */
        fun saveCameraOrder(context: Context, cameras: List<Camera>) {
            prefs(context).edit().putString(K_ORDER, cameras.joinToString(",") { it.channel.toString() }).apply()
        }

        fun clearCameraOrder(context: Context) {
            prefs(context).edit().remove(K_ORDER).apply()
        }

        /**
         * Puts cameras in your saved order. Cameras not in the saved order (e.g. newly
         * added to the NVR) go at the end, in the NVR's order.
         */
        fun applyCameraOrder(context: Context, cameras: List<Camera>): List<Camera> {
            val saved = prefs(context).getString(K_ORDER, null)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return cameras
            val rank = saved.withIndex().associate { (i, ch) -> ch to i }
            return cameras.sortedBy { rank[it.channel] ?: (saved.size + cameras.indexOf(it)) }
        }

        fun clearCameraCache(context: Context) {
            prefs(context).edit().remove(K_CACHE).apply()
        }
    }
}
