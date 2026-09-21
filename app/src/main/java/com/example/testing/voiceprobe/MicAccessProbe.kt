package com.example.testing.voiceprobe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Probe 1: can this app record audio itself, and how many real microphones does it get?
 *
 * Background: the OrionStar SDK has no API that hands out raw audio (checked in the jar),
 * so any detector working on the signal itself has to use Android's own [AudioRecord].
 * RobotOS's speech service is probably recording permanently to listen for the wake word,
 * and Android does not always let two apps record at the same time. Depending on the
 * Android version, the second recorder either fails to start or silently receives zeros.
 *
 * What the probe does:
 *  1. Logs the device, Android version, input devices and currently active recordings.
 *  2. Records one second, mono at 16 kHz, from each of five audio sources, and checks
 *     whether any of them delivers a real signal (not all zeros, not digital silence).
 *  3. With the first source that works, requests 2, 4, 6 and 8 channels at 16 kHz and
 *     48 kHz, and measures whether those channels are actually different microphones.
 *
 * How to read the result:
 *  - "RESULT: no source delivered signal": the mic is held by RobotOS. Change detection on
 *    raw audio is blocked; only the speech-service callbacks and bearings are left.
 *  - A source works, but every channel has `corrToCh0` ≈ 1.000: one microphone copied into
 *    several channels. Change detection works; bearing must come from RobotOS, if at all.
 *  - Channels with clearly lower correlation (`distinct` > 1): a real mic array is exposed,
 *    so the app could estimate sound direction itself.
 *
 * Privacy: audio only lives in a local buffer inside [record] for the duration of one
 * attempt. Only statistics (levels, correlations) are logged. Nothing is saved or sent.
 */
