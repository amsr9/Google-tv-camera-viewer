package com.home.nvrviewer

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** NVR address, login and display options. "Test" checks the login and lists cameras. */
class SettingsActivity : Activity() {

    private lateinit var host: EditText
    private lateinit var httpPort: EditText
    private lateinit var rtspPort: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var manual: EditText
    private lateinit var grid: EditText
    private lateinit var cycle: EditText
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        host = findViewById(R.id.f_host)
        httpPort = findViewById(R.id.f_http_port)
        rtspPort = findViewById(R.id.f_rtsp_port)
        user = findViewById(R.id.f_user)
        pass = findViewById(R.id.f_pass)
        manual = findViewById(R.id.f_manual)
        grid = findViewById(R.id.f_grid)
        cycle = findViewById(R.id.f_cycle)
        result = findViewById(R.id.result)

        val c = NvrConfig.load(this)
        host.setText(c.host)
        httpPort.setText(c.httpPort.toString())
        rtspPort.setText(c.rtspPort.toString())
        user.setText(c.username)
        pass.setText(c.password)
        manual.setText(c.manualChannels.toString())
        grid.setText(c.gridSize.toString())
        cycle.setText(c.cycleSeconds.toString())

        findViewById<Button>(R.id.btn_test).setOnClickListener { test() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        host.requestFocus()
    }

    private fun readForm(): NvrConfig? {
        fun num(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def

        val h = host.text.toString().trim()
            .removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (h.isEmpty()) { result.text = "Enter the NVR's IP address, e.g. 192.168.1.64"; return null }
        if (user.text.isBlank()) { result.text = "Enter a username."; return null }

        val g = num(grid, 2)
        if (g !in 1..4) { result.text = "Grid size must be 1, 2, 3 or 4."; return null }

        return NvrConfig(
            host = h,
            httpPort = num(httpPort, 80),
            rtspPort = num(rtspPort, 554),
            username = user.text.toString().trim(),
            password = pass.text.toString(),
            manualChannels = num(manual, 0).coerceIn(0, 128),
            gridSize = g,
            cycleSeconds = num(cycle, 0).coerceAtLeast(0),
        )
    }

    private fun test() {
        val cfg = readForm() ?: return
        result.text = "Testing ${cfg.host}…"
        Thread {
            val r = runCatching { NvrClient.cameras(cfg) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.text = r.fold(
                    onSuccess = { list ->
                        "✓ Found ${list.size} cameras:\n" +
                            list.joinToString("\n") { "  ${it.channel}. ${it.name}" }
                    },
                    onFailure = { e -> "✗ ${e.message}" },
                )
            }
        }.start()
    }

    private fun save() {
        val cfg = readForm() ?: return
        val old = NvrConfig.load(this)
        if (old.host != cfg.host || old.manualChannels != cfg.manualChannels) {
            NvrConfig.clearCameraCache(this)
        }
        cfg.save(this)
        finish()
    }
}
