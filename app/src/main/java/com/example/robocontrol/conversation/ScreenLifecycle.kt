package com.example.robocontrol.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Calls [onStart] when the screen becomes visible (ON_START) and [onStop] when it is no longer visible (ON_STOP).
 * @param onStart called when the screen becomes visible
 * @param onStop called when the screen is no longer visible
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
