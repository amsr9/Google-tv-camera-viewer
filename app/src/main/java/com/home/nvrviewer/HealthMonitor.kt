package com.home.nvrviewer

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Every 2 minutes: is the NVR reachable, is its hard drive working (no drive =
 * nothing is being recorded), and is every camera online? Reports a list of
 * warnings (empty = all fine) on the main thread whenever it changes.
 */
class HealthMonitor(
    private val config: NvrConfig,
    private val cameras: () -> List<Camera>,
    private val onChange: (List<String>) -> Unit,
) {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var task: ScheduledFuture<*>? = null
    private var last: List<String>? = null
    private var failures = 0

    fun start() {
        if (task != null) return
        task = exec.scheduleWithFixedDelay({ runCatching { check() } }, 20, 120, TimeUnit.SECONDS)
    }

    fun stop() {
        task?.cancel(true)
        task = null
        exec.shutdownNow()
    }

    private fun check() {
        val warnings = mutableListOf<String>()
        try {
            NvrClient.getBytes(config, "/ISAPI/System/deviceInfo")
            failures = 0
        } catch (e: NvrClient.AuthException) {
            report(listOf("NVR refused the login – check the password in Settings"))
            return
        } catch (e: Exception) {
            // One missed check can be a Wi-Fi blip; warn after two in a row.
            if (++failures >= 2) report(listOf("NVR not reachable – cameras may not be recording"))
            return
        }

        runCatching { NvrClient.getBytes(config, "/ISAPI/ContentMgmt/Storage").toString(Charsets.UTF_8) }
            .onSuccess { warnings += storageWarnings(it) }

        runCatching {
            NvrClient.parseOnlineStatus(
                NvrClient.getBytes(config, "/ISAPI/ContentMgmt/InputProxy/channels/status").toString(Charsets.UTF_8),
            )
        }.onSuccess { online ->
            cameras().filter { online[it.channel] == false }.forEach { warnings += "${it.name} offline" }
        }
        report(warnings)
    }

    private fun report(warnings: List<String>) {
        if (warnings == last) return
        last = warnings
        main.post { onChange(warnings) }
    }

    companion object {
        /** Drive states that mean recording is not happening (or soon won't). */
        private val BAD = setOf(
            "error", "unformatted", "uninitialized", "mismatch", "offline", "smartfailed",
            "notexist", "unrecordhostformatted", "abnormal",
        )

        internal fun storageWarnings(xml: String): List<String> {
            val drives = Regex("<hdd[ >][\\s\\S]*?</hdd>").findAll(xml).map { it.value }.toList()
            if (drives.isEmpty()) {
                return if (xml.contains("<hddList", ignoreCase = true) || xml.contains("<storage", ignoreCase = true))
                    listOf("No hard drive in the NVR – nothing is being recorded") else emptyList()
            }
            return drives.mapNotNull { d ->
                val id = Regex("<id>([^<]*)</id>").find(d)?.groupValues?.get(1) ?: "?"
                val status = Regex("<status>([^<]*)</status>").find(d)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                if (status.lowercase() in BAD) "Hard drive $id: $status – recordings may have stopped" else null
            }
        }
    }
}
