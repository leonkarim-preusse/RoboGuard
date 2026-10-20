package com.example.robocontrol.conversation

import android.content.Context
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/*
 * The parts the owner comparison, the pairwise comparison and the voice enrolment share: the speech gate, reading the
 * microphone, cutting speech into pieces, and the thread that turns a piece into an embedding.
 *
 * They used to be written out three times, and every fix had to be made three times — and was not always (review
 * 2026-10-02: the enrolment had no robot-speech mask, its pieces were cut differently from the detector's, and the
 * hysteresis could switch the gate on for good). A template is only comparable with pieces cut the SAME way, so one
 * implementation is not only shorter, it is a correctness requirement.
 */

/** Samples per 10 ms hop at 16 kHz. */
internal const val SPEECH_HOP = 160

/**
 * Reads one full hop from [source]. Returns false at the end of the stream or when the microphone stops delivering.
 * Checks for interruption on every read, so stop() always ends the loop — a read that returns 0 used to spin for ever
 * (review 2026-10-02, item 35).
 */
internal fun readHop(source: PcmSource, read: ShortArray, hop: ShortArray): Boolean {
    var filled = 0
    var emptyReads = 0
    while (filled < SPEECH_HOP) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val n = source.read(read, SPEECH_HOP - filled)
        if (n < 0) return false
        if (n == 0) {
            if (++emptyReads > MAX_EMPTY_READS) return false
            Thread.sleep(5)
            continue
        }
        emptyReads = 0
        read.copyInto(hop, filled, 0, n)
        filled += n
    }
    return true
}

/** About one second of reads that deliver nothing: the recording is not running any more. */
private const val MAX_EMPTY_READS = 200

/** Level of one hop in dBFS. */
internal fun hopLevelDb(hop: ShortArray): Double {
    var sum = 0.0
    for (k in hop.indices) {
        val v = hop[k] / 32768f
        sum += v * v
    }
    return 20 * log10(max(sqrt(sum / hop.size), 1e-9))
}

/**
 * The speech gate of the embedding methods: Silero with hysteresis and the loudness floor, as [ConversationSettings]
 * says. The release bar is bounded ([ConversationSettings.sileroRelease]): with a hysteresis as large as the threshold it
 * used to be 0 or below, and once switched on the Silero half then stayed on until the detector restarted (item 78).
 */
internal class SpeechGateState(private val settings: ConversationSettings) {
    private var sileroWasSpeech = false
    var silero = false
        private set
    var loud = false
        private set

    /** Decides whether one frame is speech from Silero's [probability] and the [levelDb]; updates [silero] and [loud]. */
    fun decide(probability: Double, levelDb: Double): Boolean {
        val limit = if (sileroWasSpeech) settings.sileroRelease else settings.speechThreshold
        silero = probability >= limit
        sileroWasSpeech = silero
        loud = levelDb > settings.minLevelDb
        // Digital silence is never speech, whatever a neural network makes of it (2026-09-28: Silero said "speech" on
        // every frame of a muted microphone).
        if (levelDb < DIGITAL_SILENCE_DB) return false
        return when (settings.gateMode) {
            SpeechGateMode.SILERO_AND_LEVEL -> silero && loud
            SpeechGateMode.SILERO_ONLY -> silero
            SpeechGateMode.LEVEL_ONLY -> loud
        }
    }

    /** Forgets the hysteresis state, e.g. after a silence reset. */
    fun reset() {
        sileroWasSpeech = false
    }

    private companion object {
        const val DIGITAL_SILENCE_DB = -100.0
    }
}

/** One finished piece of speech on its way to the speaker model. Whoever uses [samples] zeroes them. */
internal class SpeechPiece(
    val samples: FloatArray,
    /** Audio time of its last hop. */
    val timeMs: Long,
    /** Mean level of its speech frames: the closest thing to a distance for one reading. */
    val levelDb: Double,
    /** From its first to its last hop. */
    val spanMs: Long,
    /** Why it was handed off: "full", "pause", "voice changed", "robot spoke", "end". */
    val reason: String
)

