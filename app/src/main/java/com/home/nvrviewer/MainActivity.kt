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

    // Rearranging: the camera being moved, and the order to go back to on Back.
    private var cameraGrid: CameraGridLayout? = null
    /** Where each camera on this page sits: tiles[i] is drawn at slots[i]. */
    private var slots: List<CameraGridLayout.Slot> = emptyList()
    private val featured: Boolean get() = config.layout == NvrConfig.LAYOUT_FEATURED

    private lateinit var topBar: View
    private lateinit var btnLayout: Button
    private val hideBar = Runnable {
        if (tiles.isEmpty() || topBar.findFocus() != null) return@Runnable
        topBar.animate().alpha(0f).setDuration(300).withEndAction { topBar.visibility = View.GONE }.start()
    }
    private var moving: CameraTile? = null
    private var orderBeforeMove: List<Camera> = emptyList()
    private var moveOkArmed = false

    private val perPage: Int get() = config.perPage
    private val pageCount: Int get() = maxOf(1, (cameras.size + perPage - 1) / perPage)

    private val cycleRunnable = Runnable {
        if (moving != null) return@Runnable
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
        topBar = findViewById(R.id.top_bar)
        btnLayout = findViewById(R.id.btn_layout)
        btnLayout.setOnClickListener { cycleLayout() }

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
        if (moving != null) finishMove(save = false)
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
        val known = if (forceReload) emptyList() else NvrConfig.loadCameraCache(this).ifEmpty { cameras }
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
                    if (moving != null) return@onSuccess // don't redraw while you rearrange
                    if (NvrConfig.applyCameraOrder(this, list) != cameras) showCameras(list)
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
        cameras = NvrConfig.applyCameraOrder(this, list)
        val f = focusIndex
        page = if (f != null) f / perPage else page
        page = page.coerceIn(0, pageCount - 1)
        buildPage()
    }

    private fun buildPage() {
        moving = null // a redraw ends any rearranging
        releaseTiles()
        grid.removeAllViews()
        updateLayoutButton()
        if (cameras.isEmpty()) {
            showStatus("No cameras found on the NVR.")
            return
        }
        statusText.visibility = View.GONE

        val start = page * perPage
        val pageCams = cameras.subList(start, min(start + perPage, cameras.size))
        val g = CameraGridLayout(this)
        if (featured) {
            // 3 × 3 cells: one camera covers 2 × 2, five cover one each. All exactly 16:9.
            g.columns = 3
            g.rows = 3
            slots = CameraGridLayout.FEATURED_SLOTS
        } else {
            val n = config.gridSize
            g.columns = n
            // Only as many rows as this page needs, so the cameras fill the screen.
            g.rows = if (pageCount == 1) (pageCams.size + n - 1) / n else n
            slots = CameraGridLayout.equalSlots(pageCams.size, n)
        }
        grid.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cameraGrid = g
        pageCams.forEachIndexed { i, cam ->
            val tile = CameraTile(this, cam, config, this)
            tile.view.setOnClickListener { openFullscreen(cameras.indexOf(tile.camera)) }
            tile.view.setOnLongClickListener { startMove(tile); true }
            tile.setBig(featured && i == 0)
            g.addTile(tile.view, slots[i])
            tiles += tile
        }

        startTiles()
        updateLabel()
        showBar()

        val target = focusIndex?.let { it - start }?.takeIf { it in tiles.indices } ?: 0
        tiles.getOrNull(target)?.view?.requestFocus()
        focusIndex = null
        scheduleCycle()
    }

    /** Puts every tile in its slot (after a move) and gives the first slot the big stream. */
    private fun placeTiles() {
        val g = cameraGrid ?: return
        tiles.forEachIndexed { i, tile ->
            g.setSlot(tile.view, slots[i])
            tile.setBig(featured && i == 0)
        }
    }

    /** Top-bar Layout button: each press switches to the next layout. */
    private val layouts = listOf(
        Triple(NvrConfig.LAYOUT_FEATURED, NvrConfig.FIT_FILL, "1 big + 5 small"),
        Triple(NvrConfig.LAYOUT_GRID, NvrConfig.FIT_BARS, "Equal · whole picture"),
        Triple(NvrConfig.LAYOUT_GRID, NvrConfig.FIT_STRETCH, "Equal · stretched"),
        Triple(NvrConfig.LAYOUT_GRID, NvrConfig.FIT_FILL, "Equal · fill"),
    )

    private fun currentLayoutIndex() =
        layouts.indexOfFirst { it.first == config.layout && it.second == config.fitMode }

    private fun updateLayoutButton() {
        val name = layouts.getOrNull(currentLayoutIndex())?.third
            ?: if (featured) "1 big + 5 small" else "Equal"
        btnLayout.text = "Layout: $name"
    }

    private fun cycleLayout() {
        val next = layouts[(currentLayoutIndex() + 1).mod(layouts.size)]
        config = config.copy(layout = next.first, fitMode = next.second)
        config.save(this)
        page = 0
        buildPage()
        btnLayout.requestFocus() // stay on the button so you can press OK again
        showBar()
    }

    private fun showBar() {
        handler.removeCallbacks(hideBar)
        topBar.animate().cancel()
        topBar.alpha = 1f
        topBar.visibility = View.VISIBLE
        handler.postDelayed(hideBar, BAR_HIDE_MS)
    }

    /** Live video if it works; otherwise snapshots. Tries one camera first. */
    private fun startTiles() {
        when (VideoMode.mode) {
            VideoMode.Mode.LIVE -> tiles.forEach { it.startLive() }
            // The built-in player couldn't play any camera: use VLC for all of them.
            VideoMode.Mode.SNAPSHOT -> tiles.forEach {
                if (VideoMode.authFailed) it.startSnapshots() else it.startVlc()
            }
            VideoMode.Mode.UNKNOWN -> {
                // Test the built-in player on one small camera first; the big one uses VLC.
                val probe = tiles.firstOrNull { !it.big }
                tiles.forEach {
                    when {
                        it.big -> it.startLive()
                        it === probe -> it.startLive()
                        else -> it.showWaiting()
                    }
                }
            }
        }
    }

    override fun onLiveResult(tile: CameraTile, ok: Boolean, authFailed: Boolean, reason: String?) {
        if (tile !in tiles) return
        when (VideoMode.mode) {
            VideoMode.Mode.UNKNOWN -> if (ok) {
                VideoMode.mode = VideoMode.Mode.LIVE
                tiles.filter { it !== tile && !it.big }.forEach { it.startLive() }
            } else {
                // This camera's stream may just be unsupported by the built-in player:
                // play it with VLC and try the built-in player on the next camera.
                val next = if (authFailed) null
                else tiles.drop(tiles.indexOf(tile) + 1).firstOrNull { !it.big }
                if (next != null) {
                    tile.startVlc()
                    next.startLive()
                } else {
                    VideoMode.mode = VideoMode.Mode.SNAPSHOT
                    VideoMode.reason = reason
                    VideoMode.authFailed = authFailed
                    // A refused login must not be retried by every camera (account lock).
                    tiles.forEach {
                        if (authFailed) it.startSnapshots() else if (!it.big || it === tile) it.startVlc()
                    }
                    Toast.makeText(
                        this,
                        if (authFailed) "The NVR refused the video login. Showing snapshots – check the password in Settings."
                        else "Switching to the VLC player (${reason ?: "built-in player couldn't play the cameras"}).",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            // Live works for other cameras but this one couldn't start (e.g. its
            // stream format isn't supported): use VLC for just this camera.
            VideoMode.Mode.LIVE -> if (!ok) { if (authFailed) tile.startSnapshots() else tile.startVlc() }
            VideoMode.Mode.SNAPSHOT -> Unit
        }
        updateLabel()
    }

    private fun updateLabel() {
        pageLabel.text = buildString {
            append("${cameras.size} cameras")
            if (pageCount > 1) append("   ·   Page ${page + 1} of $pageCount (◀ ▶ at the edge)")
            if (moving != null) {
                clear()
                append("Moving ${moving?.camera?.name}:   ◀ ▲ ▼ ▶ move   ·   OK place it here   ·   Back cancel")
                return@buildString
            }
            append("   ·   OK: full screen   ·   Hold OK: move camera   ·   ▲: Layout / Refresh / Settings")
            if (VideoMode.mode == VideoMode.Mode.SNAPSHOT) {
                append(if (VideoMode.authFailed) "   ·   Snapshot mode (video login refused)" else "   ·   VLC mode")
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

    /** Hold OK on a camera: pick it up so the arrows move it. */
    private fun startMove(tile: CameraTile) {
        if (moving != null || tile !in tiles) return
        moving = tile
        orderBeforeMove = cameras
        moveOkArmed = false // the OK being held right now must not drop it straight away
        tile.view.cancelLongPress()
        tile.view.isPressed = false
        tile.setMoving(true)
        tile.view.requestFocus()
        handler.removeCallbacks(cycleRunnable)
        updateLabel()
    }

    /**
     * Swap the moving camera with the nearest camera in that direction (works for
     * both layouts). Videos keep playing; only the big spot switches streams.
     */
    private fun moveBy(dCol: Int, dRow: Int) {
        val tile = moving ?: return
        val i = tiles.indexOf(tile)
        if (i < 0) return
        val from = slots[i]
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (j in tiles.indices) {
            if (j == i) continue
            val to = slots[j]
            // Only cameras fully past this one's edge in the pressed direction.
            val beyond = when {
                dCol > 0 -> to.col >= from.col + from.colSpan
                dCol < 0 -> to.col + to.colSpan <= from.col
                dRow > 0 -> to.row >= from.row + from.rowSpan
                else -> to.row + to.rowSpan <= from.row
            }
            if (!beyond) continue
            val dx = to.centerX - from.centerX
            val dy = to.centerY - from.centerY
            val along = if (dCol != 0) dx * dCol else dy * dRow // distance in the pressed direction
            val across = if (dCol != 0) kotlin.math.abs(dy) else kotlin.math.abs(dx)
            val score = along + 2 * across // prefer the same row / column
            if (score < bestScore) { bestScore = score; best = j }
        }
        if (best < 0) return
        java.util.Collections.swap(tiles, i, best)
        val start = page * perPage
        cameras = cameras.toMutableList().also { java.util.Collections.swap(it, start + i, start + best) }
        placeTiles()
        tile.view.requestFocus()
    }

    /** OK: keep the new order. Back: put every camera back where it was. */
    private fun finishMove(save: Boolean) {
        val tile = moving ?: return
        moving = null
        tile.setMoving(false)
        if (save) {
            NvrConfig.saveCameraOrder(this, cameras)
            Toast.makeText(this, "Camera order saved", Toast.LENGTH_SHORT).show()
        } else {
            cameras = orderBeforeMove
            tiles.sortBy { cameras.indexOf(it.camera) }
            placeTiles()
        }
        tile.view.requestFocus()
        updateLabel()
        scheduleCycle()
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
        if (index < 0) return
        // Free the grid's decoders first (the big camera uses the TV's 4K decoder,
        // which full screen needs). The grid restarts when you come back.
        tiles.forEach { it.release() }
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
        if (moving != null) return
        if (config.cycleSeconds > 0 && pageCount > 1) {
            handler.postDelayed(cycleRunnable, config.cycleSeconds * 1000L)
        }
    }

    private fun isTile(v: View?) = v?.tag == CameraTile.TAG

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (moving != null) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) moveBy(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) moveBy(+1, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveBy(0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveBy(0, +1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // A fresh press then release places the camera.
                    if (down && event.repeatCount == 0) moveOkArmed = true
                    else if (!down && moveOkArmed) finishMove(save = true)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (!down) finishMove(save = false)
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) showBar()
        if (event.action == KeyEvent.ACTION_DOWN && cameras.isNotEmpty()) {
            scheduleCycle() // any key press postpones auto-cycling
            val focused = currentFocus
            val inBar = focused != null && topBar.findFocus() != null
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (isTile(focused) && !isTile(focused?.focusSearch(View.FOCUS_UP))) {
                        btnLayout.requestFocus(); return true
                    } else if (inBar) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (inBar && tiles.isNotEmpty()) {
                    tiles.first().view.requestFocus(); return true
                }
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
        const val BAR_HIDE_MS = 6_000L
    }
}
