package com.ljyh.mei.ui.component.player.component.mesh

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshFrameClockTest {
    @Test
    fun ambientAndAudioCadenceRegisterOnlyFramesTheyRenderAcrossDisplayRates() = runTest {
        for (displayHz in listOf(60, 90, 120, 144)) {
            for (targetHz in listOf(30, 60)) {
                for (deliveryLatencyMillis in listOf(0L, 1L, 2L, 5L, 8L)) {
                    val clock = DisplayClock(testScheduler, displayHz,
                        deliveryLatencyMillis = deliveryLatencyMillis)
                    val startedAt = testScheduler.currentTime
                    val stopAt = startedAt + 2_000
                    var animating = true
                    val draws = mutableListOf<Long>()
                    val work = launch(clock) {
                        runMeshFrames(
                            intervalNanos = 1_000_000_000L / targetHz,
                            needsAnimation = { animating },
                            nanoTime = { testScheduler.currentTime * 1_000_000L },
                        ) { frameNanos ->
                            draws += frameNanos
                            animating = testScheduler.currentTime < stopAt
                        }
                    }
                    advanceUntilIdle()
                    work.join()

                    val label = "$targetHz Hz animation on a $displayHz Hz display, ${deliveryLatencyMillis}ms latency"
                    assertEquals("$label must not request discarded frames", draws.size, clock.requests)
                    assertTrue("$label must retain its target cadence: ${draws.size} frames",
                        draws.size in (targetHz * 2 - 1)..(targetHz * 2 + 2))
                    assertTrue("Frame timestamps must be passed through unchanged", draws == clock.delivered)
                    assertTrue("Scheduling must tolerate a different frame clock epoch",
                        draws.first() >= FrameEpochNanos + startedAt * 1_000_000L)
                }
            }
        }
    }

    @Test
    fun delayedFrameSkipsMissedDeadlinesWithoutACatchUpBurst() = runTest {
        val clock = DisplayClock(testScheduler, displayHz = 120, stallAtRequest = 10)
        var animating = true
        val draws = mutableListOf<Long>()
        val work = launch(clock) {
            runMeshFrames(
                intervalNanos = 1_000_000_000L / 30,
                needsAnimation = { animating },
                nanoTime = { testScheduler.currentTime * 1_000_000L },
            ) { frameNanos ->
                draws += frameNanos
                animating = testScheduler.currentTime < 2_000
            }
        }
        advanceUntilIdle()
        work.join()

        assertTrue("The display stall must reach the scheduler", draws[9] - draws[8] >= 500_000_000L)
        assertTrue("Missed frames must be skipped instead of replayed", draws.size in 44..48)
        assertTrue("Frames after a stall must retain normal spacing",
            draws.drop(9).zipWithNext().all { (first, second) -> second - first >= 24_000_000L })
        assertEquals(draws.size, clock.requests)
    }

    @Test
    fun cancellationDuringDelayOrFrameWaitPreventsFurtherCallbacks() = runTest {
        for (cancelAtMillis in listOf(5L, 20L)) {
            val clock = DisplayClock(testScheduler, displayHz = 60)
            var draws = 0
            val work = launch(clock) {
                runMeshFrames(
                    intervalNanos = 1_000_000_000L / 30,
                    needsAnimation = { true },
                    nanoTime = { testScheduler.currentTime * 1_000_000L },
                ) { draws++ }
            }
            runCurrent()
            advanceTimeBy(cancelAtMillis)
            runCurrent()
            val requestsBeforeCancel = clock.requests
            val drawsBeforeCancel = draws
            work.cancelAndJoin()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals("Cancellation must remove any outstanding frame request", drawsBeforeCancel, draws)
            assertEquals("Cancellation must not schedule another frame", requestsBeforeCancel, clock.requests)
        }
    }

    @Test
    fun stoppingAnimationLeavesNoFrameRequestAndResumeStartsFromCurrentTime() = runTest {
        val clock = DisplayClock(testScheduler, displayHz = 60)
        var animating = false
        var draws = 0
        val idle = launch(clock) {
            runMeshFrames(1_000_000_000L / 30, { animating },
                { testScheduler.currentTime * 1_000_000L }) { draws++ }
        }
        advanceUntilIdle()
        idle.join()
        assertEquals("An inactive animation must not request a frame", 0, clock.requests)

        repeat(2) {
            animating = true
            val resumedAt = testScheduler.currentTime
            val resumeFrames = mutableListOf<Long>()
            val work = launch(clock) {
                runMeshFrames(1_000_000_000L / 30, { animating },
                    { testScheduler.currentTime * 1_000_000L }) { frameNanos ->
                    draws++
                    resumeFrames += frameNanos
                    if (resumeFrames.size == 3) animating = false
                }
            }
            advanceUntilIdle()
            work.join()
            assertTrue("Resume must request the next display frame immediately",
                resumeFrames.first() - FrameEpochNanos - resumedAt * 1_000_000L <= 17_000_000L)
            val resumedSpan = resumeFrames.last() - resumeFrames.first()
            val expectedSpan = 2 * (1_000_000_000L / 30)
            assertTrue("Resume must retain its cadence across vsync quantization: $resumeFrames",
                resumedSpan in (expectedSpan - 17_000_000L)..(expectedSpan + 17_000_000L))
            assertEquals("A settled animation must have no outstanding request", draws, clock.requests)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("A settled animation must stay idle", draws, clock.requests)
        }
    }

    @Test
    fun animationStoppedDuringTheDeadlineDelayDoesNotRequestAnotherFrame() = runTest {
        val clock = DisplayClock(testScheduler, displayHz = 60)
        var animating = true
        var draws = 0
        val work = launch(clock) {
            runMeshFrames(1_000_000_000L / 30, { animating },
                { testScheduler.currentTime * 1_000_000L }) { draws++ }
        }
        advanceTimeBy(20)
        runCurrent()
        assertEquals(1, draws)
        animating = false
        advanceUntilIdle()
        work.join()
        assertEquals("Stopping during pacing must not register a new vsync", 1, clock.requests)
        assertEquals(1, draws)
    }

    private class DisplayClock(
        private val scheduler: TestCoroutineScheduler,
        private val displayHz: Int,
        private val stallAtRequest: Int? = null,
        private val deliveryLatencyMillis: Long = 0,
    ) : MonotonicFrameClock {
        var requests = 0
            private set
        val delivered = mutableListOf<Long>()

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests++
            if (requests == stallAtRequest) delay(500)
            val nextTick = scheduler.currentTime * displayHz / 1_000 + 1
            val frameNanos = nextTick * 1_000_000_000L / displayHz
            val deliveryMillis = (frameNanos + 999_999) / 1_000_000
            delay(deliveryMillis - scheduler.currentTime + deliveryLatencyMillis)
            val timestamp = FrameEpochNanos + frameNanos
            delivered += timestamp
            return onFrame(timestamp)
        }
    }

    private companion object {
        const val FrameEpochNanos = 9_000_000_000_000L
    }
}
