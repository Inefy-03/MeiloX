package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicInfoFadeTest {
    @Test fun informationFadesInSequenceWithBoundedOpacity() {
        var previousAlbum = 1f
        var previousHeader = 0f
        for (step in -10..110) {
            val p = step / 100f
            val album = appleMusicAlbumInfoAlpha(p)
            val header = appleMusicHeaderInfoAlpha(p)
            assertTrue(album in 0f..1f && header in 0f..1f)
            assertTrue(album <= previousAlbum)
            assertTrue(header >= previousHeader)
            assertTrue(album == 0f || header == 0f)
            previousAlbum = album
            previousHeader = header
        }
        assertEquals(1f, appleMusicAlbumInfoAlpha(0f), 0f)
        assertEquals(0f, appleMusicHeaderInfoAlpha(0f), 0f)
        assertEquals(0f, appleMusicAlbumInfoAlpha(1f), 0f)
        assertEquals(1f, appleMusicHeaderInfoAlpha(1f), 0f)
    }

    @Test fun headerFinishesFadingOnTheSameSpringFrameAsTheArtwork() {
        val normal = Rect(48f, 150f, 352f, 454f)
        val target = Rect(24f, 76f, 70f, 122f)
        val animation = TargetBasedAnimation(appleMusicLyricSpring(), Float.VectorConverter, 0f, 1f)
        for (time in 0 until animation.durationNanos step 16_666_667L) {
            val p = animation.getValueFromNanos(time)
            val frame = appleMusicArtworkFrame(normal, target, 12f, 4f, 16f, 1f, p)
            if (frame.bounds.width > target.width) assertTrue(appleMusicHeaderInfoAlpha(p) < 1f)
        }
        val end = animation.getValueFromNanos(animation.durationNanos)
        assertEquals(target, appleMusicArtworkFrame(normal, target, 12f, 4f, 16f, 1f, end).bounds)
        assertEquals(1f, appleMusicHeaderInfoAlpha(end), 0f)
        assertEquals(0f, appleMusicAlbumInfoAlpha(end), 0f)
    }

    @Test fun interruptedSpringRetainsBothOpacitiesAndReversesToAlbumInformation() = runBlocking {
        val clock = BroadcastFrameClock()
        val progress = Animatable(0f)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                clock.sendFrame(time)
                time += 16_666_667L
                yield()
            }
        }
        val opening = launch(clock) { progress.animateTo(1f, appleMusicLyricSpring()) }
        frames(10)
        opening.cancelAndJoin()
        val album = appleMusicAlbumInfoAlpha(progress.value)
        val header = appleMusicHeaderInfoAlpha(progress.value)
        assertTrue(header > 0f && header < 1f)
        frames(4)
        assertEquals(album, appleMusicAlbumInfoAlpha(progress.value), 0f)
        assertEquals(header, appleMusicHeaderInfoAlpha(progress.value), 0f)
        val closing = launch(clock) { progress.animateTo(0f, appleMusicLyricSpring()) }
        try {
            frames(1)
            assertEquals(album, appleMusicAlbumInfoAlpha(progress.value), 0.0001f)
            assertEquals(header, appleMusicHeaderInfoAlpha(progress.value), 0.0001f)
            frames(120)
            assertEquals(1f, appleMusicAlbumInfoAlpha(progress.value), 0f)
            assertEquals(0f, appleMusicHeaderInfoAlpha(progress.value), 0f)
        } finally { closing.cancelAndJoin() }
    }
}
