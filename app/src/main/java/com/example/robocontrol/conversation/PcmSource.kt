package com.example.robocontrol.conversation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** 16-bit mono PCM input for [SpeakerChangeDetector]. */
interface PcmSource {
    val sampleRate: Int

    /** @return null if opened, otherwise a reason */
    fun open(): String?

    /** Reads up to [count] samples into [buffer]. @return samples read, 0 if none yet, −1 at the end or on error */
    fun read(buffer: ShortArray, count: Int): Int

    fun close()
}

/**
 * The robot's microphone through plain Android `AudioRecord` (needs RECORD_AUDIO).
 *
 * Source CAMCORDER by default: on the robot (MicAccessProbe, see CLAUDE.md) it was the only source with a clear signal
 * (−37 dBFS, versus MIC unreliable and VOICE_RECOGNITION / UNPROCESSED near silence). Mono 16 kHz.
 */
class AndroidMicSource(
    override val sampleRate: Int = 16_000,
    private val audioSource: Int = MediaRecorder.AudioSource.CAMCORDER
) : PcmSource {

    private var record: AudioRecord? = null

    override fun open(): String? {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return "16 kHz mono not supported (getMinBufferSize=$minBuffer)"
        val r = try {
            AudioRecord(audioSource, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (e: Exception) {
            return "AudioRecord failed: $e"
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return "AudioRecord not initialized (permission missing or microphone busy?)"
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return "startRecording failed: $e"
        }
        record = r
        return null
    }

    override fun read(buffer: ShortArray, count: Int): Int {
        val r = record ?: return -1
        val n = r.read(buffer, 0, count)
        return if (n < 0) -1 else n
    }

    override fun close() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

/**
 * Synthetic "voices" for testing without people: a pulse train at the speaker's pitch through three formant resonators,
 * with syllable-like loudness and changing vowels. Speakers differ in pitch and vocal-tract length (all formants scaled),
 * which is roughly what separates real voices too. Not meant to sound like speech; meant to test the pipeline.
 *
 * @param script segments to play in order: speaker index (0 or 1) or null for silence, with a duration
 * @param realtime true = deliver at the speed of real audio (for watching the screen); false = as fast as possible
 */
class SyntheticVoicesSource(
    val script: List<Segment>,
    private val realtime: Boolean,
    override val sampleRate: Int = 16_000,
    seed: Int = 7
) : PcmSource {

    data class Segment(val speaker: Int?, val durationMs: Long)

    private data class Voice(val pitchHz: Double, val formantScale: Double)

    private val voices = listOf(Voice(115.0, 1.0), Voice(215.0, 1.2))
    private val vowels = listOf(
        doubleArrayOf(730.0, 1090.0, 2440.0), // a
        doubleArrayOf(270.0, 2290.0, 3010.0), // i
        doubleArrayOf(300.0, 870.0, 2240.0),  // u
        doubleArrayOf(530.0, 1840.0, 2480.0)  // e
    )
    private val random = Random(seed)
    private var sample = 0L
    private val totalSamples = script.sumOf { it.durationMs } * sampleRate / 1000
    private var startedAtNanos = 0L
    private var phase = 0.0
    private val y1 = DoubleArray(3)
    private val y2 = DoubleArray(3)

    /** Times (ms) where the speaker changes from one voice to the other, ignoring silences: the "truth" for a test. */
    val expectedChangesMs: List<Long> = buildList {
        var t = 0L
        var lastSpeaker: Int? = null
        for (segment in script) {
            if (segment.speaker != null) {
                if (lastSpeaker != null && lastSpeaker != segment.speaker) add(t)
                lastSpeaker = segment.speaker
            }
            t += segment.durationMs
        }
    }

    override fun open(): String? {
        startedAtNanos = System.nanoTime()
        return null
    }

    override fun read(buffer: ShortArray, count: Int): Int {
        if (sample >= totalSamples) return -1
        if (realtime) {
            val dueSamples = (System.nanoTime() - startedAtNanos) * sampleRate / 1_000_000_000L
            if (sample + count > dueSamples) {
                Thread.sleep(((sample + count - dueSamples) * 1000 / sampleRate).coerceAtLeast(1))
            }
        }
        val n = minOf(count.toLong(), totalSamples - sample).toInt()
        for (i in 0 until n) buffer[i] = nextSample()
        return n
    }

    private fun nextSample(): Short {
        val t = sample.toDouble() / sampleRate
        val ms = sample * 1000 / sampleRate
        sample++
        var elapsed = 0L
        var speaker: Int? = null
        for (segment in script) {
            if (ms < elapsed + segment.durationMs) { speaker = segment.speaker; break }
            elapsed += segment.durationMs
        }
        val noise = random.nextDouble(-1.0, 1.0) * 0.002
        if (speaker == null) return (noise * 32767).toInt().toShort()

        val voice = voices[speaker]
        val syllable = (t * 4).toInt()                       // 4 syllables per second
        val envelope = 0.5 - 0.5 * cos(2 * PI * (t * 4 % 1.0)) // loud in the middle of each syllable
        val pitch = voice.pitchHz * (1 + 0.03 * sin(2 * PI * 5 * t) + 0.05 * sin(2 * PI * 0.3 * t))
        phase += pitch / sampleRate
        val pulse = if (phase >= 1.0) { phase -= 1.0; 1.0 } else 0.0

        var x = pulse
        val formants = vowels[(syllable * 7 + syllable / 3) % vowels.size]
        for (k in 0 until 3) {
            val f = formants[k] * voice.formantScale
            val r = exp(-PI * 90.0 / sampleRate)
            val y = x + 2 * r * cos(2 * PI * f / sampleRate) * y1[k] - r * r * y2[k]
            y2[k] = y1[k]; y1[k] = y
            x = y * (1 - r)
        }
        val value = (x * OUTPUT_GAIN * envelope + noise).coerceIn(-1.0, 1.0)
        return (value * 32767).toInt().toShort()
    }

    override fun close() {}

    private companion object {
        /** The resonator cascade is very quiet; this brings syllable peaks to roughly −15 dBFS. */
        const val OUTPUT_GAIN = 250.0
    }
}
