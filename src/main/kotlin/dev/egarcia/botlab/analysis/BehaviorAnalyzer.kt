package dev.egarcia.botlab.analysis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

enum class Severity(val weight: Int) { INFO(0), LOW(5), MEDIUM(15), HIGH(30) }

data class Finding(
    val id: String,
    val severity: Severity,
    val title: String,
    /** What was measured, with numbers, so a reader can judge it. */
    val evidence: String,
    /** What an app could conclude from it. */
    val meaning: String,
)

enum class Verdict(val label: String) {
    HUMAN_LIKE("Looks human-like"),
    SUSPICIOUS("Suspicious"),
    AUTOMATED("Likely automated"),
    NOT_ENOUGH_DATA("Not enough interaction to judge"),
}

data class Report(val findings: List<Finding>, val score: Int, val verdict: Verdict, val eventCount: Int)

/**
 * Client-side heuristics of the kind anti-abuse SDKs use on touch, keyboard and accessibility data. Thresholds are
 * illustrative, not any vendor's: this shows which of our own actions are *observable*, not what a specific
 * service (a messaging app's, a bank's) actually decides. Server-side signals cannot be tested from the phone.
 */
object BehaviorAnalyzer {

    const val MIN_INTERACTIONS = 6

    private const val ACCESSIBILITY_EVENT_FLAG = 0x800 // MotionEvent flag set on gestures dispatched by accessibility services

    fun analyze(events: List<Event>): Report {
        val sorted = events.sortedBy { it.t }
        // Interactions, not raw events: one tap alone produces a down, an up and a click.
        if (sorted.count { it.type == EventType.DOWN || it.type == EventType.A11Y || it.type == EventType.TEXT } < MIN_INTERACTIONS) {
            return Report(emptyList(), 0, Verdict.NOT_ENOUGH_DATA, sorted.size)
        }
        val touches = touches(sorted)
        val findings = listOfNotNull(
            accessibilityActions(sorted),
            clickWithoutTouch(sorted),
            textWithoutKeyboard(sorted),
            scrollWithoutTouch(sorted),
            syntheticTouchMetadata(touches),
            tapPosition(touches),
            tapDuration(touches),
            swipeShape(touches),
            typingCadence(sorted),
            actionRhythm(sorted, touches),
        )
        val score = findings.sumOf { it.severity.weight }.coerceAtMost(100)
        val verdict = when {
            score >= 45 -> Verdict.AUTOMATED
            score >= 15 -> Verdict.SUSPICIOUS
            else -> Verdict.HUMAN_LIKE
        }
        return Report(findings.sortedByDescending { it.severity.weight }, score, verdict, sorted.size)
    }

    // --- Direct signals: the app is told it is being operated by something other than a finger --------------

    private fun accessibilityActions(events: List<Event>): Finding? {
        val actions = events.filter { it.type == EventType.A11Y }
        if (actions.isEmpty()) return null
        val summary = actions.groupingBy { actionName(it.action) }.eachCount().entries.joinToString { "${it.value} x ${it.key}" }
        return Finding(
            "a11y_action", Severity.HIGH, "Accessibility actions performed on the app's views", summary,
            "Any app can override View.performAccessibilityAction and see exactly when an accessibility service (or TalkBack) acts on it, instead of a finger.",
        )
    }

    private fun clickWithoutTouch(events: List<Event>): Finding? {
        val clicks = events.filter { it.type == EventType.CLICK }
        if (clicks.isEmpty()) return null
        val ups = events.filter { it.type == EventType.UP }
        val untouched = clicks.filter { c -> ups.none { it.t <= c.t && c.t - it.t <= 400 } }
        if (untouched.isEmpty()) return null
        return Finding(
            "click_without_touch", Severity.HIGH, "Clicks with no touch before them",
            "${untouched.size} of ${clicks.size} clicks had no finger-up within 400 ms",
            "A real tap is a touch sequence that ends in a click. A click with no touch was produced programmatically (an accessibility action, for example).",
        )
    }

