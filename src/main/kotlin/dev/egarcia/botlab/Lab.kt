package ga.egarcia.botlab

import android.content.Context
import android.os.Build
import android.os.SystemClock
import ga.egarcia.botlab.analysis.BehaviorAnalyzer
import ga.egarcia.botlab.analysis.Event
import ga.egarcia.botlab.analysis.EventType
import ga.egarcia.botlab.analysis.ReportText
import ga.egarcia.botlab.analysis.Session
import ga.egarcia.botlab.env.EnvironmentProbe
import kotlinx.serialization.json.Json
import java.io.File

/** Process-wide recorder of everything the lab's views observe. */
object Lab {
    private val lock = Any()
    private val events = ArrayList<Event>()

    @Volatile
    private var start = SystemClock.uptimeMillis()

    @Volatile
    var version = 0
        private set

    val json = Json { encodeDefaults = false; prettyPrint = true }

    fun now(): Long = SystemClock.uptimeMillis() - start

    fun rel(uptimeMillis: Long): Long = uptimeMillis - start

    fun add(event: Event) {
        synchronized(lock) { events += event }
        version++
    }

    fun events(): List<Event> = synchronized(lock) { events.toList() }

    fun reset() {
        synchronized(lock) { events.clear() }
        start = SystemClock.uptimeMillis()
        version++
    }

    fun click(view: String) = add(Event(EventType.CLICK, now(), view))
    fun a11y(view: String, action: Int) = add(Event(EventType.A11Y, now(), view, action = action))
    fun key(view: String) = add(Event(EventType.KEY, now(), view))
    fun ime(view: String, len: Int) = add(Event(EventType.IME, now(), view, len = len))
    fun text(view: String, len: Int, delta: Int) = add(Event(EventType.TEXT, now(), view, len = len, delta = delta))
    fun scroll(view: String, dy: Int) = add(Event(EventType.SCROLL, now(), view, dy = dy))

    // --- Sessions on disk ------------------------------------------------------------------------------------

    private fun dir(context: Context) = File(context.filesDir, "sessions").apply { mkdirs() }

    fun save(context: Context, label: String): File {
        val safe = label.trim().ifEmpty { "session" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val session = Session(safe, System.currentTimeMillis(), "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})", events(), EnvironmentProbe.collect(context))
        // A readable report next to the raw session, so results can be read without the app.
        File(dir(context), "$safe.report.txt").writeText(ReportText.render(BehaviorAnalyzer.analyze(session.events), session.env))
        return File(dir(context), "$safe.json").also { it.writeText(json.encodeToString(Session.serializer(), session)) }
    }
}
