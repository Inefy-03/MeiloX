package com.ljyh.mei.ui.component.sheet

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetArtworkOwnershipTest {
    private val mini = Rect(20f, 708f, 52f, 740f)
    private val full = Rect(50f, 100f, 350f, 400f)
    private val host = Rect(0f, 0f, 400f, 800f)
    private val foreground = listOf("miniHost", "miniContent", "fullContent")

    @Test fun firstOpeningAndClosingCanShareWithoutAnyArtworkRecording() {
        val captures = PlayerSheetCaptures<String>()
        repeat(2) {
            captures.clear()
            assertTrue(playerSharesArtwork(true, true, true, mini, full, host))
            foreground.forEach { layer ->
                assertTrue(captures.shouldCapture(layer, sharedArtwork = true))
            }
        }
    }

    @Test fun bothPlayerStylesAndLyricHeaderUseTheSameOwnershipContract() {
        val targets = listOf(full, Rect(65f, 115f, 335f, 385f), Rect(24f, 76f, 70f, 122f))
        targets.forEach { target ->
            assertTrue(playerSharesArtwork(true, true, true, mini, target, host))
        }
    }

    @Test fun settledOrUnavailableOverlayLeavesEndpointArtworkVisible() {
        assertFalse(playerSharesArtwork(false, true, true, mini, full, host))
        assertFalse(playerSharesArtwork(true, false, true, mini, full, host))
        assertFalse(playerSharesArtwork(true, true, false, mini, full, host))
        assertFalse(playerSharesArtwork(true, true, true, Rect.Zero, full, host))
        assertFalse(playerSharesArtwork(true, true, true, mini, Rect.Zero, host))
        assertFalse(playerSharesArtwork(true, true, true, mini, full, Rect.Zero))
    }

    @Test fun lateImageHostReplacesSnapshotsContainingTheEndpointCopy() {
        val captures = PlayerSheetCaptures<String>()
        val fallback = playerSharesArtwork(true, true, false, mini, full, host)
        foreground.forEach { captures.markCaptured(it, fallback) }

        val shared = playerSharesArtwork(true, true, true, mini, full, host)
        foreground.forEach {
            assertTrue(captures.shouldCapture(it, shared))
            captures.markCaptured(it, shared)
            assertFalse(captures.shouldCapture(it, shared))
        }
    }

    @Test fun removedImageSourceRestoresFallbackSnapshots() {
        val captures = PlayerSheetCaptures<String>()
        foreground.forEach { captures.markCaptured(it, sharedArtwork = true) }
        val shared = playerSharesArtwork(true, true, false, mini, full, host)
        foreground.forEach { assertTrue(captures.shouldCapture(it, shared)) }
    }
}