class MicAccessProbe(private val context: Context, private val log: ProbeLog) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Runs the whole probe. Blocking, takes roughly 15–25 s depending on how many
     * configurations the device accepts. Must be called from a background thread.
     *
     * @param secondsPerAttempt how long each configuration records. One second is enough to
     *        see signal vs. silence, and short enough that people can keep talking throughout.
     */
    fun run(secondsPerAttempt: Double = 1.0) {
        log.section("Mic access")
        log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL}, Android SDK ${Build.VERSION.SDK_INT}")

        // The activity asks for the permission before calling run(); this is a safety net.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log.i(TAG, "RECORD_AUDIO not granted, stopping")
            return
        }
        listInputDevices()
        logActiveRecordings("before")

        // Step 1: which audio sources deliver any signal at all (mono, 16 kHz).
        // Different sources map to differently processed paths in the audio HAL; on some
        // devices only one of them is free while another app records.
        val working = SOURCES.filter { (name, source) ->
            record(name, source, SPEECH_RATE, channels = 1, secondsPerAttempt)?.hasSignal == true
        }
        if (working.isEmpty()) {
            log.i(TAG, "RESULT: no source delivered signal. The mic is probably held by the speech service.")
            logActiveRecordings("after")
            return
        }
        log.i(TAG, "sources with signal: ${working.map { it.first }}")

        // Step 2: with the first working source, how many distinct channels exist?
        // 16 kHz is enough for speech features; 48 kHz is included because some HALs only
        // expose multi-channel capture at their native rate.
        val (name, source) = working.first()
        for (rate in listOf(SPEECH_RATE, 48_000)) {
            for (channels in listOf(2, 4, 6, 8)) {
                record(name, source, rate, channels, secondsPerAttempt)
            }
        }
        logActiveRecordings("after")
        log.i(TAG, "done. Note whether the robot still reacted to its wake word during the run.")
    }

    /**
     * Logs every input device with the channel counts, channel index masks and sample rates
     * it advertises. A mic array normally shows up here with more than 2 channels or with
     * index masks. Empty lists mean "any value", not "none".
     */
    private fun listInputDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        log.i(TAG, "${devices.size} input device(s)")
        for (d in devices) {
            log.i(
                TAG,
                "  type=${d.type} name=${d.productName} channels=${d.channelCounts.toList()} " +
                    "indexMasks=${d.channelIndexMasks.toList()} rates=${d.sampleRates.toList()}"
            )
        }
    }

    /**
     * Logs the recordings Android reports as active. If the speech service's session is listed
     * "before", that confirms RobotOS holds the mic.
     * Caveat: before Android 10 the list may include other apps' sessions; from Android 10 on,
     * normal apps usually only see their own, so an empty list proves nothing there.
     */
    private fun logActiveRecordings(whenText: String) {
        val configs = audioManager.activeRecordingConfigurations
        log.i(
            TAG,
            "active recordings $whenText: ${configs.size} " +
                configs.map { "source=${it.clientAudioSource} session=${it.clientAudioSessionId}" }
        )
    }

    /**
     * Records one configuration and computes [Stats] for it.
     *
     * Every failure mode is logged separately, because they mean different things:
     *  - "cannot create": the device rejects this format or source outright
     *  - "not initialized": the format was accepted, but the HAL could not open it
     *  - "did not start": most likely another app holds the input
     *  - "read returned <n>": recording started but was cut off (negative n is an error code)
     *
     * @param sourceName readable name of [source], for the log only
     * @param source a `MediaRecorder.AudioSource` constant
     * @param rate sample rate in Hz
     * @param channels requested channel count; the device may deliver a different one
     * @param seconds recording length
     * @return statistics, or null if nothing could be recorded
     */
    @SuppressLint("MissingPermission") // checked in run()
    private fun record(sourceName: String, source: Int, rate: Int, channels: Int, seconds: Double): Stats? {
        val label = "$sourceName ${rate}Hz ${channels}ch"
        val frames = (rate * seconds).toInt()
        val recorder = try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .apply {
                    // Mono uses the normal position mask. For more channels, index masks request
                    // raw channels 0..n-1 without "left/right/front" meaning, which is how a mic
                    // array shows up if it is exposed at all.
                    if (channels == 1) setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    else setChannelIndexMask((1 shl channels) - 1)
                }
                .build()
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                // Buffer for the whole attempt (16-bit = 2 bytes per sample), so reads never overrun.
                .setBufferSizeInBytes(frames * channels * 2)
                .build()
        } catch (e: Exception) {
            log.i(TAG, "$label: cannot create (${e.javaClass.simpleName}: ${e.message})")
            return null
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log.i(TAG, "$label: not initialized")
                return null
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                log.i(TAG, "$label: did not start (recordingState=${recorder.recordingState})")
                return null
            }

            // Use the channel count the device actually granted, not the one requested.
            val actualChannels = recorder.channelCount
            val buffer = ShortArray(frames * actualChannels)

            // read() may return fewer samples than asked for, so loop until the buffer is full.
            var read = 0
            while (read < buffer.size) {
                val n = recorder.read(buffer, read, buffer.size - read)
                if (n <= 0) {
                    log.i(TAG, "$label: read returned $n after $read samples")
                    break
                }
                read += n
            }
            val stats = Stats.of(buffer, read, actualChannels)
            log.i(TAG, "$label: got ${actualChannels}ch, ${read / actualChannels} frames, $stats")
            return stats
        } catch (e: Exception) {
            log.i(TAG, "$label: failed (${e.javaClass.simpleName}: ${e.message})")
            return null
        } finally {
            // Always free the input, otherwise the next attempt, or the robot's own speech
            // service, cannot get it back.
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Summary of one recording. Holds only numbers, no audio.
     *
     * Samples are interleaved: frame f, channel c is at index `f * channels + c`.
     *
     * @property zeroFraction share of samples that are exactly 0 (1.0 = pure digital silence)
     * @property rmsDbfs loudness per channel in dB relative to full scale. Speech close to the
     *           robot is typically -40 to -20; a quiet room -70 to -50; -Infinity = all zeros.
     * @property correlationToFirst Pearson correlation of each channel with channel 0 (the first
     *           entry is always 1.0). Separate microphones a few centimetres apart still correlate,
     *           but clearly below 1.0. Copies of the same channel give ≈ 1.000.
     */
    class Stats(
        private val zeroFraction: Double,
        private val rmsDbfs: List<Double>,
        private val correlationToFirst: List<Double>
    ) {
        /**
         * True if the recording contains a real signal. All-zero or digitally silent buffers
         * are what Android gives a second recorder when another client owns the microphone.
         */
        val hasSignal: Boolean get() = zeroFraction < 0.99 && rmsDbfs.any { it > SILENCE_DBFS }

        /**
         * Rough number of physically different microphones: channel 0 plus every channel
         * that is not a near-copy of it.
         */
        val distinctChannels: Int get() = 1 + correlationToFirst.drop(1).count { it < DUPLICATE_CORRELATION }

        override fun toString() =
            "zeros=${"%.2f".format(zeroFraction)} rmsDbfs=${rmsDbfs.map { "%.1f".format(it) }} " +
                "corrToCh0=${correlationToFirst.map { "%.3f".format(it) }} distinct≈$distinctChannels"

        companion object {
            /** Below this, a channel counts as silent. Real mics have a noise floor well above it. */
            private const val SILENCE_DBFS = -80.0

            /** At or above this correlation, a channel is treated as a copy of channel 0. */
            private const val DUPLICATE_CORRELATION = 0.999

            /**
             * Computes the statistics.
             *
             * @param samples interleaved 16-bit PCM
             * @param count number of valid samples in [samples] (may be less than its size)
             * @param channels number of interleaved channels
             */
            fun of(samples: ShortArray, count: Int, channels: Int): Stats {
                val frames = count / channels
                if (frames == 0) {
                    return Stats(1.0, List(channels) { Double.NEGATIVE_INFINITY }, List(channels) { 0.0 })
                }

                var zeros = 0
                for (i in 0 until frames * channels) if (samples[i].toInt() == 0) zeros++

                // RMS per channel, converted to dBFS (0 dBFS = the loudest possible 16-bit value).
                val rms = (0 until channels).map { c ->
                    var sum = 0.0
                    for (f in 0 until frames) {
                        val s = samples[f * channels + c].toDouble()
                        sum += s * s
                    }
                    val r = sqrt(sum / frames)
                    if (r == 0.0) Double.NEGATIVE_INFINITY else 20 * log10(r / Short.MAX_VALUE)
                }

                val corr = (0 until channels).map { c -> correlation(samples, frames, channels, 0, c) }
                return Stats(zeros.toDouble() / (frames * channels), rms, corr)
            }

            /**
             * Pearson correlation between channels [a] and [b] at zero lag.
             * Returns 0.0 if either channel is constant (e.g. all zeros), since correlation is
             * undefined there and "not a copy" is the safer reading.
             */
            private fun correlation(s: ShortArray, frames: Int, channels: Int, a: Int, b: Int): Double {
                var meanA = 0.0
                var meanB = 0.0
                for (f in 0 until frames) {
                    meanA += s[f * channels + a].toDouble()
                    meanB += s[f * channels + b].toDouble()
                }
                meanA /= frames
                meanB /= frames

                var cov = 0.0
                var varA = 0.0
                var varB = 0.0
                for (f in 0 until frames) {
                    val x = s[f * channels + a].toDouble() - meanA
                    val y = s[f * channels + b].toDouble() - meanB
                    cov += x * y
                    varA += x * x
                    varB += y * y
                }
                return if (varA == 0.0 || varB == 0.0) 0.0 else cov / sqrt(varA * varB)
            }
        }
    }

    private companion object {
        const val TAG = "mic"

        /** Standard rate for speech processing; also what a change detector would use. */
        const val SPEECH_RATE = 16_000

        /**
         * Audio sources to try, in order of preference for a speech detector:
         *  - MIC: plain microphone, least processing
         *  - VOICE_RECOGNITION: tuned for ASR, often the path a speech service uses
         *  - CAMCORDER: sometimes the only path exposing several mics
         *  - VOICE_COMMUNICATION: echo cancellation on; could help ignore the robot's own voice
         *  - UNPROCESSED: raw signal, if the device supports it
         */
        val SOURCES = listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED
        )
    }
}