    private fun textWithoutKeyboard(events: List<Event>): Finding? {
        val changes = events.filter { it.type == EventType.TEXT && it.delta != 0 }
        if (changes.isEmpty()) return null
        val input = events.filter { it.type == EventType.KEY || it.type == EventType.IME }
        val orphans = changes.filter { c -> input.none { it.t <= c.t && c.t - it.t <= 400 } }
        if (orphans.isEmpty()) return null
        val bulk = orphans.maxOf { abs(it.delta) }
        return Finding(
            "text_without_keyboard", Severity.HIGH, "Text changed without any keyboard input",
            "${orphans.size} of ${changes.size} text changes had no key or keyboard event first; the largest added $bulk characters at once",
            "Typing goes through the keyboard. Text that appears with no key events (often all at once) was set programmatically.",
        )
    }

    private fun scrollWithoutTouch(events: List<Event>): Finding? {
        val scrolls = events.filter { it.type == EventType.SCROLL && it.dy != 0 }
        if (scrolls.isEmpty()) return null
        val touch = events.filter { it.type == EventType.MOVE || it.type == EventType.DOWN }
        val orphans = scrolls.filter { s -> touch.none { it.t <= s.t && s.t - it.t <= 300 } }
        if (orphans.isEmpty()) return null
        return Finding(
            "scroll_without_touch", Severity.HIGH, "Scrolling with no finger movement",
            "${orphans.size} of ${scrolls.size} scroll changes had no touch movement in the previous 300 ms",
            "A list that scrolls with no finger on the screen was scrolled programmatically.",
        )
    }

    // --- Touch data -------------------------------------------------------------------------------------------

    private class Touch(val points: List<Event>) {
        val down = points.first()
        val up = points.last()
        val durationMs get() = up.t - down.t
        val travel: Double get() = hypot((up.x - down.x).toDouble(), (up.y - down.y).toDouble())
        val maxDrift: Double get() = points.maxOf { hypot((it.x - down.x).toDouble(), (it.y - down.y).toDouble()) }
        val isTap get() = maxDrift < 24 && durationMs < 600
        val isSwipe get() = travel >= 80
        val offsetFromCentre get() = hypot((down.x - down.cx).toDouble(), (down.y - down.cy).toDouble())
    }

    private fun touches(events: List<Event>): List<Touch> {
        val result = mutableListOf<Touch>()
        var current = mutableListOf<Event>()
        for (e in events) when (e.type) {
            EventType.DOWN -> current = mutableListOf(e)
            EventType.MOVE -> if (current.isNotEmpty()) current += e
            EventType.UP -> if (current.isNotEmpty()) {
                current += e
                result += Touch(current)
                current = mutableListOf()
            }
            EventType.CANCEL -> current = mutableListOf()
        }
        return result
    }

    private fun syntheticTouchMetadata(touches: List<Touch>): Finding? {
        if (touches.isEmpty()) return null
        val downs = touches.map { it.down }
        val flagged = downs.count { it.flags and ACCESSIBILITY_EVENT_FLAG != 0 }
        val virtual = downs.count { it.deviceId <= 0 }
        val pressureConstant = downs.size >= 4 && std(downs.map { it.pressure.toDouble() }) < 0.001
        val sizeConstant = downs.size >= 4 && std(downs.map { it.size.toDouble() }) < 0.001
        val parts = mutableListOf<String>()
        var severity = Severity.INFO
        if (flagged > 0) { parts += "$flagged touches carry the accessibility-gesture flag"; severity = Severity.HIGH }
        if (virtual > 0) { parts += "$virtual touches come from a virtual device (id <= 0)"; if (severity != Severity.HIGH) severity = Severity.MEDIUM }
        if (pressureConstant && sizeConstant) { parts += "pressure and contact size never vary"; if (severity == Severity.INFO) severity = Severity.LOW }
        if (parts.isEmpty()) return null
        return Finding(
            "touch_metadata", severity, "Touch events look synthetic", parts.joinToString("; "),
            "Injected touches can differ from a finger's in device id, flags, pressure and contact size.",
        )
    }

    private fun tapPosition(touches: List<Touch>): Finding? {
        val taps = touches.filter { it.isTap && it.down.cx > 0 }
        if (taps.size < 4) return null
        val offsets = taps.map { it.offsetFromCentre }
        val mean = offsets.average()
        val spread = std(offsets)
        val centred = mean < 3.0
        val repeated = spread < 1.5
        if (!centred && !repeated) return null
        return Finding(
            "tap_position", Severity.MEDIUM,
            if (centred) "Taps land on the exact centre of buttons" else "Taps hit the exact same spot every time",
            "${taps.size} taps: mean %.1f px from the target centre, spread %.1f px; people scatter by tens of pixels".format(mean, spread),
            "Automation usually aims at the same point, often the middle of the target, every time.",
        )
    }

