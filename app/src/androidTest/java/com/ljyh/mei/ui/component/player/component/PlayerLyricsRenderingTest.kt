package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLyricsRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val clickedLine = AtomicReference<ISyncedLine>()

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
