package com.example.robocontrol.conversation

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * Was the robot itself making a sound when this audio was recorded?
 *
 * Everything the robot says goes out of its speaker and back into its own microphone, where it is speech like any other —
 * and it is not the owner's, so it reads as "somebody else". The robot would then react to itself: an announcement, or
 * even the apology it speaks after a conversation was detected, becomes evidence for the next detection.
 *
 * Playback is recorded by the system's playback callback ([AudioManager.registerAudioPlaybackCallback]) as start and stop
 * times, independent of the audio loop. The question is then asked for the moment the audio was CAPTURED, not the moment
 * it is processed: a sentence that started and ended while a detector was busy used to be missed and its audio, still in
 * the buffer, was taken for a person (review 2026-10-02, item 54). The answer stays true for [tailMs] after the sound
 * stops, because the room keeps ringing for a moment.
 */
class RobotSpeaking(context: Context, private val tailMs: Long = 500) {

    init {
        Playback.start(context.applicationContext)
    }

    /** Smallest "wall clock minus audio time" seen: when the stream started, i.e. how audio time maps to wall time. */
    private var streamStartAt = Long.MAX_VALUE

    /** True while the robot plays something, and for [tailMs] afterwards — right now. */
    fun active(): Boolean = Playback.activeAt(SystemClock.elapsedRealtime(), tailMs)

    /**
     * Same question for the hop that ends at [audioTimeMs] of the stream (samples read so far, in ms). The capture time is
     * estimated as stream start + audio time; the stream start is the smallest offset seen, i.e. taken while the loop was
     * keeping up.
     */
    fun active(audioTimeMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        streamStartAt = minOf(streamStartAt, now - audioTimeMs)
        return Playback.activeAt(minOf(now, streamStartAt + audioTimeMs), tailMs)
    }

    /** One recorder of playback intervals per process. */
    private object Playback {
        private var started = false
        private var playingSince = 0L
        private val ends = LongArray(16)
        private val starts = LongArray(16)
        private var next = 0
        private lateinit var audio: AudioManager

        /** Registers the playback callback once per process; until then [activeAt] answers false. */
        @Synchronized
        fun start(context: Context) {
            if (started) return
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audio = manager
            started = true
            val thread = HandlerThread("RobotSpeaking").apply { isDaemon = true; start() }
            update(runCatching { manager.activePlaybackConfigurations.isNotEmpty() }.getOrDefault(false))
            manager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    update(!configs.isNullOrEmpty())
                }
            }, Handler(thread.looper))
        }

        /** Records the start and the end of a playback episode. */
        @Synchronized
        private fun update(playing: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (playing && playingSince == 0L) playingSince = now
            if (!playing && playingSince != 0L) {
                starts[next] = playingSince
                ends[next] = now
                next = (next + 1) % ends.size
                playingSince = 0L
            }
        }

        /** Whether the robot was playing sound at time [t] (elapsedRealtime), including [tailMs] of echo after each end. */
        @Synchronized
        fun activeAt(t: Long, tailMs: Long): Boolean {
            if (!started) return false
            if (playingSince != 0L && t >= playingSince) return true
            for (i in ends.indices) {
                if (ends[i] != 0L && t >= starts[i] && t <= ends[i] + tailMs) return true
            }
            return false
        }
    }
}
