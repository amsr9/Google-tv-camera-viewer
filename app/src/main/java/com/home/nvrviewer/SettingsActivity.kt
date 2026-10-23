package com.home.nvrviewer

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * NVR address, login and display options, laid out for a TV remote:
 * text fields for the login, ◀ ▶ choices for everything else.
 */
class SettingsActivity : Activity() {

    private lateinit var host: EditText
    private lateinit var httpPort: EditText
    private lateinit var rtspPort: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var layoutOpt: OptionSelector
    private lateinit var grid: OptionSelector
    private lateinit var fit: OptionSelector
    private lateinit var cycle: OptionSelector
    private lateinit var manual: OptionSelector
    private lateinit var result: TextView

    private lateinit var original: NvrConfig
    private var backPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        host = findViewById(R.id.f_host)
        httpPort = findViewById(R.id.f_http_port)
        rtspPort = findViewById(R.id.f_rtsp_port)
        user = findViewById(R.id.f_user)
        pass = findViewById(R.id.f_pass)
        result = findViewById(R.id.result)

        val c = NvrConfig.load(this)
        original = c
        host.setText(c.host)
        httpPort.setText(c.httpPort.toString())
        rtspPort.setText(c.rtspPort.toString())
        user.setText(c.username)
        pass.setText(c.password)

        layoutOpt = OptionSelector(
            findViewById(R.id.o_layout), "Layout",
            listOf(NvrConfig.LAYOUT_FEATURED, NvrConfig.LAYOUT_GRID), c.layout,
        ) {
            if (it == NvrConfig.LAYOUT_FEATURED) "1 big + 5 small (nothing trimmed)" else "Equal tiles"
        }
        grid = OptionSelector(findViewById(R.id.o_grid), "Cameras per row (equal tiles)", listOf(1, 2, 3, 4), c.gridSize) {
            if (it == 1) "1  (one camera at a time)" else "$it  (up to ${it * it} on screen)"
        }
        fit = OptionSelector(
            findViewById(R.id.o_fit), "Picture",
            listOf(NvrConfig.FIT_FILL, NvrConfig.FIT_STRETCH, NvrConfig.FIT_BARS), c.fitMode,
        ) {
            when (it) {
                NvrConfig.FIT_STRETCH -> "Stretch to fill"
                NvrConfig.FIT_BARS -> "Whole picture (black bars)"
                else -> "Fill tile (trims edges)"
            }
        }
        cycle = OptionSelector(
            findViewById(R.id.o_cycle), "Switch pages automatically",
            listOf(0, 10, 15, 20, 30, 60, 120), c.cycleSeconds,
        ) { if (it == 0) "Off" else "every $it seconds" }
        manual = OptionSelector(
            findViewById(R.id.o_manual), "Cameras", (0..32).toList(), c.manualChannels,
        ) { if (it == 0) "Find automatically" else "$it (set by hand)" }

        findViewById<Button>(R.id.btn_test).setOnClickListener { test() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_reset_order).setOnClickListener {
            NvrConfig.clearCameraOrder(this)
            Toast.makeText(this, "Cameras will be shown in the NVR's order", Toast.LENGTH_SHORT).show()
        }

        // First run: start at the IP address. Afterwards the login rarely changes, so
        // start at the display options (▲ still reaches the login fields).
        if (c.isComplete) findViewById<Button>(R.id.o_layout).requestFocus() else host.requestFocus()
    }

    private fun readForm(showErrors: Boolean = true): NvrConfig? {
        fun num(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def
        fun error(msg: String): NvrConfig? {
            if (showErrors) result.text = msg
            return null
        }

        val h = host.text.toString().trim()
            .removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (h.isEmpty()) return error("Enter the NVR's IP address, for example 192.168.100.30")
        if (user.text.isBlank()) return error("Enter a username.")

        return NvrConfig(
            host = h,
            httpPort = num(httpPort, 80),
            rtspPort = num(rtspPort, 554),
            username = user.text.toString().trim(),
            password = pass.text.toString(),
            manualChannels = manual.value,
            gridSize = grid.value,
            cycleSeconds = cycle.value,
            fitMode = fit.value,
            layout = layoutOpt.value,
        )
    }

    private fun test() {
        val cfg = readForm() ?: return
        NvrClient.clearAuthBlock()
        result.text = "Testing ${cfg.host}…"
        Thread {
            val r = runCatching { NvrClient.cameras(cfg) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.text = r.fold(
                    onSuccess = { list ->
                        "✓ Connected. Found ${list.size} cameras:\n" +
                            list.joinToString("\n") { "  ${it.channel}. ${it.name}" } +
                            "\n\nSelect Save to start watching."
                    },
                    onFailure = { e -> "✗ ${e.message}" },
                )
            }
        }.start()
    }

    private fun save() {
        val cfg = readForm() ?: return
        if (original.host != cfg.host || original.manualChannels != cfg.manualChannels) {
            NvrConfig.clearCameraCache(this)
        }
        cfg.save(this)
        NvrClient.clearAuthBlock()
        finish()
    }

    /** Back with unsaved changes asks once before throwing them away. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val current = readForm(showErrors = false)
        val changed = current != null && current != original
        val now = System.currentTimeMillis()
        if (changed && now - backPressedAt > 3_000) {
            backPressedAt = now
            Toast.makeText(this, "You have unsaved changes. Press Back again to discard them, or select Save.", Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
