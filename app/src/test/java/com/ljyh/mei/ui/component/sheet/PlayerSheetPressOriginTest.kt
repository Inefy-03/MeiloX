package com.ljyh.mei.ui.component.sheet

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class PlayerSheetPressOriginTest {
    private val layout = Rect(20f, 700f, 420f, 750f)
    private val localPress = Rect(-16f, -5.5f, 424f, 49.5f)
    private val pressed = Rect(4f, 694.5f, 444f, 749.5f)
    private val artwork = Rect(32f, 708f, 64f, 740f)
    private val header = Rect(24f, 76f, 70f, 122f)
    private val host = Rect(0f, 0f, 440f, 800f)

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }

    @Test fun pressedCoordinatesRecoverTheRawContainerWithoutApplyingPressTwice() {
        val shiftedMeasurement = Rect(4f, 694.5f, 404f, 744.5f)
        val raw = playerMiniLayoutRect(pressed, localPress, IntSize(400, 50), shiftedMeasurement)
        assertBounds(layout, raw)
        assertBounds(pressed, playerUntransformRect(raw, raw, pressed))
    }

    @Test fun firstLyricTransitionFrameStartsAtTheVisiblePressedCover() {
        val raw = playerMiniLayoutRect(pressed, localPress, IntSize(400, 50), layout)
        val visualArtwork = playerUntransformRect(artwork, raw, pressed)
        assertBounds(Rect(17.2f, 703.3f, 52.4f, 738.5f), visualArtwork)
        assertBounds(visualArtwork, playerTopAnchoredArtworkRect(visualArtwork, header, pressed, host, 0f))
        assertBounds(header, playerTopAnchoredArtworkRect(visualArtwork, header, pressed, host, 1f))
    }

    @Test fun reverseProgressReturnsToTheRawCoverRatherThanThePressedOffset() {
        val release = PlayerPressRelease()
        release.update(0.5f)
        release.update(0.25f)
        val visual = playerUntransformRect(artwork, layout, pressed)
        val returning = playerContainerRect(artwork, visual, release.fraction(0.25f))
        assertBounds(Rect(24.6f, 705.65f, 58.2f, 739.25f), returning)
        assertBounds(artwork, playerContainerRect(artwork, visual, release.fraction(0f)))
    }

    @Test fun unavailableCoordinatesUseTheLastUsableLayout() {
        assertEquals(layout, playerMiniLayoutRect(null, localPress, IntSize(400, 50), layout))
        assertEquals(layout, playerMiniLayoutRect(pressed, Rect.Zero, IntSize(400, 50), layout))
        assertEquals(layout, playerMiniLayoutRect(pressed, localPress, IntSize.Zero, layout))
    }
}
