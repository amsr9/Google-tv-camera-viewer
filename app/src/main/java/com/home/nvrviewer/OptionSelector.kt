package com.home.nvrviewer

import android.view.KeyEvent
import android.widget.Button

/**
 * A TV-friendly setting: a button showing "Title:  ◀ value ▶".
 * ◀ / ▶ on the remote change the value; OK steps to the next one.
 */
class OptionSelector(
    private val button: Button,
    private val title: String,
    choices: List<Int>,
    initial: Int,
    private val label: (Int) -> String,
) {
    /** Keeps a value set earlier that isn't one of the standard choices. */
    private val values: List<Int> = if (initial in choices) choices else (choices + initial).sorted()
    private var index = values.indexOf(initial).coerceAtLeast(0)

    val value: Int get() = values[index]

    /** Called after the value changes (◀ ▶ or OK). */
    var onChange: ((Int) -> Unit)? = null

    init {
        render()
        button.setOnClickListener { step(+1) }
        button.setOnKeyListener { _, keyCode, event ->
            val horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (horizontal && event.action == KeyEvent.ACTION_DOWN) {
                step(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1)
            }
            horizontal // consume ◀ ▶ so focus doesn't jump away
        }
    }

    private fun step(delta: Int) {
        index = (index + delta + values.size) % values.size
        render()
        onChange?.invoke(value)
    }

    private fun render() {
        button.text = "$title:    ◀  ${label(value)}  ▶"
    }
}
