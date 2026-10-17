package ga.egarcia.botlab.analysis

import kotlinx.serialization.Serializable

object EventType {
    const val DOWN = "down"
    const val MOVE = "move"
    const val UP = "up"
    const val CANCEL = "cancel"

    /** A click was performed on a view (from a touch, a key, or an accessibility action). */
    const val CLICK = "click"

    /** `View.performAccessibilityAction` was called: an accessibility service or TalkBack acted on the view. */
    const val A11Y = "a11y_action"

    /** The text of a field changed. */
    const val TEXT = "text"

    /** A key event reached the app (hardware or injected). */
    const val KEY = "key"

    /** The on-screen keyboard (an input method) committed or composed text. */
    const val IME = "ime"
    const val SCROLL = "scroll"
}

/**
 * One observation an ordinary app can make about how it is being operated. Deliberately flat so a session is a
 * plain list that serializes to JSON. Times are milliseconds since the session started.
 */
@Serializable
data class Event(
    val type: String,
    val t: Long,
    val view: String = "",
    // Touch: position on screen, and the centre of the view it landed in (to measure how centred taps are).
    val x: Float = 0f,
    val y: Float = 0f,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val pressure: Float = 0f,
    val size: Float = 0f,
    val toolType: Int = 0,
    val source: Int = 0,
    val deviceId: Int = 0,
    val flags: Int = 0,
    // Accessibility action id, text length after a change, characters added, scroll distance.
    val action: Int = 0,
    val len: Int = 0,
    val delta: Int = 0,
    val dy: Int = 0,
)

/** One thing the environment reveals (an enabled accessibility service, a RASP SDK verdict, ...). */
@Serializable
data class EnvSignal(
    val id: String,
    /** platform | freeRASP */
    val source: String,
    /** info | low | medium | high */
    val severity: String,
    val title: String,
    val detail: String = "",
)

@Serializable
data class Session(
    val label: String,
    val savedAtMs: Long,
    val device: String,
    val events: List<Event>,
    val env: List<EnvSignal>,
)
