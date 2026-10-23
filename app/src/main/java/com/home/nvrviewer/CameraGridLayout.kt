package com.home.nvrviewer

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Equal-size grid where each child is placed by its cell number.
 * Moving a camera only changes its cell, so its view is never removed and its
 * live video keeps playing while you rearrange.
 */
class CameraGridLayout(context: Context) : ViewGroup(context) {

    var columns = 1
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var rows = 1
        set(value) { field = value.coerceAtLeast(1); requestLayout() }

    class LayoutParams(var cell: Int) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    fun addTile(view: View, cell: Int) = addView(view, LayoutParams(cell))

    fun cellOf(view: View): Int = (view.layoutParams as LayoutParams).cell

    fun setCell(view: View, cell: Int) {
        (view.layoutParams as LayoutParams).cell = cell
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cw = MeasureSpec.makeMeasureSpec(width / columns, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(height / rows, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / columns
        val ch = (b - t) / rows
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val cell = cellOf(child)
            val x = (cell % columns) * cw
            val y = (cell / columns) * ch
            child.layout(x, y, x + cw, y + ch)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(0)
}
