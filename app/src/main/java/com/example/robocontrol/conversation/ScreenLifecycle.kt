package com.example.robocontrol.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Runs [onStart] while the screen is visible and [onStop] when it is not, also when the composable itself stays alive.
 *
 * The debug screens used to clean up only when their composable was disposed. Home, another app or another RoboGuard
 * screen only STOP the activity, the composition stays, and the service keeps the microphone delivering — so a voice test
 * ran on in the background and kept the conversation detection off (review 2026-10-02, item 25). ON_STOP rather than
 * ON_PAUSE, so the permission dialog and the dialog-themed popups do not end a run.
 */
@Composable
fun WhileVisible(onStart: () -> Unit, onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        var started = false
        fun begin() { if (!started) { started = true; start() } }
        fun end() { if (started) { started = false; stop() } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> begin()
                Lifecycle.Event.ON_STOP -> end()
                else -> Unit
            }
        }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) begin()
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            end()
        }
    }
}
