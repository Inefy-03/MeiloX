package com.ljyh.mei.ui.component.sheet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetCapturesTest {
    private val layers = listOf("miniHost", "miniContent", "fullContent")

    @Test fun liveFullContentKeepsUpdatingDuringExpansionAndCollapse() {
        val captures = PlayerSheetCaptures<String>()
        layers.forEach { captures.markCaptured(it, sharedArtwork = true) }
        // Playback time may change without an artwork-ownership or direction change.
        for (progress in (0..100) + (100 downTo 0)) {
            assertTrue("Full content at $progress", captures.shouldCapture("fullContent", sharedArtwork = true, live = true))
            captures.markCaptured("fullContent", sharedArtwork = true)
            assertFalse(captures.shouldCapture("miniContent", sharedArtwork = true))
            assertFalse(captures.shouldCapture("miniHost", sharedArtwork = true))
        }
    }

    @Test fun foregroundCapturesStayReusableWhileTheImageOwnsArtwork() {
        val captures = PlayerSheetCaptures<String>()
        layers.forEach { layer ->
            assertTrue(captures.shouldCapture(layer, sharedArtwork = true))
            captures.markCaptured(layer, sharedArtwork = true)
        }
        // Drawing the overlay must not preserve a mini/full snapshot from before
        // it took ownership, or cause every animation frame to be recorded again.
        layers.forEach { assertFalse(captures.shouldCapture(it, sharedArtwork = true)) }
    }

    @Test fun lateGeometryReplacesBothForegroundSnapshotsIncludingTheFullCover() {
        val captures = PlayerSheetCaptures<String>()
        layers.forEach { captures.markCaptured(it, sharedArtwork = false) }

        layers.forEach { assertTrue(captures.shouldCapture(it, sharedArtwork = true)) }
        // A mounted image must not certify the stale foreground snapshots.
        layers.forEach {
            assertTrue(captures.shouldCapture(it, sharedArtwork = true))
            captures.markCaptured(it, sharedArtwork = true)
        }
        layers.forEach { assertFalse(captures.shouldCapture(it, sharedArtwork = true)) }
    }

    @Test fun lostGeometryRestoresEndpointArtworkInTheFallbackSnapshots() {
        val captures = PlayerSheetCaptures<String>()
        layers.forEach { captures.markCaptured(it, sharedArtwork = true) }
        layers.forEach { assertTrue(captures.shouldCapture(it, sharedArtwork = false)) }
    }

    @Test fun reversalKeepsTheSameCapturesButANewTransitionRefreshesThem() {
        val captures = PlayerSheetCaptures<String>()
        layers.forEach { captures.markCaptured(it, sharedArtwork = true) }
        // Direction changes preserve ownership and the current recorded image.
        repeat(3) {
            layers.forEach { assertFalse(captures.shouldCapture(it, sharedArtwork = true)) }
        }
        captures.clear()
        layers.forEach { assertTrue(captures.shouldCapture(it, sharedArtwork = true)) }
    }
}
