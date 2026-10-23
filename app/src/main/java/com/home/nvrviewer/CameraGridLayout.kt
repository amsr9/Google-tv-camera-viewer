package com.home.nvrviewer

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Grid of equal cells where each camera occupies a [Slot] (one cell, or a
 * block of cells for the big camera). Moving a camera only changes its slot,
 * so its view is never removed and its live video keeps playing.
 */
class CameraGridLayout(context: Context) : ViewGroup(context) {

    /** Position in cells: column, row, and how many columns/rows it covers. */
    data class Slot(val col: Int, val row: Int, val colSpan: Int = 1, val rowSpan: Int = 1) {
        val centerX: Float get() = col + colSpan / 2f
        val centerY: Float get() = row + rowSpan / 2f
    }

    var columns = 1
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var rows = 1
        set(value) { field = value.coerceAtLeast(1); requestLayout() }

    class LayoutParams(var slot: Slot) : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    fun addTile(view: View, slot: Slot) = addView(view, LayoutParams(slot))

    fun slotOf(view: View): Slot = (view.layoutParams as LayoutParams).slot

    fun setSlot(view: View, slot: Slot) {
        (view.layoutParams as LayoutParams).slot = slot
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val s = slotOf(child)
            child.measure(
                MeasureSpec.makeMeasureSpec(edge(width, columns, s.col + s.colSpan) - edge(width, columns, s.col), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(edge(height, rows, s.row + s.rowSpan) - edge(height, rows, s.row), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val s = slotOf(child)
            child.layout(
                edge(w, columns, s.col), edge(h, rows, s.row),
                edge(w, columns, s.col + s.colSpan), edge(h, rows, s.row + s.rowSpan),
            )
        }
    }

    /** Pixel position of grid line [index]; rounding spread evenly so tiles touch exactly. */
    private fun edge(total: Int, count: Int, index: Int) = total * index / count

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(Slot(0, 0))
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(Slot(0, 0))

    companion object {
        /** Equal grid: [count] cameras, [cols] per row, [rows] rows. */
        fun equalSlots(count: Int, cols: Int): List<Slot> =
            (0 until count).map { Slot(it % cols, it / cols) }

        /**
         * One large camera (top-left, 2×2) and five small ones on a 3×3 grid.
         * On a 16:9 screen every tile is exactly 16:9: nothing trimmed, no bars.
         */
        val FEATURED_SLOTS = listOf(
            Slot(0, 0, 2, 2),
            Slot(2, 0), Slot(2, 1),
            Slot(0, 2), Slot(1, 2), Slot(2, 2),
        )
    }
}
