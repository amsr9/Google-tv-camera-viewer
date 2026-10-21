package com.home.nvrviewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Home screen: every camera on the NVR in a grid, page by page.
 * D-pad moves between cameras, OK opens one full screen,
 * pressing right/left past the edge changes page.
 */
class MainActivity : Activity() {

    private lateinit var config: NvrConfig
    private lateinit var grid: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var pageLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val tiles = mutableListOf<CameraTile>()

    private var cameras: List<Camera> = emptyList()
    private var page = 0
    private var loadToken = 0
    private var focusIndex: Int? = null
    private var askedForSettings = false

    private val perPage: Int get() = config.gridSize * config.gridSize
    private val pageCount: Int get() = maxOf(1, (cameras.size + perPage - 1) / perPage)

    private val cycleRunnable = Runnable {
        page = (page + 1) % pageCount
        focusIndex = null
        buildPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        statusText = findViewById(R.id.status)
        pageLabel = findViewById(R.id.page_label)

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { loadCameras(forceReload = true) }
    }

    override fun onStart() {
        super.onStart()
        val newConfig = NvrConfig.load(this)
        if (!::config.isInitialized || newConfig != config) cameras = emptyList() // settings changed
        config = newConfig
        if (!config.isComplete) {
            showStatus("Open Settings to enter your NVR's address and login.")
            findViewById<Button>(R.id.btn_settings).requestFocus()
            if (!askedForSettings) {
                askedForSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        loadCameras()
    }

    override fun onStop() {
        super.onStop()
        loadToken++ // ignore any discovery still in flight
        handler.removeCallbacksAndMessages(null)
        releaseTiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FULLSCREEN && data != null) {
            focusIndex = data.getIntExtra(PlayerActivity.EXTRA_INDEX, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from full screen: highlight the camera that was last shown.
        val f = focusIndex ?: return
        if (tiles.isEmpty()) return // grid still loading; buildPage() will use focusIndex
        val p = (f / perPage).coerceIn(0, pageCount - 1)
        if (p != page) {
            page = p
            buildPage()
        } else {
            tiles.getOrNull(f - page * perPage)?.view?.requestFocus()
            focusIndex = null
        }
    }

    /**
     * Shows the known camera list straight away (if any), then asks the NVR for
     * the current list in the background and updates the grid if it changed.
     */
    private fun loadCameras(forceReload: Boolean = false) {
        val token = ++loadToken
        val cfg = config
        val known = if (forceReload) emptyList() else cameras.ifEmpty { NvrConfig.loadCameraCache(this) }
        if (known.isNotEmpty()) {
            showCameras(known)
        } else {
            releaseTiles()
            grid.removeAllViews()
            showStatus("Connecting to ${cfg.host}…")
        }

        executor.execute {
            val result = runCatching { NvrClient.cameras(cfg) }
            handler.post {
                if (token != loadToken) return@post
                result.onSuccess { list ->
                    NvrConfig.saveCameraCache(this, list)
                    if (list != cameras) showCameras(list)
                }.onFailure { e ->
                    if (cameras.isEmpty()) {
                        showStatus("Couldn't reach the NVR:\n${e.message}")
                        findViewById<Button>(R.id.btn_settings).requestFocus()
                    } else {
                        Toast.makeText(this, "Couldn't refresh camera list: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showCameras(list: List<Camera>) {
        cameras = list
        val f = focusIndex
        page = if (f != null) f / perPage else page
        page = page.coerceIn(0, pageCount - 1)
        buildPage()
    }

    private fun buildPage() {
        releaseTiles()
        grid.removeAllViews()
        if (cameras.isEmpty()) {
            showStatus("No cameras found on the NVR.")
            return
        }
        statusText.visibility = View.GONE

        val n = config.gridSize
        val start = page * perPage
        val pageCams = cameras.subList(start, min(start + perPage, cameras.size))
        // Full screen grid (1×1) uses the main stream; multi-camera grids use the light sub-stream.
        val stream = if (n == 1) 1 else 2

        for (r in 0 until n) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until n) {
                val i = r * n + c
                val cell = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (i < pageCams.size) {
                    val cam = pageCams[i]
                    val tile = CameraTile(this, cam, config.rtspUrl(cam.channel, stream))
                    val globalIndex = start + i
                    tile.view.setOnClickListener { openFullscreen(globalIndex) }
                    row.addView(tile.view, cell)
                    tiles += tile
                    tile.start()
                } else {
                    row.addView(View(this), cell)
                }
            }
        }

        pageLabel.text = buildString {
            append("${cameras.size} cameras")
            if (pageCount > 1) append("   ·   Page ${page + 1} of $pageCount   ·   ◀ ▶ to change page")
        }

        val target = focusIndex?.let { it - start }?.takeIf { it in tiles.indices } ?: 0
        tiles.getOrNull(target)?.view?.requestFocus()
        focusIndex = null
        scheduleCycle()
    }

    private fun releaseTiles() {
        tiles.forEach { it.release() }
        tiles.clear()
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
        pageLabel.text = getString(R.string.app_name)
    }

    private fun openFullscreen(index: Int) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNELS, cameras.map { it.channel }.toIntArray())
            .putExtra(PlayerActivity.EXTRA_NAMES, cameras.map { it.name }.toTypedArray())
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_FULLSCREEN)
    }

    private fun changePage(delta: Int) {
        if (pageCount <= 1) return
        page = (page + delta + pageCount) % pageCount
        focusIndex = null
        buildPage()
    }

    private fun scheduleCycle() {
        handler.removeCallbacks(cycleRunnable)
        if (config.cycleSeconds > 0 && pageCount > 1) {
            handler.postDelayed(cycleRunnable, config.cycleSeconds * 1000L)
        }
    }

    private fun isTile(v: View?) = v?.tag == CameraTile.TAG

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && cameras.isNotEmpty()) {
            scheduleCycle() // any key press postpones auto-cycling
            val focused = currentFocus
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (isTile(focused) && !isTile(focused?.focusSearch(View.FOCUS_RIGHT)) && pageCount > 1) {
                        changePage(+1); return true
                    }
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (isTile(focused) && !isTile(focused?.focusSearch(View.FOCUS_LEFT)) && pageCount > 1) {
                        changePage(-1); return true
                    }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { changePage(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> { changePage(-1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val REQ_FULLSCREEN = 1
    }
}
