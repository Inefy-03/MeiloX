package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.ljyh.mei.ui.component.player.FloatingLyricsPipContent
import com.ljyh.mei.ui.component.player.component.applemusic.playerLyricsFade
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLyricsRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val clickedLine = AtomicReference<ISyncedLine>()

    @Test
    fun floatingLyricsRemainReadableWhenTheCardThemeChanges() {
        val isDark = mutableStateOf(false)
        val lines = listOf("Current lyric", "Translated lyric", "Next lyric")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark.value),
                    LocalGlassBackdrop provides emptyBackdrop(),
                ) {
                    Box(Modifier.width(320.dp).height(180.dp)) {
                        FloatingLyricsPipContent(lines[0], lines[1], lines[2], 1f)
                    }
                }
            }
        }

        fun assertReadable(dark: Boolean) {
            val background = defaultGlassColors(dark).elevatedBackground.luminance()
            for (line in lines) {
                val pixels = compose.onNodeWithText(line).assertIsDisplayed().captureToImage().toPixelMap()
                var foregroundPixels = 0
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        if (kotlin.math.abs(pixels[x, y].luminance() - background) > 0.15f) foregroundPixels++
                    }
                }
                assertTrue("$line must contrast with its ${if (dark) "dark" else "light"} card", foregroundPixels > 20)
            }
        }

        assertReadable(dark = false)
        compose.runOnIdle { isDark.value = true }
        assertReadable(dark = true)
    }

    @Test
    fun wrappedKaraokeLyricsRenderAndSeekToTheirStart() {
        val line = KaraokeLine.MainKaraokeLine(
            syllables = listOf(
                KaraokeSyllable("Always ", 1_000, 2_000),
                KaraokeSyllable("follow ", 2_000, 3_000),
                KaraokeSyllable("your ", 3_000, 4_000),
                KaraokeSyllable("song  ", 4_000, 5_000),
            ),
            translation = "Keep singing",
            alignment = KaraokeAlignment.Start,
            start = 1_000,
            end = 5_000,
        )

        showLyrics(line)

        // Karaoke words are drawn on a Canvas. The merged translation node also
        // covers the wrapped canvas and exposes the real line's click action.
        // Trailing word spaces exercise the UI library's KaraokeSyllable.copy call.
        compose.onNodeWithText("Keep singing")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(100.dp)
            .performClick()
        compose.runOnIdle {
            assertSame(line, clickedLine.get())
            assertEquals(1_000, clickedLine.get().start)
        }
    }

    @Test
    fun syncedLyricsRenderAndSeekToTheirStart() {
        val line = SyncedLine(
            content = "A simple lyric line",
            translation = "Keep singing",
            start = 2_000,
            end = 5_000,
        )

        showLyrics(line)

        compose.onNodeWithText("A simple lyric line").assertIsDisplayed().performClick()
        compose.onNodeWithText("Keep singing").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(line, clickedLine.get())
            assertEquals(2_000, clickedLine.get().start)
        }
    }

    @Test
    fun lyricsKeepAdditiveBlendingThroughoutThePlayerFade() {
        val visibility = mutableStateOf(0.25f)
        val background = Color(0.10f, 0.25f, 0.40f)
        val line = SyncedLine(
            content = "MMMM",
            translation = "",
            start = 1_000,
            end = 5_000,
        )
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .width(260.dp)
                        .height(240.dp)
                        .background(background)
                        .testTag("lyrics-fade-background")
                ) {
                    Box(Modifier.fillMaxSize().playerLyricsFade(visibility)) {
                        PlayerLyricsView(
                            listState = rememberLazyListState(),
                            lyrics = SyncedLyrics(lines = listOf(line)),
                            currentPosition = { 0 },
                            onLineClicked = {},
                            onLinePressed = {},
                            isManualScrolling = false,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    blendMode = BlendMode.Plus
                                    compositingStrategy = CompositingStrategy.Offscreen
                                },
                            useBlurEffect = false,
                            showPhonetic = false,
                            offset = 48.dp,
                            keepAliveZone = 0.dp,
                        )
                    }
                }
            }
        }

        for (alpha in listOf(0.25f, 0.5f, 0.75f, 1f, 0.5f, 0f)) {
            compose.runOnIdle { visibility.value = alpha }
            val pixels = compose.onNodeWithTag("lyrics-fade-background")
                .captureToImage()
                .toPixelMap()
            // A solid glyph interior has the highest red channel. Capturing the
            // background too distinguishes additive blending from a flattened fade.
            var glyph = pixels[0, 0]
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (pixels[x, y].red > glyph.red) glyph = pixels[x, y]
                }
            }
            val contribution = 0.4f * alpha
            val tolerance = 0.03f
            assertEquals("Red contribution at visibility $alpha", background.red + contribution, glyph.red, tolerance)
            assertEquals("Green contribution at visibility $alpha", background.green + contribution, glyph.green, tolerance)
            assertEquals("Blue contribution at visibility $alpha", background.blue + contribution, glyph.blue, tolerance)
        }
    }

    private fun showLyrics(line: ISyncedLine) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp).height(480.dp)) {
                    PlayerLyricsView(
                        listState = rememberLazyListState(),
                        lyrics = SyncedLyrics(lines = listOf(line)),
                        currentPosition = { 3_500 },
                        onLineClicked = clickedLine::set,
                        onLinePressed = {},
                        isManualScrolling = false,
                        useBlurEffect = false,
                        showPhonetic = false,
                        offset = 32.dp,
                        keepAliveZone = 0.dp,
                    )
                }
            }
        }
    }
}
