package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.ui.component.sheet.PlayerSheetStiffness
import com.ljyh.mei.ui.component.utils.lerp

internal fun appleMusicLyricSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PlayerSheetStiffness,
    // Fractional progress drives hundreds of pixels; the default 0.01 snaps visibly.
    visibilityThreshold = 0.0001f,
)

internal fun appleMusicAlbumInfoAlpha(progress: Float): Float =
    (1f - progress.coerceIn(0f, 1f) * 2f).coerceIn(0f, 1f)

internal fun appleMusicHeaderInfoAlpha(progress: Float): Float =
    (progress.coerceIn(0f, 1f) * 2f - 1f).coerceIn(0f, 1f)

internal data class AppleMusicArtworkFrame(
    val bounds: Rect,
    val cornerRadius: Float,
    val shadowElevation: Float,
)

/** Visual pixels, independent of the image's fixed layout size. */
internal fun appleMusicArtworkFrame(
    normalBounds: Rect,
    headerBounds: Rect,
    normalRadius: Float,
    headerRadius: Float,
    normalShadow: Float,
    playbackScale: Float,
    progress: Float,
): AppleMusicArtworkFrame {
    val p = progress.coerceIn(0f, 1f)
    val width = lerp(normalBounds.width, headerBounds.width, p)
    val height = lerp(normalBounds.height, headerBounds.height, p)
    val scale = lerp(playbackScale, 1f, p)
    val left = lerp(normalBounds.left, headerBounds.left, p) + width * (1f - scale) / 2f
    val top = lerp(normalBounds.top, headerBounds.top, p) + height * (1f - scale) / 2f
    return AppleMusicArtworkFrame(
        bounds = Rect(left, top, left + width * scale, top + height * scale),
        cornerRadius = lerp(normalRadius, headerRadius, p) * scale,
        shadowElevation = normalShadow * (1f - p) * scale,
    )
}
