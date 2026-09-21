package com.example.testing.voiceprobe

import android.content.Context
import android.os.SystemClock
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.RobotApi
import com.example.robocontrol.system.RobotApiConnection
import com.ainirobot.coreservice.client.listener.TextListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.entity.LangParamsEnum
import com.ainirobot.coreservice.client.speech.entity.TTSEntity
import com.ainirobot.coreservice.client.speech.entity.TTSParams
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsLanguage
import com.example.robocontrol.audio.TtsListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hardware test for [OrionStarTts]: does text-to-speech work on the robot, in English and German?
 *
 * Some checks are automatic and log PASS or FAIL. Others can only be judged by listening; those
 * end with "Press ✔ or ✘", and [verdict] writes the answer into the log file, so the result of a
 * session is complete without extra notes.
 *
 * Tests, in the recommended order:
 *  0. [saySentences]: simply let the robot say a few English and German sentences.
 *  1. [listVoices]: are English and German voices installed?
 *  2. [english], [german]: does each language sound right? How long until speech starts and ends?
 *  3. [germanNumericCode]: the same German sentence with SpeechLanguage "7" instead of "de_DE".
 *     Answers the open question of which format the speech service expects (CLAUDE.md, "TTS").
 *  4. [validation]: invalid text is rejected immediately and nothing is spoken (automatic).
 *  5. [interrupt]: [OrionStarTts.stop] cuts speech off and reports Interrupted (automatic).
 *  6. [overlap]: does a second sentence queue, interrupt the first, or get rejected? (documents behaviour)
 *  7. [playStatus]: which `getTtsPlayStatus` values mean "robot is speaking"? A voice detector
 *     needs that to ignore the robot's own voice.
 *
 * Besides [OrionStarTts] itself, a second, raw [SkillApi] connection is kept for the two things
 * OrionStarTts deliberately does not offer: sending the numeric language code, and reading the
 * play status.
 *
 * All suspend functions block for up to [TIMEOUT_MS] and must not run on the main thread.
 */
class TtsProbe(private val context: Context, private val log: ProbeLog) {

    private val tts = OrionStarTts(context)
    private val rawApi = SkillApi()

    @Volatile
    private var rawConnected = false

    /** Name of the most recent test, used to label the ✔/✘ verdict. */
    @Volatile
    private var lastTest: String? = null

    /**
     * Reports whether RobotOS grants (onRecovery) or withdraws (onSuspend) SDK control from this app.
     * Only the request type is logged, never request text, which can contain speech.
     */
    private val moduleCallback = object : ModuleCallbackApi() {
        override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean {
            log.i(TAG, "RobotOS request: $reqType")
            return false
        }

        override fun onSuspend() = log.i(TAG, "RobotOS SUSPENDED this app: another app holds SDK control, TTS will not work")
        override fun onRecovery() = log.i(TAG, "RobotOS RECOVERED this app: SDK control granted")
    }

    /**
     * Connects both [OrionStarTts] and the raw [SkillApi]. Call once when the screen opens.
     * If "disabled" is logged, the app is not allowed to use the SDK (usually: not in the
     * foreground) and every test below will fail with NotConnected.
     */
    fun connect() {
        log.section("Text-to-speech")
        val t0 = now()
        tts.connect { log.i(TAG, "OrionStarTts connected after ${now() - t0} ms") }
        rawApi.connectApi(context, object : ApiListener {
            override fun handleApiConnected() {
                rawConnected = true
                log.i(TAG, "raw SkillApi connected after ${now() - t0} ms")
            }

            override fun handleApiDisconnected() {
                rawConnected = false
                log.i(TAG, "raw SkillApi disconnected")
            }

            override fun handleApiDisabled() {
                rawConnected = false
                log.i(TAG, "raw SkillApi disabled: app not in foreground or not authorized, TTS will not work")
            }
        })

        // CoreService only serves its "active app module"; being the foreground activity is not enough
        // (on the robot, mActiveAppModule stayed com.ainirobot.maptool while this screen was on top and
        // playText got no callback). connectServer + setCallback is the SDK's documented init order and is
        // expected to register this app as a module; onRecovery/onSuspend above show whether it worked.
        RobotApiConnection.connect(context, object : ApiListener {
            override fun handleApiConnected() {
                RobotApi.getInstance().setCallback(moduleCallback)
                log.i(TAG, "RobotApi connected after ${now() - t0} ms, module callback registered")
                logSdkControl()
            }

            override fun handleApiDisconnected() = log.i(TAG, "RobotApi disconnected")
            override fun handleApiDisabled() = log.i(TAG, "RobotApi disabled: this app is not the active app")
        })
    }

