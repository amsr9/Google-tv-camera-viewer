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

    /**
     * Cameras whose full-quality (4K main) stream couldn't be played. Full screen
     * shows snapshots for them straight away instead of trying again each time.
     */
    val fullScreenFailed: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())

    fun reset() {
        mode = Mode.UNKNOWN
        reason = null
        fullScreenFailed.clear()
    }
}
