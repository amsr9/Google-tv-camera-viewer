package com.home.nvrviewer

import android.content.Context
import org.videolan.libvlc.LibVLC

/** One VLC engine for the whole app (full screen, grid tiles, playback): starting it is slow. */
object VlcEngine {
    private var vlc: LibVLC? = null

    fun get(context: Context): LibVLC = vlc ?: LibVLC(
        context.applicationContext,
        arrayListOf(
            "--rtsp-tcp",            // reliable over Wi-Fi, same as the grid
            "--network-caching=600", // small buffer: close to real time
            "--no-audio",            // camera audio is never played
            "--drop-late-frames",
            "--skip-frames",
        ),
    ).also { vlc = it }
}