    /** Stops speech and closes all connections. Call when the screen closes. */
    fun disconnect() {
        tts.disconnect()
        runCatching { rawApi.disconnectApi() }
        // No RobotApi.disconnectApi(): that connection is shared by the whole app (RobotApiConnection).
        rawConnected = false
    }

    /** Stops whatever is currently being spoken. */
    fun stopSpeech() = tts.stop()

    /** Records the tester's judgement of the most recent test in the log. */
    fun verdict(asExpected: Boolean) {
        val test = lastTest ?: return log.i(TAG, "no test has run yet")
        log.i(TAG, "VERDICT $test: ${if (asExpected) "as expected" else "WRONG"}")
    }

    /**
     * Test 0, the simple smoke test: the robot says a few everyday sentences, English first,
     * then German, one after another through [OrionStarTts.speakAndWait]. If this works, TTS works
     * in principle; the numbered tests then check the details.
     */
    suspend fun saySentences() {
        begin("say sentences", "Expect: ${SMOKE_SENTENCES.size} sentences one after another, English then German, each spoken completely.")
        if (!tts.connected.value) return log.i(TAG, "FAIL: not connected")
        for ((language, text) in SMOKE_SENTENCES) {
            log.i(TAG, "$language: \"$text\"")
            val t0 = now()
            // withTimeoutOrNull cancels speakAndWait on timeout, which also stops the speech.
            val outcome = withTimeoutOrNull(TIMEOUT_MS) {
                tts.speakAndWait(text, language)?.let { "failed: $it" } ?: "finished"
            } ?: "no callback within ${TIMEOUT_MS / 1000} s"
            log.i(TAG, "  -> $outcome after ${now() - t0} ms")
            delay(PAUSE_BETWEEN_SENTENCES_MS)
        }
        askForVerdict()
    }

    /**
     * Test 1: logs the voices the speech service reports for each language.
     * The format is undocumented, so the raw string is shown (voice names, no speech content).
     */
    fun listVoices() {
        begin("voices", "Expect a non-empty result for both languages. Empty or null for GERMAN means no German voice is installed.")
        if (!tts.connected.value) return log.i(TAG, "FAIL: not connected")
        for (language in TtsLanguage.entries) {
            val voices = tts.voicesFor(language)
            log.i(TAG, "voicesFor($language): ${ProbeLog.describe(voices)} ${voices?.take(VOICES_PREVIEW_CHARS) ?: ""}")
        }
    }

    /** Test 2a: one English sentence through [OrionStarTts.speakEnglish]'s code path. */
    suspend fun english() = speakAndMeasure(
        name = "english",
        language = TtsLanguage.ENGLISH,
        text = ENGLISH_SENTENCE,
        expectation = "Expect: the sentence in English, clearly understandable."
    )

    /** Test 2b: one German sentence with umlauts and ß, sent with the code name "de_DE". */
    suspend fun german() = speakAndMeasure(
        name = "german (de_DE)",
        language = TtsLanguage.GERMAN,
        text = GERMAN_SENTENCE,
        expectation = "Expect: a German voice, correct ü/ö/ß. English pronunciation of German words means the language parameter was ignored."
    )

