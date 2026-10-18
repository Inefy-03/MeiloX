package com.ljyh.mei.ui.component.player.component.mesh

import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Request a vsync only when the mesh is due, rather than polling every display frame. */
internal suspend fun runMeshFrames(
    intervalNanos: Long,
    needsAnimation: () -> Boolean,
    nanoTime: () -> Long = System::nanoTime,
    onFrame: (Long) -> Unit,
) {
    require(intervalNanos > 0L)
    var nextFrameNanos: Long? = null
    while (coroutineContext.isActive && needsAnimation()) {
        withFrameNanos { frameTime ->
            // Frame clocks can have a different epoch from System.nanoTime. Use their
            // timestamps only for animation, and the monotonic clock only for pacing.
            val now = nanoTime()
            var deadline = (nextFrameNanos ?: now) + intervalNanos
            // A callback can arrive just after its normalized vsync. Allow one late
            // deadline so 60 Hz animation on a 60 Hz display does not fall to 30 Hz.
            // Larger stalls discard missed periods instead of replaying them.
            if (now - deadline >= intervalNanos) {
                deadline += ((now - deadline) / intervalNanos + 1L) * intervalNanos
            }
            nextFrameNanos = deadline
            onFrame(frameTime)
        }
        if (!coroutineContext.isActive || !needsAnimation()) return
        // Absolute deadlines prevent vsync wait time accumulating into cadence drift.
        // Millisecond truncation lets us register just before a deadline's vsync.
        val remainingNanos = checkNotNull(nextFrameNanos) - nanoTime()
        delay((remainingNanos / 1_000_000L).coerceAtLeast(0L))
    }
}
