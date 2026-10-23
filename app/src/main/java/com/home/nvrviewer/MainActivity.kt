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
class MainActivity : Activity(), CameraTile.Listener {

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
    private var backPressedAt = 0L

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

        findViewById<Button>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            VideoMode.reset() // try live video again
            loadCameras(forceReload = true)
        }
    }

    override fun onStart() {
        super.onStart()
        val newConfig = NvrConfig.load(this)
        if (!::config.isInitialized || newConfig != config) { // settings changed
            cameras = emptyList()
            VideoMode.reset()
        }
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
            // Forget the old list too, so the answer is always shown, even if unchanged.
            cameras = emptyList()
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

        // Only as many rows as this page needs, so the cameras fill the screen.
        val rows = if (pageCount == 1) (pageCams.size + n - 1) / n else n
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until n) {
                val i = r * n + c
                val cell = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (i < pageCams.size) {
                    val cam = pageCams[i]
                    val tile = CameraTile(this, cam, config, this)
                    val globalIndex = start + i
                    tile.view.setOnClickListener { openFullscreen(globalIndex) }
                    row.addView(tile.view, cell)
                    tiles += tile
                } else {
                    row.addView(View(this), cell)
                }
            }
        }

        startTiles()
        updateLabel()

        val target = focusIndex?.let { it - start }?.takeIf { it in tiles.indices } ?: 0
        tiles.getOrNull(target)?.view?.requestFocus()
        focusIndex = null
        scheduleCycle()
    }

    /** Live video if it works; otherwise snapshots. Tries one camera first. */
    private fun startTiles() {
        when (VideoMode.mode) {
            VideoMode.Mode.LIVE -> tiles.forEach { it.startLive() }
            VideoMode.Mode.SNAPSHOT -> tiles.forEach { it.startSnapshots() }
            VideoMode.Mode.UNKNOWN -> {
                tiles.firstOrNull()?.startLive()
                tiles.drop(1).forEach { it.showWaiting() }
            }
        }
    }

    override fun onLiveResult(tile: CameraTile, ok: Boolean, authFailed: Boolean, reason: String?) {
        if (tile !in tiles) return
        when (VideoMode.mode) {
            VideoMode.Mode.UNKNOWN -> if (ok) {
                VideoMode.mode = VideoMode.Mode.LIVE
                tiles.filter { it !== tile }.forEach { it.startLive() }
            } else if (!authFailed && tiles.indexOf(tile) < tiles.size - 1) {
                // This camera's stream may just be unsupported: show its snapshots
                // and try live video on the next camera.
                tile.startSnapshots()
                tiles[tiles.indexOf(tile) + 1].startLive()
            } else {
                VideoMode.mode = VideoMode.Mode.SNAPSHOT
                VideoMode.reason = reason
                tiles.forEach { it.startSnapshots() }
                Toast.makeText(
                    this,
                    "Live video didn't start (${reason ?: "unknown error"}). Showing snapshots instead.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            // Live works for other cameras but this one couldn't start (e.g. its
            // stream format isn't supported): show snapshots for just this camera.
            VideoMode.Mode.LIVE -> if (!ok) tile.startSnapshots()
            VideoMode.Mode.SNAPSHOT -> Unit
        }
        updateLabel()
    }

    private fun updateLabel() {
        pageLabel.text = buildString {
            append("${cameras.size} cameras")
            if (pageCount > 1) append("   ·   Page ${page + 1} of $pageCount (◀ ▶ at the edge)")
            append("   ·   OK: full screen   ·   ▲: Refresh / Settings")
            if (VideoMode.mode == VideoMode.Mode.SNAPSHOT) {
                append("   ·   Snapshot mode")
                VideoMode.reason?.let { append(" – live video: $it") }
            }
        }
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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Back from the top bar returns to the cameras; otherwise press Back twice
     * to close, so one accidental press doesn't shut the camera wall.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val focused = currentFocus
        if (tiles.isNotEmpty() && focused != null && !isTile(focused)) {
            tiles.first().view.requestFocus()
            return
        }
        val now = System.currentTimeMillis()
        if (tiles.isNotEmpty() && now - backPressedAt > 2_500) {
            backPressedAt = now
            Toast.makeText(this, "Press Back again to close Home Cameras", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openSettings(); return true }
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
