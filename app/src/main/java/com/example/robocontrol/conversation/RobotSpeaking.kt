package com.example.robocontrol.conversation

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * Is the robot itself making a sound right now?
 *
 * Everything the robot says goes out of its speaker and back into its own microphone, where it is speech like any other —
 * and it is not the owner's, so it reads as "somebody else". The robot would then react to itself: an announcement, or
 * even the apology it speaks after a conversation was detected, becomes evidence for the next detection.
 *
 * Asked through [AudioManager.getActivePlaybackConfigurations], which covers the speech service and any other playback in
 * the system, not just this app. The answer is cached for [CHECK_MS] (the detectors ask every 10 ms) and stays true for
 * [tailMs] after the sound stops, because the room keeps ringing for a moment and the microphone is still full of it.
 */
class RobotSpeaking(context: Context, private val tailMs: Long = 500) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var lastCheckAt = 0L
    private var playing = false
    private var stoppedAt = 0L

    /** True while the robot plays something, and for [tailMs] afterwards. */
    fun active(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckAt >= CHECK_MS) {
            lastCheckAt = now
            val nowPlaying = runCatching { audio?.activePlaybackConfigurations?.isNotEmpty() == true }.getOrDefault(false)
            if (playing && !nowPlaying) stoppedAt = now
            playing = nowPlaying
        }
        return playing || (stoppedAt != 0L && now - stoppedAt < tailMs)
    }

    private companion object {
        /** The system is asked at most this often; in between the last answer is reused. */
        const val CHECK_MS = 100L
    }
}
