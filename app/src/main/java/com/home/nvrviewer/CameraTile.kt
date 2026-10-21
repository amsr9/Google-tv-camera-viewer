package com.home.nvrviewer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.media3.ui.PlayerView

/** One cell in the grid: live video, camera name and a connection status label. */
class CameraTile(context: Context, val camera: Camera, private val url: String) {

    val view: View = LayoutInflater.from(context).inflate(R.layout.item_tile, null, false)
    private val label: TextView = view.findViewById(R.id.label)
    private val status: TextView = view.findViewById(R.id.tile_status)
    private val stream = StreamPlayer(context, view.findViewById<PlayerView>(R.id.player_view)) { s ->
        when (s) {
            StreamPlayer.Status.CONNECTING -> show("Connecting…")
            StreamPlayer.Status.RETRYING -> show("Offline · retrying")
            StreamPlayer.Status.PLAYING -> status.visibility = View.GONE
        }
    }

    init {
        view.tag = TAG
        label.text = camera.name
    }

    private fun show(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    fun start() = stream.play(url)

    fun release() = stream.release()

    companion object {
        const val TAG = "camera_tile"
    }
}