    /**
     * Test 3: the same German sentence, but with the numeric language code ("7") and bypassing
     * [OrionStarTts]. Compare with test 2b:
     *  - only 2b sounds German: OrionStarTts is correct as it is
     *  - only this one sounds German: OrionStarTts must send `codeValue` instead of `codeName`
     *  - both sound German: either format works
     *  - neither: German TTS is probably not installed (see test 1)
     */
    suspend fun germanNumericCode() {
        val code = LangParamsEnum.DE_DE.codeValue.toString()
        begin("german (numeric $code)", "Same sentence with SpeechLanguage=\"$code\". Compare with the de_DE test.")
        if (!rawConnected) return log.i(TAG, "FAIL: raw SkillApi not connected")
        log.i(TAG, "text: \"$GERMAN_SENTENCE\"")

        val t0 = now()
        val started = CompletableDeferred<Long>()
        val result = CompletableDeferred<TtsFailure?>()
        val entity = TTSEntity(GERMAN_SENTENCE).apply {
            ttsParams = hashMapOf(TTSParams.SPEECHLANGUAGE to code)
        }
        runCatching {
            rawApi.playText(entity, object : TextListener() {
                override fun onStart() { started.complete(now() - t0) }
                override fun onComplete() { result.complete(null) }
                override fun onStop() { result.complete(TtsFailure.Interrupted) }
                override fun onError() { result.complete(TtsFailure.SpeechService) }
            })
        }.onFailure {
            log.i(TAG, "FAIL: playText threw $it")
            return
        }
        reportOutcome(t0, started, result)
        askForVerdict()
    }

    /**
     * Test 4 (automatic): text [OrionStarTts] must reject before it reaches the speech service.
     * Each case must return false and report the matching [TtsFailure]. If anything is heard,
     * the check did not stop it, which is a FAIL even if the log says PASS.
     */
    fun validation() {
        begin("validation", "Expect three PASS lines and NO sound.")
        if (!tts.connected.value) return log.i(TAG, "FAIL: not connected (would mask the real checks)")

        val tooLong = OrionStarTts.MAX_TEXT_LENGTH + 1
        val cases = listOf(
            Triple("blank text", "   ", TtsFailure.EmptyText),
            Triple("$tooLong characters", "a".repeat(tooLong), TtsFailure.TooLong(tooLong)),
            Triple("JSON-looking text", "{\"text\": \"hello\"}", TtsFailure.LooksLikeJson)
        )
        for ((name, text, expected) in cases) {
            var reported: TtsFailure? = null
            val accepted = tts.speak(text, TtsLanguage.ENGLISH, object : TtsListener {
                override fun onFailed(failure: TtsFailure) { reported = failure }
            })
            val pass = !accepted && reported == expected
            log.i(TAG, "${if (pass) "PASS" else "FAIL"} $name: returned $accepted, reported $reported, expected $expected")
        }
        askForVerdict()
    }

    /**
     * Test 5 (automatic): starts a long sentence, calls [OrionStarTts.stop] after
     * [INTERRUPT_AFTER_MS], and expects [TtsFailure.Interrupted] within [STOP_TIMEOUT_MS].
     * Listen too: the voice must actually stop, not just the callback.
     */
    suspend fun interrupt() {
        begin("interrupt", "Expect: speech starts, stops after about ${INTERRUPT_AFTER_MS / 1000.0} s, result Interrupted.")
        val t0 = now()
        val started = CompletableDeferred<Long>()
        val result = CompletableDeferred<TtsFailure?>()
        tts.speak(LONG_TEXT, TtsLanguage.ENGLISH, deferredListener(t0, started, result))

        if (withTimeoutOrNull(TIMEOUT_MS) { started.await() } == null) {
            log.i(TAG, "FAIL: speech never started within ${TIMEOUT_MS / 1000} s")
            tts.stop()
            return
        }
        delay(INTERRUPT_AFTER_MS)
        log.i(TAG, "calling stop() at ${now() - t0} ms")
        tts.stop()

        val completed = withTimeoutOrNull(STOP_TIMEOUT_MS) { result.await(); true } ?: false
        if (!completed) {
            log.i(TAG, "FAIL: no callback within ${STOP_TIMEOUT_MS / 1000} s after stop()")
        } else {
            val failure = result.await()
            val pass = failure == TtsFailure.Interrupted
            log.i(TAG, "${if (pass) "PASS" else "FAIL"}: result ${failure ?: "finished"} at ${now() - t0} ms, expected Interrupted")
        }
        askForVerdict()
    }

