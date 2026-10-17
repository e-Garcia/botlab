package dev.egarcia.botlab.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.egarcia.botlab.Lab
import dev.egarcia.botlab.analysis.BehaviorAnalyzer
import dev.egarcia.botlab.analysis.Event
import dev.egarcia.botlab.analysis.EventType
import dev.egarcia.botlab.analysis.ReportText
import dev.egarcia.botlab.env.EnvironmentProbe

/**
 * The arena. Buttons, a text box, a feed and a swipe area, styled like the actions a social app has, all instrumented
 * to record how they are operated. A live score is shown; the full report is at the bottom.
 */
class BotLabActivity : Activity() {

    private val targets = mutableListOf<Pair<String, View>>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var headline: TextView
    private lateinit var counters: TextView
    private lateinit var report: TextView
    private lateinit var label: EditText
    private val counts = linkedMapOf("Like" to 0, "Follow" to 0, "Message" to 0, "Send" to 0)
    private var shownVersion = -1
    private var tick = 0
    private var touching = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh(force = tick % 5 == 0)
            tick++
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)

        headline = TextView(this).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD) }
        counters = TextView(this).apply { textSize = 14f }

        val actions = LinearLayout(this).apply {
            counts.keys.forEach { name ->
                addView(TrackedButton(this@BotLabActivity, name.lowercase()).apply {
                    text = name
                    isAllCaps = false
                    setOnClickListener {
                        counts[name] = counts.getValue(name) + 1
                        renderCounters()
                    }
                    targets += name.lowercase() to this
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val message = TrackedEditText(this, "message").apply {
            hint = "Write a message"
            targets += "message" to this
        }
        val feed = TrackedScrollView(this, "feed").apply {
            addView(LinearLayout(this@BotLabActivity).apply {
                orientation = LinearLayout.VERTICAL
                (1..30).forEach { i ->
                    addView(TextView(this@BotLabActivity).apply {
                        text = "Post #$i: a short update in the feed"
                        textSize = 16f
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                    })
                }
            })
            background = outline()
            targets += "feed" to this
        }
        val swipe = SwipePad(this).apply {
            background = outline()
            targets += "swipe_pad" to this
        }
        val protectedText = TextView(this).apply {
            text = "Secret code: 4242"
            textSize = 16f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = outline()
            // Android 14+: hide this view from accessibility services that are not declared accessibility tools.
            // Switchable (adb: --ez protected false) to measure what this one attribute does to an accessibility service.
            if (Build.VERSION.SDK_INT >= 34 && intent.getBooleanExtra("protected", true)) setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }

        label = EditText(this).apply {
            setText("manual")
            hint = "session label"
            setSingleLine()
            targets += "ctl" to this
        }
        val reset = Button(this).apply {
            text = "Reset"
            setOnClickListener { Lab.reset(); counts.keys.forEach { counts[it] = 0 }; renderCounters(); refresh(force = true) }
            targets += "ctl" to this
        }
        val save = Button(this).apply {
            text = "Save session"
            setOnClickListener {
                val file = Lab.save(this@BotLabActivity, label.text.toString())
                headline.text = "Saved ${file.name}"
            }
            targets += "ctl" to this
        }
        report = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(this@BotLabActivity).apply { text = "Bot Lab"; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
            addView(headline)
            addView(section("Actions"))
            addView(actions)
            addView(counters)
            addView(section("Message"))
            addView(message)
            addView(section("Feed (scroll it)"))
            addView(feed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)))
            addView(section("Swipe pad"))
            addView(swipe, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)))
            addView(section("Protected view (accessibilityDataSensitive)"))
            addView(protectedText)
            addView(section("Session"))
            addView(label)
            addView(LinearLayout(this@BotLabActivity).apply { addView(reset); addView(save) })
            addView(section("Report"))
            addView(report)
        }
        setContentView(ScrollView(this).apply { addView(column); isFillViewport = true })
        renderCounters()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** Every touch that reaches the window, with the geometry of the view it landed on. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        record(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun record(ev: MotionEvent) {
        if (ev.pointerCount > 1) return
        val type = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; EventType.DOWN }
            MotionEvent.ACTION_MOVE -> if (touching) EventType.MOVE else return
            MotionEvent.ACTION_UP -> { touching = false; EventType.UP }
            MotionEvent.ACTION_CANCEL -> { touching = false; EventType.CANCEL }
            else -> return
        }
        val target = targetAt(ev.rawX.toInt(), ev.rawY.toInt())
        if (target?.first == "ctl") return
        val centre = target?.second?.let { v -> IntArray(2).also(v::getLocationOnScreen).let { it[0] + v.width / 2f to it[1] + v.height / 2f } }
        Lab.add(
            Event(
                type, Lab.rel(ev.eventTime), target?.first ?: "screen", ev.rawX, ev.rawY, centre?.first ?: 0f, centre?.second ?: 0f,
                ev.pressure, ev.size, ev.getToolType(0), ev.source, ev.deviceId, ev.flags,
            ),
        )
    }

    private fun targetAt(x: Int, y: Int): Pair<String, View>? = targets.lastOrNull { (_, v) ->
        v.isShown && Rect().let { r -> v.getGlobalVisibleRect(r) && r.contains(x, y) }
    }

    private fun refresh(force: Boolean) {
        if (!force && Lab.version == shownVersion) return
        shownVersion = Lab.version
        val result = BehaviorAnalyzer.analyze(Lab.events())
        headline.text = "Behaviour ${result.score}/100 · ${result.verdict.label}"
        headline.setTextColor(Color.parseColor(when { result.score >= 45 -> "#B3261E"; result.score >= 15 -> "#B26A00"; else -> "#1E7B34" }))
        report.text = ReportText.render(result, EnvironmentProbe.collect(this))
    }

    private fun renderCounters() {
        counters.text = counts.entries.joinToString("  ·  ") { "${it.key} ${it.value}" }
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#5F6368"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun outline() = GradientDrawable().apply {
        setStroke(dp(1), Color.parseColor("#C4C7C5"))
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
