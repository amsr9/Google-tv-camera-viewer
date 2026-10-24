package com.home.nvrviewer

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One detection reported by the NVR.
 * type: Hikvision event type (VMD = motion, fielddetection = intrusion, linedetection…)
 * target: "human" / "vehicle" when the NVR's AcuSense says what it saw, else null.
 */
data class AlertEvent(
    val timeMs: Long,
    var lastMs: Long,
    val channel: Int,
    val type: String,
    val target: String?,
) {
    val isPerson: Boolean get() = target.equals("human", ignoreCase = true)
    val isVehicle: Boolean get() = target.equals("vehicle", ignoreCase = true)

    /** "Person", "Vehicle", "Motion", "Line crossing"… */
    val what: String
        get() = when {
            isPerson -> "Person"
            isVehicle -> "Vehicle"
            else -> when (type.lowercase(Locale.US)) {
                "vmd" -> "Motion"
                "fielddetection" -> "Intrusion"
                "linedetection" -> "Line crossing"
                "regionentrance" -> "Entering area"
                "regionexiting" -> "Leaving area"
                "pir" -> "PIR motion"
                "shelteralarm", "tamperdetection" -> "Camera covered"
                else -> type
            }
        }

    /** Should this event pop the camera up, given the user's choice? */
    fun triggersPopup(mode: Int): Boolean = when (mode) {
        NvrConfig.POPUP_PEOPLE -> isPerson
        NvrConfig.POPUP_PEOPLE_VEHICLES -> isPerson || isVehicle
        NvrConfig.POPUP_ANY_MOTION -> true
        else -> false
    }

    fun timeText(): String = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(timeMs))
}

/**
 * Recent events, newest last. Repeats from the same camera (the NVR reports
 * ongoing motion every second) are merged into one entry. Kept for 7 days in a
 * small file so the list survives restarts.
 */
class EventLog(private val file: File) {
    private val events = mutableListOf<AlertEvent>()
    private val io = Executors.newSingleThreadExecutor()

    init {
        runCatching {
            if (file.exists()) file.readLines().mapNotNullTo(events) { parse(it) }
        }
        trim()
    }

    /** Adds an event; returns false if it was merged into one already listed. */
    @Synchronized
    fun add(ev: AlertEvent): Boolean {
        val last = events.lastOrNull {
            it.channel == ev.channel && it.type == ev.type && it.target == ev.target
        }
        if (last != null && ev.timeMs - last.lastMs < MERGE_MS) {
            last.lastMs = ev.timeMs
            return false
        }
        events += ev
        trim()
        save()
        return true
    }

    /** Newest first. */
    @Synchronized
    fun recent(): List<AlertEvent> = events.asReversed().toList()

    private fun trim() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        events.removeAll { it.timeMs < cutoff }
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    private fun save() {
        val text = events.joinToString("\n") { "${it.timeMs}|${it.lastMs}|${it.channel}|${it.type}|${it.target ?: ""}" }
        io.execute { runCatching { file.writeText(text) } }
    }

    private fun parse(line: String): AlertEvent? {
        val p = line.split('|')
        if (p.size < 5) return null
        return AlertEvent(
            timeMs = p[0].toLongOrNull() ?: return null,
            lastMs = p[1].toLongOrNull() ?: return null,
            channel = p[2].toIntOrNull() ?: return null,
            type = p[3],
            target = p[4].ifBlank { null },
        )
    }

    private companion object {
        const val MERGE_MS = 30_000L
        const val KEEP_MS = 7 * 24 * 3600_000L
        const val MAX_EVENTS = 3000
    }
}
