package com.example.robocontrol.movement

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Passes the question "may the robot temporarily cross this private area?" from [MapNavigation] to the popup
 * [PrivacyOverrideActivity] or the phone, and the answer back. At most one question is open; it ends with exactly one
 * [Outcome] (allowed, denied, superseded, cancelled or expired after [MAX_AGE_MS]).
 */
object PrivacyOverridePrompt {

    /** How a crossing question ended. */
    enum class Outcome { ALLOWED, DENIED, SUPERSEDED, CANCELLED, EXPIRED }

    /**
     * Answer to a crossing question.
     * @property outcome how the question ended
     * @property minutes allowed minutes; only for [Outcome.ALLOWED]
     * @property answeredBy who answered (robot screen or phone name)
     */
    class Answer(val outcome: Outcome, val minutes: Int? = null, val answeredBy: String? = null)

    /**
     * An open crossing question.
     * @property id identifier of the question
     * @property zoneName private area the robot would cross
     * @property createdAt time the question was asked (elapsedRealtime)
     */
    class Request internal constructor(
        val id: Long,
        val zoneName: String,
        val createdAt: Long,
        internal val onAnswer: (Answer) -> Unit
    )

    /** A question older than this is closed without an answer. */
    const val MAX_AGE_MS = 90_000L

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var nextId = 0L

    /** Id of the question a popup is already showing, so a recreated screen does not open a second one. */
    @Volatile
    var shownId = -1L

    /** Opens a question; [onAnswer] gets exactly one [Answer]. Returns the request id. */
    fun ask(zoneName: String, onAnswer: (Answer) -> Unit): Long {
        val (replaced, request) = synchronized(this) {
            val old = _pending.value
            val r = Request(++nextId, zoneName, SystemClock.elapsedRealtime(), onAnswer)
            _pending.value = r
            old to r
        }
        replaced?.onAnswer?.invoke(Answer(Outcome.SUPERSEDED))
        return request.id
    }

    /**
     * Answers question [id] (ignored if it is no longer open): [minutes] allowed, or null for "no".
     * [answeredBy] is written into the audit log as the person who granted it.
     */
    fun answer(id: Long, minutes: Int?, answeredBy: String = "person at robot screen") {
        val request = take { it.id == id } ?: return
        request.onAnswer(if (minutes == null) Answer(Outcome.DENIED, answeredBy = answeredBy) else Answer(Outcome.ALLOWED, minutes, answeredBy))
    }

    /** Closes question [id] if it is older than [MAX_AGE_MS]. */
    fun expireIfOld(now: Long = SystemClock.elapsedRealtime()) {
        val request = take { now - it.createdAt >= MAX_AGE_MS } ?: return
        request.onAnswer(Answer(Outcome.EXPIRED))
    }

    /** Closes any open question without an answer (STOP, a new drive, the screen closing). The popup then closes itself. */
    fun cancel() {
        val request = take { true } ?: return
        request.onAnswer(Answer(Outcome.CANCELLED))
    }

    /** Removes and returns the open question if it meets [condition]; null otherwise. */
    private fun take(condition: (Request) -> Boolean): Request? = synchronized(this) {
        val r = _pending.value?.takeIf(condition) ?: return null
        _pending.value = null
        r
    }
}
