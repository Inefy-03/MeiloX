package com.ljyh.mei.ui.component.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UiLifecycleWorkTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test
    fun stoppedTimersStayIdleAndResumeOnceAcrossEveryStart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            var starts = 0
            var ticks = 0
            var cleanups = 0
            val work = launch {
                owner.lifecycle.runUiWhileStarted {
                    starts++
                    try {
                        while (true) {
                            ticks++
                            delay(50)
                        }
                    } finally {
                        cleanups++
                    }
                }
            }
            runCurrent()
            assertEquals(0, ticks)
            repeat(3) { round ->
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                runCurrent()
                assertEquals(round + 1, starts)
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                runCurrent()
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                runCurrent() // A visible paused Activity, including PiP, still works.
                assertEquals(round + 1, starts)
                advanceTimeBy(100)
                runCurrent()
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                runCurrent()
                assertEquals(round + 1, cleanups)
                val stoppedTicks = ticks
                advanceTimeBy(2_000)
                runCurrent()
                assertEquals(stoppedTicks, ticks)
            }
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            work.join()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stoppingCancelsSuspendedWorkAndRunsCleanupBeforeRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            var visibility = 1f
            var starts = 0
            val work = launch {
                owner.lifecycle.runUiWhileStarted {
                    starts++
                    try {
                        visibility = 0f
                        awaitCancellation()
                    } finally {
                        visibility = 1f
                    }
                }
            }
            runCurrent()
            assertEquals(0f, visibility)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertEquals(1f, visibility)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(2, starts)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            work.join()
            assertEquals(1f, visibility)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
