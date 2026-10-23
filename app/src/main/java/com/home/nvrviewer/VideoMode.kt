package com.home.nvrviewer

/**
 * Whether live RTSP video works with this NVR. Decided once by trying a single
 * camera first, so a refused login can't be retried by every camera at once
 * (Hikvision locks the account after several failed logins).
 */
object VideoMode {
    enum class Mode { UNKNOWN, LIVE, SNAPSHOT }

    @Volatile var mode: Mode = Mode.UNKNOWN
    @Volatile var reason: String? = null

    fun reset() {
        mode = Mode.UNKNOWN
        reason = null
    }
}
