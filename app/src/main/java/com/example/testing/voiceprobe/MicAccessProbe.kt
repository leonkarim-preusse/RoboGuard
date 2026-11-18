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
 * Tests which audio sources and channel counts the app can record; logs statistics only.
 * @param context used to reach the audio services
 * @param log log of the test screen
 */
class MicAccessProbe(private val context: Context, private val log: ProbeLog) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Runs all attempts. Blocks for about 15–25 s; call it from a background thread.
     * @param secondsPerAttempt recording length per configuration
     */
    fun run(secondsPerAttempt: Double = 1.0) {
        log.section("Mic access")
        log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL}, Android SDK ${Build.VERSION.SDK_INT}")

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log.i(TAG, "RECORD_AUDIO not granted, stopping")
            return
        }
        com.example.robocontrol.conversation.ConversationMonitor.acquireMicrophone("mic access test").use { runAttempts(secondsPerAttempt) }
    }

    /**
     * Tries every audio source and channel count for [secondsPerAttempt] each and logs the levels per channel.
     * @param secondsPerAttempt recording length per attempt
     */
    private fun runAttempts(secondsPerAttempt: Double) {
        listInputDevices()
        logActiveRecordings("before")

        val working = SOURCES.filter { (name, source) ->
            record(name, source, SPEECH_RATE, channels = 1, secondsPerAttempt)?.hasSignal == true
        }
        if (working.isEmpty()) {
            log.i(TAG, "RESULT: no source delivered signal. The mic is probably held by the speech service.")
            logActiveRecordings("after")
            return
        }
        log.i(TAG, "sources with signal: ${working.map { it.first }}")

        val (name, source) = working.first()
        for (rate in listOf(SPEECH_RATE, 48_000)) {
            for (channels in listOf(2, 4, 6, 8)) {
                record(name, source, rate, channels, secondsPerAttempt)
            }
        }
        logActiveRecordings("after")
        log.i(TAG, "done. Note whether the robot still reacted to its wake word during the run.")
    }

    /** Logs every input device with its supported channel counts, channel index masks and sample rates. */
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
     * Logs the recordings Android reports as active.
     * @param whenText label for the log ("before", "after")
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
     * Records one configuration and computes its [Stats].
     * @param sourceName name of [source], for the log
     * @param source a `MediaRecorder.AudioSource` constant
     * @param rate sample rate in Hz
     * @param channels requested channel count
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
                    // Index masks request raw channels, which is how a mic array would appear.
                    if (channels == 1) setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    else setChannelIndexMask((1 shl channels) - 1)
                }
                .build()
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
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

            val actualChannels = recorder.channelCount
            val buffer = ShortArray(frames * actualChannels)

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
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Statistics of one recording; contains no audio.
     * @property zeroFraction share of samples that are exactly 0
     * @property rmsDbfs level per channel in dBFS
     * @property correlationToFirst correlation of each channel with channel 0
     */
    class Stats(
        private val zeroFraction: Double,
        private val rmsDbfs: List<Double>,
        private val correlationToFirst: List<Double>
    ) {
        val hasSignal: Boolean get() = zeroFraction < 0.99 && rmsDbfs.any { it > SILENCE_DBFS }

        val distinctChannels: Int get() = 1 + correlationToFirst.drop(1).count { it < DUPLICATE_CORRELATION }

        /**
         * The statistics as text.
         * @return the text
         */
        override fun toString() =
            "zeros=${"%.2f".format(zeroFraction)} rmsDbfs=${rmsDbfs.map { "%.1f".format(it) }} " +
                "corrToCh0=${correlationToFirst.map { "%.3f".format(it) }} distinct≈$distinctChannels"

        companion object {
            private const val SILENCE_DBFS = -80.0

            private const val DUPLICATE_CORRELATION = 0.999

            /**
             * Computes the statistics of interleaved samples.
             * @param samples interleaved 16-bit PCM
             * @param count number of valid samples
             * @param channels number of interleaved channels
             * @return the statistics
             */
            fun of(samples: ShortArray, count: Int, channels: Int): Stats {
                val frames = count / channels
                if (frames == 0) {
                    return Stats(1.0, List(channels) { Double.NEGATIVE_INFINITY }, List(channels) { 0.0 })
                }

                var zeros = 0
                for (i in 0 until frames * channels) if (samples[i].toInt() == 0) zeros++

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
             * Pearson correlation of channels [a] and [b]; 0 if a channel is constant.
             * @param s interleaved samples
             * @param frames number of frames
             * @param channels number of interleaved channels
             * @param a first channel
             * @param b second channel
             * @return the correlation
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

        const val SPEECH_RATE = 16_000

        val SOURCES = listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED
        )
    }
}
