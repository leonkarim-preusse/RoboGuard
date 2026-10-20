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

/** Progress of one spoken sentence. Callbacks run on an SDK thread, not on the main thread. */
interface TtsListener {
    /** Called when the robot starts to speak the sentence. */
    fun onStarted() {}
    /** Called when the sentence was spoken completely. */
    fun onFinished() {}
    /** Called when the sentence could not be spoken; [failure] says why. */
    fun onFailed(failure: TtsFailure) {}
}

/**
 * Text-to-speech through RobotOS's speech service ([SkillApi]). The language and voice are set per sentence, so the
 * robot's global speech settings are not changed. Speech requires SDK control.
 * @param context used to connect to the speech service
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
    /**
     * Speaks [text] in the language the text file selects (`meta.languages.<current>.speechLanguage`), so switching the
     * language in `assets/texts/texts.json` also switches the voice.
     */
    fun speakConfigured(text: String, listener: TtsListener? = null): Boolean =
        speak(text, if (com.example.robocontrol.text.UiText.speechLanguage.startsWith("en")) TtsLanguage.ENGLISH else TtsLanguage.GERMAN,
            listener, voice = com.example.robocontrol.text.UiText.speechVoice
                ?: TtsVoices.voiceFor(context, com.example.robocontrol.text.UiText.speechLanguage))

    /** Speaks [text] with the German voice. Kept for the test screens; product sentences use [speakConfigured]. */
    fun speakGerman(text: String, listener: TtsListener? = null): Boolean =
        speak(text, TtsLanguage.GERMAN, listener)

    /**
     * Speaks [text].
     * @param text text to speak
     * @param language language of the text
     * @param listener receives progress and the result
     * @param voice speaker role; null = the robot's default voice
     * @return true if the text was passed to the speech service; false if it was rejected (the listener was told)
     */
    fun speak(text: String, language: TtsLanguage, listener: TtsListener? = null, voice: String? = null): Boolean {
        val invalid = validate(text)
        if (invalid != null) {
            listener?.onFailed(invalid)
            return false
        }

        val params = hashMapOf(TTSParams.SPEECHLANGUAGE to language.sdk.codeName)
        // The language alone does not change the voice: the speech service keeps the robot-wide speaker (a German WaveNet
        // voice here, "de-de-wavenet-c") and only told it the text is English (robot log 2026-10-19). The speaker is set per
        // sentence as well, so the robot-wide setting stays untouched.
        if (!voice.isNullOrBlank()) params[TTSParams.SPEAKERROLE] = voice
        val entity = TTSEntity(text).apply { ttsParams = params }

        return runCatching { skillApi.playText(entity, textListener(listener)) }
            .onFailure { listener?.onFailed(TtsFailure.SpeechService) }
            .isSuccess
    }

    /**
     * Speaks [text] and suspends until it has finished; cancelling the coroutine stops the speech.
     * @param text text to speak
     * @param language language of the text
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
     * Voices available for [language], as returned by the speech service.
     * @param language the language
     * @return the raw answer, or null if not connected or the call failed
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
        /** Maximum text length accepted by `SkillApi.playText`. */
        const val MAX_TEXT_LENGTH = 1000
    }
}
