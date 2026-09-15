package com.example.robocontrol.audio

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.listener.TextListener
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.entity.LangParamsEnum
import com.ainirobot.coreservice.client.speech.entity.TTSEntity
import com.ainirobot.coreservice.client.speech.entity.TTSParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Languages this class can speak. Codes come from the SDK's own [LangParamsEnum]. */
enum class TtsLanguage(internal val sdk: LangParamsEnum) {
    ENGLISH(LangParamsEnum.EN_US),
    GERMAN(LangParamsEnum.DE_DE)
}

/** Why a sentence was not (fully) spoken. */
sealed interface TtsFailure {
    /** [OrionStarTts.connect] was not called, or the connection is not up yet. */
    data object NotConnected : TtsFailure

    /** Empty or whitespace-only text. */
    data object EmptyText : TtsFailure

    /** Longer than [OrionStarTts.MAX_TEXT_LENGTH]; the speech service does not accept it. */
    data class TooLong(val length: Int) : TtsFailure

    /** The SDK refuses text that parses as JSON (checked in the jar: `SkillApi.isInvalidTtsText`). */
    data object LooksLikeJson : TtsFailure

    /** The speech service reported an error. The SDK gives no code or reason. */
    data object SpeechService : TtsFailure

    /** Speech was cut off by [OrionStarTts.stop] or by another sentence. */
    data object Interrupted : TtsFailure
}

/**
 * Progress of one spoken sentence. All methods are optional.
 *
 * Callbacks arrive on the SDK's dispatcher thread, NOT the main thread. Switch threads
 * before touching UI.
 */
interface TtsListener {
    fun onStarted() {}
    fun onFinished() {}
    fun onFailed(failure: TtsFailure) {}
}

/**
 * Text-to-speech through RobotOS's speech service (OrionStar [SkillApi]).
 *
 * Usage:
 * ```
 * val tts = OrionStarTts(applicationContext)
 * tts.connect()
 * // once tts.connected is true:
 * tts.speakEnglish("Hello, I am RoboGuard.")
 * tts.speakGerman("Hallo, ich bin RoboGuard.")
 * ```
 *
 * The language is set per sentence through the entity's `ttsParams` map, not through
 * `SkillApi.setTTSParams`. That way the robot's global speech settings are never changed.
 *
 * Constraints:
 *  - RobotOS only serves the SDK to the foreground app. From a background service,
 *    [connect] reports disabled and nothing is spoken.
 *  - A new sentence while one is playing: the SDK does not document whether it queues or
 *    interrupts. This class does not queue; await [speakAndWait] if order matters.
 *
 * UNVERIFIED on hardware (see CLAUDE.md, "TTS"):
 *  - whether `SpeechLanguage` expects the code name ("de_DE", used here) or the numeric code ("7")
 *  - whether a German voice is installed on the robot at all; check with [voicesFor]
 */
class OrionStarTts(private val context: Context) {

    private val skillApi = SkillApi()

    private val _connected = MutableStateFlow(false)

    /** True once the speech service connection is up; [speak] fails with NotConnected before that. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Connects to the speech service. Call once, e.g. in `onCreate`. Returns immediately;
     * watch [connected], or pass [onReady], which runs on the SDK thread once connected.
     */
    fun connect(onReady: () -> Unit = {}) {
        skillApi.connectApi(context, object : ApiListener {
            override fun handleApiConnected() {
                _connected.value = true
                onReady()
            }

            override fun handleApiDisconnected() { _connected.value = false }

            // Typically: the app is not in the foreground, or lacks the OrionStar permission.
            override fun handleApiDisabled() { _connected.value = false }
        })
    }

    /** Stops any speech and disconnects. Call in `onDestroy`. */
    fun disconnect() {
        stop()
        runCatching { skillApi.disconnectApi() }
        _connected.value = false
    }

    /** Speaks an English sentence. See [speak]. */
    fun speakEnglish(text: String, listener: TtsListener? = null): Boolean =
        speak(text, TtsLanguage.ENGLISH, listener)

    /** Speaks a German sentence. See [speak]. */
    fun speakGerman(text: String, listener: TtsListener? = null): Boolean =
        speak(text, TtsLanguage.GERMAN, listener)

    /**
     * Converts [text] to speech in [language].
     *
     * @return true if the text was handed to the speech service. False means it was rejected
     *         before that, and [listener] has already received [TtsListener.onFailed].
     */
    fun speak(text: String, language: TtsLanguage, listener: TtsListener? = null): Boolean {
        val invalid = validate(text)
        if (invalid != null) {
            listener?.onFailed(invalid)
            return false
        }

        val params = hashMapOf(TTSParams.SPEECHLANGUAGE to language.sdk.codeName)
        val entity = TTSEntity(text).apply { ttsParams = params }

        return runCatching { skillApi.playText(entity, textListener(listener)) }
            .onFailure { listener?.onFailed(TtsFailure.SpeechService) }
            .isSuccess
    }

    /**
     * Speaks and suspends until the sentence is finished.
     * Cancelling the coroutine stops the speech.
     *
     * @return null when spoken completely, otherwise the reason it was not
     */
    suspend fun speakAndWait(text: String, language: TtsLanguage): TtsFailure? =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { stop() }
            speak(text, language, object : TtsListener {
                override fun onFinished() { if (cont.isActive) cont.resume(null) }
                override fun onFailed(failure: TtsFailure) { if (cont.isActive) cont.resume(failure) }
            })
        }

    /** Stops the current sentence. Its listener receives [TtsFailure.Interrupted]. */
    fun stop() {
        runCatching { skillApi.stopTTS() }
    }

    /**
     * Voices the robot has for [language], as the raw string from the speech service,
     * or null if not connected or the call failed. The format is undocumented. An empty
     * or null result for GERMAN suggests German TTS is not installed.
     */
    fun voicesFor(language: TtsLanguage): String? {
        if (!_connected.value) return null
        return runCatching { skillApi.getSpokemanListByLanguage(language.sdk.codeName) }.getOrNull()
    }

    /** Checks done here so the caller gets a precise reason instead of the SDK's bare onError. */
    private fun validate(text: String): TtsFailure? = when {
        !_connected.value -> TtsFailure.NotConnected
        text.isBlank() -> TtsFailure.EmptyText
        text.length > MAX_TEXT_LENGTH -> TtsFailure.TooLong(text.length)
        text.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> TtsFailure.LooksLikeJson
        else -> null
    }

    /** Adapts the SDK's [TextListener] to [TtsListener]. */
    private fun textListener(listener: TtsListener?) = object : TextListener() {
        override fun onStart() { listener?.onStarted() }
        override fun onComplete() { listener?.onFinished() }
        override fun onStop() { listener?.onFailed(TtsFailure.Interrupted) }
        override fun onError() { listener?.onFailed(TtsFailure.SpeechService) }
    }

    companion object {
        /** Documented limit for SkillApi.playText. */
        const val MAX_TEXT_LENGTH = 1000
    }
}