    /**
     * Test 6: documents what the SDK does with a second sentence while one is playing.
     * There is no right or wrong here; the log shows the order of events:
     *  - A Interrupted, then B finished: a new sentence interrupts the current one
     *  - A finished, then B started:     sentences are queued
     *  - B failed straight away:         a second sentence is rejected while speaking
     */
    suspend fun overlap() {
        begin("overlap", "Documents behaviour: does sentence B queue, interrupt A, or get rejected?")
        val t0 = now()
        val aStarted = CompletableDeferred<Unit>()
        val aDone = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()

        tts.speak(OVERLAP_A, TtsLanguage.ENGLISH, eventLogger("A", t0, aStarted, aDone))
        if (withTimeoutOrNull(TIMEOUT_MS) { aStarted.await() } == null) {
            log.i(TAG, "A never started within ${TIMEOUT_MS / 1000} s, aborting")
            tts.stop()
            return
        }
        delay(OVERLAP_DELAY_MS)
        log.i(TAG, "B requested at ${now() - t0} ms, while A is speaking")
        tts.speak(OVERLAP_B, TtsLanguage.ENGLISH, eventLogger("B", t0, null, bDone))

        val bothDone = withTimeoutOrNull(TIMEOUT_MS) { aDone.await(); bDone.await(); true } ?: false
        if (!bothDone) {
            log.i(TAG, "not both sentences ended within ${TIMEOUT_MS / 1000} s, stopping")
            tts.stop()
        }
        log.i(TAG, "Read the event order above to classify: interrupt, queue or reject.")
    }

