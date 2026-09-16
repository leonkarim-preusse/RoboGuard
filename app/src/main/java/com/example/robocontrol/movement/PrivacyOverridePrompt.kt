package com.example.robocontrol.movement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands the question "may the robot temporarily cross this private area?" from [MapNavigation] to the popup
 * [PrivacyOverrideActivity] and the answer back. Both live in the same process; at most one question is open.
 *
 * A new question replaces an unanswered older one (the older one counts as "no").
 */
object PrivacyOverridePrompt {

    /** @property zoneName the private area the robot would have to cross */
    class Request internal constructor(val id: Long, val zoneName: String, internal val onAnswer: (minutes: Int?) -> Unit)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var nextId = 0L

    /** Opens a question; [onAnswer] gets the allowed minutes, or null for "no". Returns the request id. */
    fun ask(zoneName: String, onAnswer: (minutes: Int?) -> Unit): Long {
        val (replaced, request) = synchronized(this) {
            val old = _pending.value
            val r = Request(++nextId, zoneName, onAnswer)
            _pending.value = r
            old to r
        }
        replaced?.onAnswer?.invoke(null)
        return request.id
    }

    /** Answers question [id] (ignored if it is no longer open): [minutes] allowed, or null for "no". */
    fun answer(id: Long, minutes: Int?) {
        val request = synchronized(this) {
            val r = _pending.value?.takeIf { it.id == id } ?: return
            _pending.value = null
            r
        }
        request.onAnswer(minutes)
    }

    /** Closes any open question without an answer, e.g. when the map screen closes. The popup then closes itself. */
    fun cancel() {
        synchronized(this) { _pending.value = null }
    }
}
