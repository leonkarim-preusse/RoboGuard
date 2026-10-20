package com.example.robocontrol.conversation

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * Tells whether the robot itself was playing sound when a piece of audio was recorded, so the detectors can ignore it.
 * Playback start and end times come from [AudioManager.registerAudioPlaybackCallback].
 * @param context used to reach the audio service
 * @param tailMs time after the end of playback that still counts as speaking
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
     * Whether the robot was speaking when the hop ending at [audioTimeMs] was captured.
     * @param audioTimeMs position in the audio stream, in milliseconds
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