/**
 * Cuts the gated speech into pieces of [ConversationSettings.pieceSeconds].
 *
 * - Continuous pieces ([ConversationSettings.continuousPieces]): padding from a ring of hops that were NOT appended yet
 *   (it used to re-append hops the bridge already had, item 55), short pauses bridged.
 * - A pause longer than [ConversationSettings.maxPieceGapMs] ends the piece: handed off if it holds at least
 *   [ConversationSettings.minPieceSeconds], otherwise dropped. Before, a half piece waited up to 30 s and was finished
 *   with whatever was said next (measured span 32.2 s, item 13).
 * - While the robot speaks nothing is appended; what was collected before it started is handed off (it is clean speech).
 * - Split at speaker changes ([ConversationSettings.splitAtChanges], item 46): a [ChangeDetector] runs on the same frames.
 *   It reports a change about 1.6 s of speech after its boundary, so a full piece is held back by that much before it is
 *   handed off; if an accepted change falls inside it, the piece is cut there instead of mixing two voices.
 *
 * Audio lives only in [buffer] and the ring, both overwritten in place and zeroed by [clear]. Not thread-safe.
 */
internal class PieceAssembler(private val settings: ConversationSettings, private val sampleRate: Int = 16_000) {

    private val pieceHops = (settings.pieceSeconds * sampleRate / SPEECH_HOP).toInt()
    private val holdHops = if (settings.splitAtChanges) HOLD_SPEECH_FRAMES else 0
    private val minHops = (settings.minPieceSeconds * sampleRate / SPEECH_HOP).toInt().coerceAtLeast(MIN_EMBED_HOPS)
    private val preRollHops = (settings.speechPadMs / 10).toInt().coerceAtLeast(1)
    private val capacityHops = pieceHops + holdHops + preRollHops + 2

    private val buffer = FloatArray(capacityHops * SPEECH_HOP)
    private val hopTime = LongArray(capacityHops)
    private val hopLevel = DoubleArray(capacityHops)
    private val hopSpeech = BooleanArray(capacityHops)
    private var hops = 0

    private val preRoll = ShortArray(preRollHops * SPEECH_HOP)
    private val preRollTime = LongArray(preRollHops)
    private val preRollLevel = DoubleArray(preRollHops)
    private var preRollAt = 0
    private var preRollFilled = 0

    private var wasAppending = false
    private var wasRobotTalking = false
    private var lastGateMs = Long.MIN_VALUE / 2
    private var lastAppendMs = Long.MIN_VALUE / 2

    // Speaker changes inside the stream: MFCC on a sliding 25 ms frame, fed with the gate's verdict.
    private val mfcc = if (settings.splitAtChanges) Mfcc(sampleRate = sampleRate) else null
    private val changes = mfcc?.let {
        ChangeDetector(it.coefficients, ChangeDetectorConfig(
            speechGate = SpeechGate.SILERO, sileroThreshold = 0.5, sileroHysteresis = 0.0, speechMinDb = -1_000.0
        ))
    }
    private val frame = DoubleArray(mfcc?.frameLength ?: 0)
    private val features = DoubleArray(mfcc?.coefficients ?: 0)

    /** Samples collected and not yet handed off (for the enrolment's progress line). */
    val collectedSamples: Int get() = hops * SPEECH_HOP

