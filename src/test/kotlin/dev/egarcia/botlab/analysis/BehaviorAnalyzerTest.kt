package dev.egarcia.botlab.analysis

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BehaviorAnalyzerTest {

    // --- Session builders ------------------------------------------------------------------------------------

    private val rnd = Random(42)

    /** A finger tap: scattered around the centre, 60 to 140 ms, varying pressure and size, a real device id. */
    private fun humanTap(t: Long, view: String = "b", cx: Float = 500f, cy: Float = 800f): List<Event> {
        val x = cx + rnd.nextInt(-40, 40); val y = cy + rnd.nextInt(-25, 25)
        val dur = rnd.nextLong(60, 140)
        val p = 0.3f + rnd.nextFloat() * 0.5f; val s = 0.02f + rnd.nextFloat() * 0.05f
        return listOf(
            Event(EventType.DOWN, t, view, x, y, cx, cy, p, s, 1, 0x1002, 5, 0),
            Event(EventType.UP, t + dur, view, x + 1, y + 1, cx, cy, p, s, 1, 0x1002, 5, 0),
            Event(EventType.CLICK, t + dur, view),
        )
    }

    /** A tap dispatched by an accessibility service's gesture: dead centre, fixed 50 ms, constant metadata, flagged. */
    private fun gestureTap(t: Long, view: String = "b", cx: Float = 500f, cy: Float = 800f, flags: Int = 0x800): List<Event> = listOf(
        Event(EventType.DOWN, t, view, cx, cy, cx, cy, 1f, 1f, 1, 0x1002, -1, flags),
        Event(EventType.UP, t + 50, view, cx, cy, cx, cy, 1f, 1f, 1, 0x1002, -1, flags),
        Event(EventType.CLICK, t + 50, view),
    )

    /** ACTION_CLICK: no touch at all, just the accessibility action and the click it causes. */
    private fun actionClick(t: Long, view: String = "b") = listOf(
        Event(EventType.A11Y, t, view, action = 0x10),
        Event(EventType.CLICK, t, view),
    )

    private fun spaced(count: Int, first: Long, gap: (Int) -> Long, make: (Long) -> List<Event>): List<Event> {
        var t = first
        return (0 until count).flatMap { i -> make(t).also { t += gap(i) } }
    }

    private fun humanGap(i: Int) = 700L + rnd.nextLong(0, 1800)

    private fun ids(report: Report) = report.findings.map { it.id }.toSet()

    // --- Tests -----------------------------------------------------------------------------------------------

    @Test
    fun tooLittleInteractionIsNotJudged() {
        val report = BehaviorAnalyzer.analyze(humanTap(0) + humanTap(1000))
        assertEquals(Verdict.NOT_ENOUGH_DATA, report.verdict)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun scatteredVariedFingerTapsLookHuman() {
        val report = BehaviorAnalyzer.analyze(spaced(10, 0, ::humanGap, ::humanTap))
        assertEquals(report.findings.toString(), Verdict.HUMAN_LIKE, report.verdict)
        assertTrue(report.score < 15)
    }

    @Test
    fun accessibilityClicksAreCaughtDirectly() {
        val report = BehaviorAnalyzer.analyze(spaced(8, 0, ::humanGap, ::actionClick))
        val ids = ids(report)
        assertTrue(ids.toString(), "a11y_action" in ids && "click_without_touch" in ids)
        assertEquals(Verdict.AUTOMATED, report.verdict)
        assertTrue(report.findings.first { it.id == "a11y_action" }.evidence.contains("CLICK"))
    }

    @Test
    fun scriptedGestureTapsShowSyntheticMetadataAndRegularity() {
        val report = BehaviorAnalyzer.analyze(spaced(8, 0, ::humanGap, ::gestureTap))
        val ids = ids(report)
        assertTrue(ids.toString(), "touch_metadata" in ids && "tap_position" in ids && "tap_duration_constant" in ids)
        assertFalse("a real touch sequence ends in a click, so no click-without-touch", "click_without_touch" in ids)
        assertEquals(Severity.HIGH, report.findings.first { it.id == "touch_metadata" }.severity)
        assertEquals(Verdict.AUTOMATED, report.verdict)
    }

    @Test
    fun gestureTapsWithoutTheFlagButWithVirtualDeviceAreStillMedium() {
        val report = BehaviorAnalyzer.analyze(spaced(8, 0, ::humanGap) { gestureTap(it, flags = 0) })
        assertEquals(Severity.MEDIUM, report.findings.first { it.id == "touch_metadata" }.severity)
    }

    @Test
    fun aMetronomicRhythmOrSuperhumanSpeedIsFlaggedEvenForRealTaps() {
        val slowButRegular = BehaviorAnalyzer.analyze(spaced(10, 0, { 1000L }, ::humanTap))
        assertTrue(ids(slowButRegular).toString(), "action_rhythm" in ids(slowButRegular))
        val tooFast = BehaviorAnalyzer.analyze(spaced(10, 0, { 120L }, ::humanTap))
        assertTrue("action_rhythm" in ids(tooFast))
    }

    @Test
    fun bulkTextWithNoKeyboardIsHigh() {
        val filler = spaced(6, 0, ::humanGap, ::humanTap)
        val text = listOf(Event(EventType.A11Y, 9_000, "edit", action = 0x200000), Event(EventType.TEXT, 9_000, "edit", len = 42, delta = 42))
        val report = BehaviorAnalyzer.analyze(filler + text)
        val f = report.findings.first { it.id == "text_without_keyboard" }
        assertEquals(Severity.HIGH, f.severity)
        assertTrue(f.evidence.contains("42"))
    }

    @Test
    fun typingThroughTheKeyboardWithHumanRhythmIsFine() {
        var t = 0L
        var len = 0
        val typing = (1..20).flatMap {
            t += 90L + rnd.nextLong(0, 260); len++
            listOf(Event(EventType.KEY, t, "edit"), Event(EventType.TEXT, t + 5, "edit", len = len, delta = 1))
        }
        val report = BehaviorAnalyzer.analyze(spaced(6, -10_000, ::humanGap, ::humanTap) + typing)
        assertFalse(ids(report).toString(), "text_without_keyboard" in ids(report) || "typing_cadence" in ids(report))
    }

    @Test
    fun incrementalTextWithAFixedFastCadenceIsFlagged() {
        var len = 0
        val typing = (1..20).flatMap { i -> len++; listOf(Event(EventType.IME, i * 25L, "edit"), Event(EventType.TEXT, i * 25L + 1, "edit", len = len, delta = 1)) }
        val report = BehaviorAnalyzer.analyze(spaced(6, 5_000, ::humanGap, ::humanTap) + typing)
        assertTrue(ids(report).toString(), "typing_cadence" in ids(report))
    }

    @Test
    fun incrementalTextWithNoKeyboardEventsIsStillCaught() {
        val typing = (1..12).map { Event(EventType.TEXT, it * 180L, "edit", len = it, delta = 1) }
        val report = BehaviorAnalyzer.analyze(spaced(6, 5_000, ::humanGap, ::humanTap) + typing)
        assertTrue("text_without_keyboard" in ids(report))
    }

    @Test
    fun scrollingWithoutAFingerIsHigh() {
        val scrolls = listOf(Event(EventType.SCROLL, 9_000, "list", dy = 300), Event(EventType.SCROLL, 9_400, "list", dy = 300))
        assertTrue("scroll_without_touch" in ids(BehaviorAnalyzer.analyze(spaced(6, 0, ::humanGap, ::humanTap) + scrolls)))
    }

    @Test
    fun scrollingWithAFingerIsNotFlagged() {
        val drag = listOf(Event(EventType.DOWN, 9_000, "list", x = 100f, y = 900f), Event(EventType.MOVE, 9_030, "list", x = 100f, y = 800f), Event(EventType.SCROLL, 9_040, "list", dy = 90), Event(EventType.UP, 9_100, "list", x = 100f, y = 700f))
        assertFalse("scroll_without_touch" in ids(BehaviorAnalyzer.analyze(spaced(8, 0, ::humanGap, ::humanTap) + drag)))
    }

    private fun swipe(t: Long, wobble: Boolean): List<Event> {
        val pts = (0..10).map { i ->
            val y = 1500f - i * 100f
            val x = 540f + if (wobble) (Math.sin(i * 1.3) * 22).toFloat() + rnd.nextInt(-4, 4) else 0f
            Event(if (i == 0) EventType.DOWN else if (i == 10) EventType.UP else EventType.MOVE, t + i * (if (wobble) 14L + rnd.nextInt(0, 9) else 16L), "pad", x, y, 540f, 800f, 0.5f, 0.05f, 1, 0x1002, 5, 0)
        }
        return pts
    }

    @Test
    fun rulerStraightSwipesAreFlaggedAndCurvedOnesAreNot() {
        val straight = BehaviorAnalyzer.analyze(spaced(4, 0, { 2000L }) { swipe(it, wobble = false) } + spaced(4, 9_000, ::humanGap, ::humanTap))
        assertTrue("swipe_linear" in ids(straight))
        val curved = BehaviorAnalyzer.analyze(spaced(4, 0, { 2000L }) { swipe(it, wobble = true) } + spaced(4, 9_000, ::humanGap, ::humanTap))
        assertFalse("swipe_linear" in ids(curved))
    }

    @Test
    fun scoreAddsUpByseverityAndCapsAt100() {
        val report = BehaviorAnalyzer.analyze(spaced(10, 0, { 100L }, ::gestureTap) + spaced(4, 5_000, { 100L }, ::actionClick))
        assertTrue(report.score in 45..100)
        assertEquals(report.findings.sumOf { it.severity.weight }.coerceAtMost(100), report.score)
        assertTrue("findings are ordered most severe first", report.findings.zipWithNext().all { (a, b) -> a.severity.weight >= b.severity.weight })
    }

    @Test
    fun aSessionSurvivesAJsonRoundTrip() {
        val session = Session("harness-api", 123L, "Pixel 8", humanTap(0), listOf(EnvSignal("adb", "platform", "low", "ADB on", "1")))
        val json = Json { encodeDefaults = false }
        val text = json.encodeToString(Session.serializer(), session)
        assertEquals(session, json.decodeFromString(Session.serializer(), text))
        assertNotNull(text)
    }
}
