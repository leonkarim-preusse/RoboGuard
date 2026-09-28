package com.example.robocontrol.conversation

import android.util.Log
import kotlin.math.max

/**
 * Load counters of the audio path, for the performance recording (`performance/record.py`).
 *
 * Every detector runs the same loop — read 10 ms of microphone, ask the voice-activity gate, and every few seconds turn
 * collected speech into one embedding — but until now nothing said how much of that time the robot actually fed audio
 * into a network, or what a single step costs on this CPU. These counters answer exactly that and nothing else:
 * how large a share of the window counted as speech, how many pieces went into the speaker network, and the average
 * time of one embedding and one voice-activity chunk.
 *
 * Privacy: numbers only. No audio, no similarity, no decision and nothing that could describe a person; the line says
 * how busy the machine was, not who was in the room.
 */
internal class AudioLoad(private val tag: String, private val settings: String = "") {

    private var windowStartMs = -1L
    private var frames = 0
    private var speechFrames = 0
    private var pieces = 0
    private var embedNs = 0L
    private var vadNs = 0L
    private var vadChunks = 0
    private var analysisNs = 0L
    private var analysisFrames = 0
    private var sileroOnly = 0
    private var levelOnly = 0
    private var loudFrames = 0
    private var maxLevelDb = Double.NEGATIVE_INFINITY
    private var levelSum = 0.0
    private var levelFrames = 0
    private var probSum = 0.0
    private var maxProb = 0.0
    private var probFrames = 0
    private var windowStartWallMs = 0L
    private var lastSettingsMs = Long.MIN_VALUE / 2

    /**
     * One audio frame (a 10 ms hop): whether the gate counted it as speech, and what each half of the gate said on its
     * own. The two disagreements are counted separately, because they answer the question the loudness floor exists for:
     * how often does Silero hear speech that the floor throws away (`silero-only`), and how often is a frame loud enough
     * while Silero says it is not speech (`level-only`)?
     */
    fun frame(
        speech: Boolean,
        silero: Boolean = false,
        loud: Boolean = false,
        levelDb: Double = Double.NEGATIVE_INFINITY,
        probability: Double = -1.0
    ) {
        frames++
        if (speech) speechFrames++
        if (silero && !loud) sileroOnly++
        if (loud && !silero) levelOnly++
        if (loud) loudFrames++
        if (levelDb > maxLevelDb) maxLevelDb = levelDb
        if (levelDb.isFinite()) { levelSum += levelDb; levelFrames++ }
        if (probability >= 0) {
            probSum += probability
            probFrames++
            if (probability > maxProb) maxProb = probability
        }
    }

    /** One voice-activity chunk took [nanos] ns. */
    fun vad(nanos: Long) {
        vadNs += nanos
        vadChunks++
    }

    /** One piece of speech was turned into an embedding in [nanos] ns. */
    fun piece(nanos: Long) {
        pieces++
        embedNs += nanos
    }

    /** Per-frame analysis that is not the gate and not an embedding (the change detector's MFCC and window statistics). */
    fun analysis(nanos: Long) {
        analysisNs += nanos
        analysisFrames++
    }

    /**
     * Logs one summary when [WINDOW_MS] of audio have passed. [timeMs] is the detector's own audio clock (samples read),
     * so the windows follow the microphone rather than the wall clock.
     */
    fun tick(timeMs: Long) {
        if (windowStartMs < 0) {
            windowStartMs = timeMs
            windowStartWallMs = System.currentTimeMillis()
            return
        }
        if (timeMs - windowStartMs < WINDOW_MS) return
        // The settings are repeated now and then, so a recording that starts after the detector still knows what it was
        // running with (performance/record.py clears the log when it starts).
        if (settings.isNotEmpty() && timeMs - lastSettingsMs >= SETTINGS_EVERY_MS) {
            lastSettingsMs = timeMs
            Log.i(tag, "settings: $settings")
        }
        val seconds = (timeMs - windowStartMs) / 1000.0
        Log.i(tag, ("audio per %.1f s: speech %d %% · pieces %d · embedding %.0f ms · vad %.2f ms per chunk" +
            " · analysis %.3f ms per frame · gate: silero-only %d, level-only %d" +
            " · level mean %.0f dB, max %.0f dB, above the floor %d %%" +
            " · silero mean %.2f, max %.2f · audio/clock %.2f").format(
            seconds,
            if (frames > 0) speechFrames * 100 / frames else 0,
            pieces,
            if (pieces > 0) embedNs / 1_000_000.0 / pieces else 0.0,
            if (vadChunks > 0) vadNs / 1_000_000.0 / vadChunks else 0.0,
            if (analysisFrames > 0) analysisNs / 1_000_000.0 / analysisFrames else 0.0,
            sileroOnly, levelOnly,
            if (levelFrames > 0) levelSum / levelFrames else Double.NEGATIVE_INFINITY,
            maxLevelDb,
            if (frames > 0) loudFrames * 100 / frames else 0,
            if (probFrames > 0) probSum / probFrames else 0.0,
            maxProb,
            // Audio time per wall-clock time: below 1 means the microphone is producing faster than this thread reads it,
            // i.e. the AudioRecord buffer is running over and speech is being LOST.
            (timeMs - windowStartMs).toDouble() / max(System.currentTimeMillis() - windowStartWallMs, 1L)))
        windowStartMs = timeMs
        windowStartWallMs = System.currentTimeMillis()
        frames = 0; speechFrames = 0; pieces = 0; embedNs = 0L; vadNs = 0L; vadChunks = 0
        analysisNs = 0L; analysisFrames = 0; sileroOnly = 0; levelOnly = 0
        loudFrames = 0; maxLevelDb = Double.NEGATIVE_INFINITY
        levelSum = 0.0; levelFrames = 0; probSum = 0.0; maxProb = 0.0; probFrames = 0
    }

    companion object {
        /** Same window as the calendar detection's summary, so both lines line up in the graphs. */
        const val WINDOW_MS = 2000L

        /** How often the settings line is repeated into the log. */
        const val SETTINGS_EVERY_MS = 30_000L
    }
}