    /**
     * One hop. [speech] is the gate's verdict for it, [robotTalking] whether the robot's own playback is on. Returns the
     * pieces that became ready (usually none).
     */
    fun onHop(hop: ShortArray, timeMs: Long, levelDb: Double, speech: Boolean, robotTalking: Boolean): List<SpeechPiece> {
        val out = ArrayList<SpeechPiece>(1)
        if (mfcc != null && changes != null) {
            frame.copyInto(frame, 0, SPEECH_HOP, frame.size)
            for (i in 0 until SPEECH_HOP) frame[frame.size - SPEECH_HOP + i] = hop[i] / 32768.0
            mfcc.compute(frame, features)
            val counts = speech && !robotTalking
            val candidate = changes.onFrame(timeMs, if (counts) levelDb else Double.NEGATIVE_INFINITY, features, if (counts) 1.0 else 0.0)
            if (candidate != null && candidate.countsAsChange) splitAt(candidate.timeMs, out)
        }
        if (robotTalking) {
            if (!wasRobotTalking) emitRange(0, hops, "robot spoke", out).also { clearBuffer() }
            wasRobotTalking = true
            wasAppending = false
            preRollFilled = 0
            return out
        }
        wasRobotTalking = false
        if (speech) lastGateMs = timeMs
        val appending = if (settings.continuousPieces) speech || timeMs - lastGateMs <= settings.bridgeGapMs else speech
        if (appending) {
            if (!wasAppending && settings.continuousPieces) {
                for (n in 0 until preRollFilled) {
                    val slot = (preRollAt - preRollFilled + n + preRollHops) % preRollHops
                    append(preRoll, slot * SPEECH_HOP, preRollTime[slot], preRollLevel[slot], false)
                }
            }
            preRollFilled = 0
            append(hop, 0, timeMs, levelDb, speech)
            lastAppendMs = timeMs
        } else if (settings.continuousPieces) {
            hop.copyInto(preRoll, preRollAt * SPEECH_HOP)
            preRollTime[preRollAt] = timeMs
            preRollLevel[preRollAt] = levelDb
            preRollAt = (preRollAt + 1) % preRollHops
            if (preRollFilled < preRollHops) preRollFilled++
        }
        wasAppending = appending
        if (hops > 0 && timeMs - lastAppendMs > settings.maxPieceGapMs) {
            emitRange(0, hops, "pause", out)
            clearBuffer()
        }
        if (hops >= pieceHops + holdHops) {
            emitRange(0, pieceHops, "full", out)
            shift(pieceHops)
        }
        return out
    }

    /** Hands off whatever is collected (end of a run). */
    fun flush(): List<SpeechPiece> {
        val out = ArrayList<SpeechPiece>(2)
        emitRange(0, hops, "end", out)
        clearBuffer()
        return out
    }

    /** Forgets everything collected (silence reset, stop). */
    fun clear() {
        clearBuffer()
        preRoll.fill(0)
        preRollFilled = 0
        wasAppending = false
        lastGateMs = Long.MIN_VALUE / 2
        lastAppendMs = Long.MIN_VALUE / 2
        frame.fill(0.0)
        features.fill(0.0)
        changes?.clear()
    }

    /** Copies one hop into the piece buffer; stops silently when the buffer is full. */
    private fun append(src: ShortArray, offset: Int, timeMs: Long, levelDb: Double, speech: Boolean) {
        if (hops >= capacityHops) return
        val base = hops * SPEECH_HOP
        for (k in 0 until SPEECH_HOP) buffer[base + k] = src[offset + k] / 32768f
        hopTime[hops] = timeMs
        hopLevel[hops] = levelDb
        hopSpeech[hops] = speech
        hops++
    }

    /** An accepted change at audio time [boundaryMs]: what came before it is one voice, and goes out on its own. */
    private fun splitAt(boundaryMs: Long, out: MutableList<SpeechPiece>) {
        if (hops == 0) return
        var h = 0
        while (h < hops && hopTime[h] < boundaryMs) h++
        if (h == 0 || h >= hops) return
        emitRange(0, h, "voice changed", out)
        shift(h)
    }

    /** Hands off hops [from, to) as pieces of at most [pieceHops]; parts shorter than [minHops] are dropped. */
    private fun emitRange(from: Int, to: Int, reason: String, out: MutableList<SpeechPiece>) {
        var start = from
        while (start < to) {
            val end = minOf(to, start + pieceHops)
            if (end - start >= minHops) {
                val samples = buffer.copyOfRange(start * SPEECH_HOP, end * SPEECH_HOP)
                var levelSum = 0.0
                var levelCount = 0
                for (i in start until end) if (hopSpeech[i] && hopLevel[i].isFinite()) { levelSum += hopLevel[i]; levelCount++ }
                out += SpeechPiece(
                    samples, hopTime[end - 1],
                    if (levelCount > 0) levelSum / levelCount else Double.NEGATIVE_INFINITY,
                    hopTime[end - 1] - hopTime[start] + 10, reason
                )
            }
            start = end
        }
    }

    /** Drops the first [count] hops and moves the rest to the front. */
    private fun shift(count: Int) {
        val keep = hops - count
        if (keep > 0) {
            buffer.copyInto(buffer, 0, count * SPEECH_HOP, hops * SPEECH_HOP)
            hopTime.copyInto(hopTime, 0, count, hops)
            hopLevel.copyInto(hopLevel, 0, count, hops)
            hopSpeech.copyInto(hopSpeech, 0, count, hops)
        }
        buffer.fill(0f, maxOf(keep, 0) * SPEECH_HOP, hops * SPEECH_HOP)
        hops = maxOf(keep, 0)
    }