    private fun tapDuration(touches: List<Touch>): Finding? {
        val taps = touches.filter { it.isTap }
        if (taps.size < 4) return null
        val d = taps.map { it.durationMs.toDouble() }
        val spread = std(d)
        val mean = d.average()
        if (spread >= 4.0 && mean >= 30) return null
        return Finding(
            "tap_duration_constant", Severity.MEDIUM, "Every tap lasts the same time",
            "${taps.size} taps: mean %.0f ms, spread %.1f ms; finger taps vary by tens of ms".format(mean, spread),
            "A fixed press duration is typical of scripted gestures.",
        )
    }

    private fun swipeShape(touches: List<Touch>): Finding? {
        val swipes = touches.filter { it.isSwipe && it.points.size >= 4 }
        if (swipes.isEmpty()) return null
        val straight = swipes.count { straightness(it) < 2.0 }
        if (straight < maxOf(1, swipes.size * 3 / 4)) return null
        return Finding(
            "swipe_linear", Severity.MEDIUM, "Swipes are perfectly straight",
            "$straight of ${swipes.size} swipes deviated under 2 px from a straight line",
            "Finger swipes curve and change speed; scripted ones are often ruler-straight.",
        )
    }

    /** Largest distance of any point from the straight line between the first and last point. */
    private fun straightness(t: Touch): Double {
        val a = t.down; val b = t.up
        val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        if (len == 0.0) return 0.0
        return t.points.maxOf { p -> abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / len }
    }

    // --- Timing -----------------------------------------------------------------------------------------------

    private fun typingCadence(events: List<Event>): Finding? {
        val typed = events.filter { it.type == EventType.TEXT && it.delta in 1..3 }
        if (typed.size < 6) return null
        val gaps = typed.zipWithNext { a, b -> (b.t - a.t).toDouble() }.filter { it in 1.0..2_000.0 }
        if (gaps.size < 5) return null
        val mean = gaps.average()
        val cov = std(gaps) / mean
        if (mean >= 40 && cov >= 0.15) return null
        return Finding(
            "typing_cadence", Severity.MEDIUM, "Typing rhythm is not human",
            "keystroke gaps: mean %.0f ms, variation %.2f (people: irregular, roughly 100 to 300 ms)".format(mean, cov),
            "Human typing has irregular rhythm; a fixed or very fast cadence suggests a script.",
        )
    }

    private fun actionRhythm(events: List<Event>, touches: List<Touch>): Finding? {
        val times = (touches.filter { it.isTap || it.isSwipe }.map { it.down.t } + events.filter { it.type == EventType.CLICK }.map { it.t }).sorted()
        // Merge near-simultaneous records of the same action (a tap also produces a click).
        val actions = times.fold(mutableListOf<Long>()) { acc, t -> if (acc.isEmpty() || t - acc.last() > 150) acc += t; acc }
        if (actions.size < 6) return null
        val gaps = actions.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = gaps.average()
        val cov = std(gaps) / mean
        if (cov >= 0.15 && gaps.min() >= 250) return null
        return Finding(
            "action_rhythm", Severity.MEDIUM, "Actions arrive on a fixed or very fast rhythm",
            "${actions.size} actions: gap mean %.0f ms, variation %.2f, shortest %.0f ms".format(mean, cov, gaps.min()),
            "People pause irregularly between actions; machines are metronomic or faster than a person can move between targets.",
        )
    }

    // --- Helpers ----------------------------------------------------------------------------------------------

    private fun std(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    fun actionName(action: Int): String = when (action) {
        0x1 -> "FOCUS"
        0x2 -> "CLEAR_FOCUS"
        0x4 -> "SELECT"
        0x10 -> "CLICK"
        0x20 -> "LONG_CLICK"
        0x1000 -> "SCROLL_FORWARD"
        0x2000 -> "SCROLL_BACKWARD"
        0x200000 -> "SET_TEXT"
        else -> "action_0x${action.toString(16)}"
    }
}
