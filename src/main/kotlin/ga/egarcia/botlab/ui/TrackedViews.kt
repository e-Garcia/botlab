package ga.egarcia.botlab.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import ga.egarcia.botlab.Lab

/*
 * These views behave normally but write down HOW they were operated. Every check here is something any app can do:
 * override performAccessibilityAction, watch the input connection, compare touches with clicks.
 */

class TrackedButton(context: Context, private val id: String) : Button(context) {
    override fun performClick(): Boolean {
        Lab.click(id)
        return super.performClick()
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        Lab.a11y(id, action)
        return super.performAccessibilityAction(action, arguments)
    }
}

class TrackedEditText(context: Context, private val id: String) : EditText(context) {
    private var lastLength = 0

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                val delta = s.length - lastLength
                lastLength = s.length
                if (delta != 0) Lab.text(id, s.length, delta)
            }
        })
    }

    /** Text typed on a keyboard is committed through this connection; text set by an accessibility action is not. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                Lab.ime(id, text?.length ?: 0)
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                Lab.ime(id, text?.length ?: 0)
                return super.setComposingText(text, newCursorPosition)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) Lab.key(id)
        return super.dispatchKeyEvent(event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        Lab.a11y(id, action)
        return super.performAccessibilityAction(action, arguments)
    }
}

class TrackedScrollView(context: Context, private val id: String) : ScrollView(context) {
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != oldt) Lab.scroll(id, t - oldt)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        Lab.a11y(id, action)
        return super.performAccessibilityAction(action, arguments)
    }
}

/** A blank area that draws where it was swiped; the activity records the touches that reach it. */
class SwipePad(context: Context) : View(context) {
    private val path = Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3F51B5"); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80868B"); textSize = 40f }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { path.reset(); path.moveTo(event.x, event.y) }
            MotionEvent.ACTION_MOVE -> path.lineTo(event.x, event.y)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText("Swipe here", 24f, 56f, label)
        canvas.drawPath(path, stroke)
    }
}