    /**
     * Test 7: samples `getTtsPlayStatus` every [SAMPLE_MS] from before a sentence starts until one
     * second after it ends. The values that only appear between "started" and "finished" are the
     * ones meaning "robot is speaking".
     */
    suspend fun playStatus() {
        begin("play status", "Documents which getTtsPlayStatus values mean 'robot is speaking'.")
        if (!rawConnected) return log.i(TAG, "FAIL: raw SkillApi not connected")
        val t0 = now()
        val done = CompletableDeferred<Unit>()

        coroutineScope {
            val sampler = launch {
                while (isActive) {
                    log.i(TAG, "status=${readPlayStatus()} at ${now() - t0} ms")
                    delay(SAMPLE_MS)
                }
            }
            delay(SAMPLE_MS * 2) // a few samples before speech starts
            tts.speak(ENGLISH_SENTENCE, TtsLanguage.ENGLISH, eventLogger("sentence", t0, null, done))
            withTimeoutOrNull(TIMEOUT_MS) { done.await() }
            delay(AFTER_END_SAMPLING_MS)
            sampler.cancel()
        }
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Shared body of tests 2a and 2b: speak, then log time to start, time to end and the result.
     */
    private suspend fun speakAndMeasure(name: String, language: TtsLanguage, text: String, expectation: String) {
        begin(name, expectation)
        log.i(TAG, "text: \"$text\"")
        val t0 = now()
        val started = CompletableDeferred<Long>()
        val result = CompletableDeferred<TtsFailure?>()
        val accepted = tts.speak(text, language, deferredListener(t0, started, result))
        log.i(TAG, "speak() returned $accepted")
        reportOutcome(t0, started, result)
        askForVerdict()
    }

    /** Listener that completes [started] with the start delay and [result] with the outcome. */
    private fun deferredListener(
        t0: Long,
        started: CompletableDeferred<Long>,
        result: CompletableDeferred<TtsFailure?>
    ): TtsListener = object : TtsListener {
        override fun onStarted() { started.complete(now() - t0) }
        override fun onFinished() { result.complete(null) }
        override fun onFailed(failure: TtsFailure) { result.complete(failure) }
    }

    /** Listener that logs every event of one sentence with a timestamp relative to [t0]. */
    private fun eventLogger(
        label: String,
        t0: Long,
        started: CompletableDeferred<Unit>?,
        done: CompletableDeferred<Unit>
    ): TtsListener = object : TtsListener {
        override fun onStarted() {
            log.i(TAG, "$label started at ${now() - t0} ms")
            started?.complete(Unit)
        }

        override fun onFinished() {
            log.i(TAG, "$label finished at ${now() - t0} ms")
            done.complete(Unit)
        }

        override fun onFailed(failure: TtsFailure) {
            log.i(TAG, "$label failed ($failure) at ${now() - t0} ms")
            done.complete(Unit)
        }
    }

    /**
     * Waits up to [TIMEOUT_MS] for [result] and logs the outcome. A timeout means the speech
     * service never called back; speech is stopped in that case so the next test starts clean.
     */
    private suspend fun reportOutcome(t0: Long, started: CompletableDeferred<Long>, result: CompletableDeferred<TtsFailure?>) {
        val completed = withTimeoutOrNull(TIMEOUT_MS) { result.await(); true } ?: false
        val startedAfter = if (started.isCompleted) "${started.await()} ms" else "never"
        if (!completed) {
            log.i(TAG, "RESULT: no callback within ${TIMEOUT_MS / 1000} s (started: $startedAfter), stopping")
            tts.stop()
            return
        }
        val failure = result.await()
        log.i(TAG, "RESULT: ${failure?.let { "failed: $it" } ?: "finished"}, started after $startedAfter, total ${now() - t0} ms")
    }

    private fun readPlayStatus(): String =
        runCatching { rawApi.getTtsPlayStatus().toString() }.getOrElse { "error ${it.javaClass.simpleName}" }

    /** Marks the start of a test in the log and remembers its name for [verdict]. */
    private fun begin(name: String, expectation: String) {
        lastTest = name
        log.section("TTS: $name")
        log.i(TAG, expectation)
        logSdkControl()
    }

    /**
     * Logs whether RobotOS currently grants this app SDK control. If "no", every SDK call is silently ignored.
     * The usual cause: the screen was started via adb instead of the robot's home launcher icon.
     */
    private fun logSdkControl() {
        val active = runCatching { RobotApi.getInstance().isApiConnectedService() && RobotApi.getInstance().isActive() }
            .getOrDefault(false)
        log.i(TAG, if (active) "SDK control active: yes" else "SDK control active: NO (start this screen from the robot's home launcher icon)")
    }

    private fun askForVerdict() = log.i(TAG, "Did it behave as expected? Press ✔ or ✘.")

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "tts"

        /** Upper bound for any single sentence; far longer than every test text takes to speak. */
        const val TIMEOUT_MS = 30_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val INTERRUPT_AFTER_MS = 1_500L
        const val OVERLAP_DELAY_MS = 500L
        const val SAMPLE_MS = 300L
        const val AFTER_END_SAMPLING_MS = 1_000L
        const val VOICES_PREVIEW_CHARS = 300

        /** Short pause so separate sentences are audibly separate. */
        const val PAUSE_BETWEEN_SENTENCES_MS = 700L

        /** Sentences for [saySentences]: the kind of thing RoboGuard will actually say. */
        val SMOKE_SENTENCES = listOf(
            TtsLanguage.ENGLISH to "Hello, my name is RoboGuard.",
            TtsLanguage.ENGLISH to "I respect your privacy. My camera is switched off.",
            TtsLanguage.ENGLISH to "Please tell me if I should leave this room.",
            TtsLanguage.GERMAN to "Hallo, mein Name ist RoboGuard.",
            TtsLanguage.GERMAN to "Ich achte auf Ihre Privatsphäre. Meine Kamera ist ausgeschaltet.",
            TtsLanguage.GERMAN to "Sagen Sie mir bitte, wenn ich diesen Raum verlassen soll."
        )

        const val ENGLISH_SENTENCE = "Hello, I am RoboGuard. The camera in this room is switched off."

        /** Umlauts and ß on purpose: an English voice mispronounces them audibly. */
        const val GERMAN_SENTENCE = "Hallo, ich bin RoboGuard. Die Kamera in diesem Zimmer ist ausgeschaltet. Schöne Grüße!"

        /** Long enough that there is still speech left to interrupt after 1.5 s. */
        val LONG_TEXT = List(3) {
            "This is a long test sentence. It keeps going so that the stop command has something to interrupt."
        }.joinToString(" ")

        const val OVERLAP_A = "This is sentence A. It is deliberately longer than sentence B, so that B arrives while A is still speaking."
        const val OVERLAP_B = "This is sentence B."
    }
}