    /** Zeroes the used part of the buffer and empties it. */
    private fun clearBuffer() {
        buffer.fill(0f, 0, hops * SPEECH_HOP)
        hops = 0
    }

    private companion object {
        /** One comparison window plus one step of the change detector: how late it reports a boundary (1.6 s). */
        const val HOLD_SPEECH_FRAMES = 160

        /** The speaker model needs at least one second. */
        const val MIN_EMBED_HOPS = 100
    }
}

/**
 * The thread that turns pieces into embeddings, so the microphone thread never stops reading (an embedding takes
 * 0.6–3.4 s; done inline, it starved the read loop and hid short robot sentences from the speech mask, items 37 and 54).
 *
 * The thread OWNS the speaker model: it loads it and it alone closes it, after its last run has returned. Closing an
 * ONNX session while a run is inside native code frees the model under it and crashes the process (review item 1).
 *
 * [compute] turns an embedding into what the caller needs — a similarity, or a copy of the vector — and runs on this
 * thread; the embedding itself is zeroed right after. [discard] erases a result nobody collected. [onEnd] runs on this
 * thread after the model is closed (erase templates there).
 */
internal class EmbeddingWorker<R : Any>(
    context: Context,
    name: String,
    capacity: Int,
    private val compute: (embedding: FloatArray, piece: SpeechPiece) -> R?,
    private val discard: (R) -> Unit = {},
    private val onEnd: () -> Unit = {}
) {
    /** One finished embedding run: the computed [value], the piece it came from and how long it took. */
    class Done<R>(val value: R, val piece: SpeechPiece, val embedNs: Long)

    private val queue = ArrayBlockingQueue<SpeechPiece>(capacity)
    private val results = ConcurrentLinkedQueue<Done<R>>()
    private val failure = AtomicReference<String?>(null)

    @Volatile
    private var busy = false

    /** Set when the model could not be loaded or a run failed in a way that ends the worker. */
    val error: String? get() = failure.get()

    /** Nothing queued and nothing running. */
    val idle: Boolean get() = queue.isEmpty() && !busy

    private val thread = Thread({
        // A defined priority: below the audio thread (-16), above ordinary background work, so the time to a verdict
        // stays bounded on a busy robot (item 52).
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND) }
        var embedder: SpeakerEmbedder? = null
        try {
            embedder = SpeakerEmbedder(context)
            while (!Thread.currentThread().isInterrupted) {
                val piece = queue.take()
                busy = true
                try {
                    val started = System.nanoTime()
                    val embedding = runCatching { embedder.embed(piece.samples, piece.samples.size) }.getOrNull()
                    piece.samples.fill(0f)
                    if (embedding != null) {
                        val value = compute(embedding, piece)
                        embedding.fill(0f)
                        if (value != null) results.add(Done(value, piece, System.nanoTime() - started))
                    }
                } finally {
                    busy = false
                }
            }
        } catch (_: InterruptedException) {
            // stop() while waiting: a normal end
        } catch (e: Exception) {
            Log.e(name, "embedding stopped", e)
            failure.set("speaker model: " + (e.message ?: e.toString()))
        } finally {
            runCatching { embedder?.close() }
            while (true) (queue.poll() ?: break).samples.fill(0f)
            while (true) discard((results.poll() ?: break).value)
            runCatching { onEnd() }
        }
    }, name).apply { isDaemon = true; start() }

    /** Queues [piece]; if the queue is full it is zeroed and dropped (a piece judged seconds late says nothing). */
    fun offer(piece: SpeechPiece): Boolean {
        if (queue.offer(piece)) return true
        piece.samples.fill(0f)
        return false
    }

    /** The next finished result, or null. */
    fun poll(): Done<R>? = results.poll()

    /** Ends the thread after its current run; it closes the model and erases everything itself. */
    fun stop() {
        thread.interrupt()
    }

    /** Waits up to [millis] for the thread to end; true if it did. */
    fun join(millis: Long): Boolean {
        runCatching { thread.join(millis) }
        return !thread.isAlive
    }
}
