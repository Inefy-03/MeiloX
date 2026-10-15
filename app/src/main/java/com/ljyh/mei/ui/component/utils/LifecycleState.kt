package com.ljyh.mei.ui.component.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/** Cancel UI timers on STOP even when Compose's frame clock has already been paused. */
@Composable
fun LifecycleStartedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentBlock by rememberUpdatedState(block)
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.runUiWhileStarted { currentBlock() }
    }
}

internal suspend fun Lifecycle.runUiWhileStarted(block: suspend CoroutineScope.() -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED, block)
}

@Composable
fun rememberLifecycleStarted(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val started = remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            started.value = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        started.value = lifecycleOwner.lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return started
}
