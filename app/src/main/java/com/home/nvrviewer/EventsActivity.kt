package com.home.nvrviewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recent detections, newest first. Select one to watch the recording from
 * 10 seconds before it. ◀ ▶ on the filter picks one camera or all.
 */
class EventsActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var warnings: TextView
    private lateinit var filter: OptionSelector
    private var cameras: List<Camera> = emptyList()
    private var shown: List<AlertEvent> = emptyList()
    private val app get() = application as App
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_events)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        warnings = findViewById(R.id.warnings)

        cameras = NvrConfig.applyCameraOrder(this, NvrConfig.loadCameraCache(this))
        // 0 = all cameras, otherwise a channel number.
        filter = OptionSelector(findViewById(R.id.o_filter), "Show", listOf(0) + cameras.map { it.channel }, 0) { ch ->
            if (ch == 0) "All cameras" else nameOf(ch)
        }
        filter.onChange = { render() }

        list.setOnItemClickListener { _, _, position, _ ->
            val ev = shown.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, PlaybackActivity::class.java)
                    .putExtra(PlaybackActivity.EXTRA_CHANNEL, ev.channel)
                    .putExtra(PlaybackActivity.EXTRA_NAME, nameOf(ev.channel))
                    .putExtra(PlaybackActivity.EXTRA_TIME, ev.timeMs - 10_000),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        app.addListener(refresh)
        render()
        list.requestFocus()
    }

    override fun onStop() {
        super.onStop()
        app.removeListener(refresh)
    }

    private fun nameOf(channel: Int) = cameras.firstOrNull { it.channel == channel }?.name ?: "Camera $channel"

    private fun render() {
        val w = app.warnings
        warnings.visibility = if (w.isEmpty()) View.GONE else View.VISIBLE
        warnings.text = w.joinToString("\n") { "⚠  $it" }

        val ch = filter.value
        shown = app.eventLog.recent().filter { ch == 0 || it.channel == ch }
        val day = SimpleDateFormat("EEE d MMM", Locale.US)
        val time = SimpleDateFormat("h:mm:ss a", Locale.US)
        val rows = shown.map { e ->
            val d = Date(e.timeMs)
            "${day.format(d).padEnd(11)}  ${time.format(d).padStart(11)}   ${nameOf(e.channel).take(22).padEnd(22)}  ${e.what}"
        }
        val keep = list.selectedItemPosition // stay on the same row when a new event arrives
        val added = rows.size - (list.adapter?.count ?: rows.size)
        list.adapter = ArrayAdapter(this, R.layout.item_event, rows)
        if (keep > 0) list.setSelection((keep + added.coerceAtLeast(0)).coerceAtMost(rows.size - 1))
        val none = rows.isEmpty()
        list.visibility = if (none) View.GONE else View.VISIBLE
        empty.visibility = if (none) View.VISIBLE else View.GONE
        empty.text = "No events yet.\n\n" +
            "Detections are listed here while this app is running on the TV.\n" +
            "If nothing ever appears: in the NVR's web page, open each camera's event\n" +
            "(Motion Detection / Intrusion / Line Crossing) → Linkage Action, and tick\n" +
            "“Notify Surveillance Center”."
        if (none) findViewById<View>(R.id.o_filter).requestFocus()
    }
}
