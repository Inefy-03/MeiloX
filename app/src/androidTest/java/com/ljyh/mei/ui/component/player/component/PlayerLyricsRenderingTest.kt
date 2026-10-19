package com.ljyh.mei.ui.component.player.component

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.player.FloatingLyricsPipContent
import com.ljyh.mei.ui.component.player.component.applemusic.playerLyricsFade
import com.ljyh.mei.ui.component.player.component.sheet.LyricSelectionSheet
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.LocalBlurBackdrop
import com.ljyh.mei.ui.glass.LocalGlassBackdrop
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.defaultGlassColors
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
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

    @Test
    fun lyricSelectionKeepsNativeCandidatesDuringSearchAndExposesTheirSources() {
        val expandSheet = mutableStateOf(false)
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        val candidates = listOf(
            lyricCandidate(LyricSource.NetEaseCloudMusic, "NetEase candidate"),
            lyricCandidate(LyricSource.QQMusic, "QQ candidate"),
            lyricCandidate(LyricSource.AM, "Apple candidate"),
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) {
                        if (expandSheet.value) sheetState.expand()
                    }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = candidates,
                        offsetMs = 0,
                        isLoading = loading.value,
                        errorMessage = error.value,
                        onSelectCandidate = {},
                        onAdjustOffset = {},
                        onResetOffset = {},
                        onResetSource = {},
                        onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        compose.waitForIdle()

        for (label in listOf(
            R.string.lyric_selection_source_netease,
            R.string.lyric_selection_source_qq,
            R.string.lyric_selection_source_am,
        )) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(text(label)))
            compose.onNodeWithContentDescription(text(label), useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
        compose.runOnIdle {
            loading.value = false
            error.value = "Search unavailable"
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Search unavailable"))
        compose.onNodeWithText("Search unavailable").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(candidates.first().title))
        compose.onNodeWithText(candidates.first().title).assertIsDisplayed()
    }

    @Test
    fun lyricSelectionAdjustsTimingInHalfSecondsAndKeepsTheResetActionsSeparate() {
        val expandSheet = mutableStateOf(false)
        val offset = mutableStateOf(0)
        val adjustments = mutableListOf<Int>()
        var sourceResets = 0
        var timingResets = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) {
                        if (expandSheet.value) sheetState.expand()
                    }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = emptyList(),
                        offsetMs = offset.value,
                        isLoading = false,
                        errorMessage = null,
                        onSelectCandidate = {},
                        onAdjustOffset = {
                            adjustments += it
                            offset.value += it
                        },
                        onResetOffset = { timingResets++; offset.value = 0 },
                        onResetSource = { sourceResets++ },
                        onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        compose.waitForIdle()

        fun click(resource: Int) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(resource)))
            compose.onNode(hasClickAction() and hasText(text(resource))).performClick()
        }
        click(R.string.lyric_selection_later)
        compose.onNodeWithTag("lyric-selection-offset")
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.lyric_selection_offset, 0.5))
        click(R.string.lyric_selection_earlier)
        click(R.string.lyric_selection_earlier)
        compose.onNodeWithTag("lyric-selection-offset")
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.lyric_selection_offset, -0.5))
        click(R.string.lyric_selection_reset_source)
        compose.runOnIdle { assertEquals(-500, offset.value) }
        click(R.string.lyric_selection_reset_delay)
        compose.runOnIdle {
            assertEquals(listOf(500, -500, -500), adjustments)
            assertEquals(1, sourceResets)
            assertEquals(1, timingResets)
            assertEquals(0, offset.value)
        }
    }

    @Test
    fun closingOrSelectingLyricsKeepsTheSheetComposedUntilItsHideAnimationFinishes() {
        val open = mutableStateOf(true)
        val sheet = AtomicReference<SheetState>()
        val selected = AtomicReference<LyricCandidate>()
        val candidate = lyricCandidate(LyricSource.AM, "Apple candidate")
        var dismissed = 0
        var disposed = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    if (open.value) {
                        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                        SideEffect { sheet.set(state) }
                        DisposableEffect(Unit) { onDispose { disposed++ } }
                        LyricSelectionSheet(
                            mediaMetadata = lyricReference,
                            candidates = listOf(candidate),
                            offsetMs = 0,
                            isLoading = false,
                            errorMessage = null,
                            onSelectCandidate = selected::set,
                            onAdjustOffset = {},
                            onResetOffset = {},
                            onResetSource = {},
                            onDismiss = { dismissed++; open.value = false },
                            sheetState = state,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(sheet.get().isVisible) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(text(R.string.lyric_selection_close)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(0, dismissed)
            assertEquals(0, disposed)
            assertTrue(sheet.get().isVisible)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(1, disposed)
            assertFalse(sheet.get().isVisible)
        }

        compose.mainClock.autoAdvance = true
        compose.runOnIdle { open.value = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(candidate.title))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(candidate.title).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertSame(candidate, selected.get())
            assertEquals(1, dismissed)
            assertEquals(1, disposed)
            assertTrue(sheet.get().isVisible)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(2, dismissed)
            assertEquals(2, disposed)
            assertFalse(sheet.get().isVisible)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun lyricSheetKeepsTheRealDialogNavigationBarTransparent() {
        val window = AtomicReference<Window?>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    IosModalSheet(onDismissRequest = {}) {
                        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                        SideEffect { window.set(dialogWindow) }
                        Text("Sheet content", Modifier.padding(24.dp))
                    }
                }
            }
        }

        compose.onNodeWithText("Sheet content").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull("The sheet must expose its actual dialog window", window.get())
            val actual = requireNotNull(window.get())
            assertTrue(actual.decorView.isAttachedToWindow)
            assertFalse(actual.isNavigationBarContrastEnforced)
            assertEquals(android.graphics.Color.TRANSPARENT, actual.navigationBarColor)
            assertEquals(android.graphics.Color.TRANSPARENT, actual.navigationBarDividerColor)
        }
    }

    @Test
    fun lyricSelectionScrollsBehindNavigationWhileItsLastCandidateRemainsSafe() {
        val expandSheet = mutableStateOf(false)
        val density = AtomicReference<Density>()
        val candidates = List(30) { index ->
            lyricCandidate(LyricSource.QQMusic, "Candidate ${index + 1}")
                .copy(key = "candidate-$index")
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    val currentDensity = LocalDensity.current
                    SideEffect { density.set(currentDensity) }
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    LaunchedEffect(expandSheet.value) {
                        if (expandSheet.value) sheetState.expand()
                    }
                    LyricSelectionSheet(
                        mediaMetadata = lyricReference,
                        candidates = candidates,
                        offsetMs = 0,
                        isLoading = false,
                        errorMessage = null,
                        onSelectCandidate = {},
                        onAdjustOffset = {},
                        onResetOffset = {},
                        onResetSource = {},
                        onDismiss = {},
                        sheetState = sheetState,
                    )
                }
            }
        }
        compose.runOnIdle { expandSheet.value = true }
        compose.waitForIdle()

        val list = compose.onNodeWithTag("lyric-selection-list")
        val rootBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        assertEquals("The scrolling viewport must extend to the dialog bottom", rootBounds.bottom, listBounds.bottom, 1f)

        // Read the real sheet window's insets; no synthetic navigation area is supplied.
        var navigationBottom = 0
        compose.runOnIdle {
            fun findDialogWindow(view: View): Window? {
                if (view is DialogWindowProvider) return view.window
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        findDialogWindow(view.getChildAt(index))?.let { return it }
                    }
                }
                return null
            }
            val actual = WindowInspector.getGlobalWindowViews().mapNotNull(::findDialogWindow).single()
            navigationBottom = actual.decorView.rootWindowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        }

        // Reference and timing rows precede the candidates. Reaching the final index
        // must reveal the complete row plus its content-owned navigation padding.
        list.performScrollToIndex(candidates.size + 1)
        val lastCandidate = compose.onNodeWithText(candidates.last().title).assertIsDisplayed()
        val lastBounds = lastCandidate.getUnclippedBoundsInRoot()
        val lastTop = with(density.get()) { lastBounds.top.toPx() }
        val lastBottom = with(density.get()) { lastBounds.bottom.toPx() }
        val expectedEndPadding = with(density.get()) { 24.dp.toPx() } + navigationBottom
        assertTrue("The complete final candidate must fit inside the viewport", lastTop >= listBounds.top - 1f)
        assertEquals(
            "The final candidate must leave the navigation safe area inside the scrolling content",
            expectedEndPadding,
            rootBounds.bottom - lastBottom,
            2f,
        )
    }

    private val lyricReference = MediaMetadata(
        id = 1,
        title = "Reference track",
        coverUrl = "",
        artists = emptyList(),
        duration = 120_000,
        album = MediaMetadata.Album(1, "Reference album"),
    )

    private fun lyricCandidate(source: LyricSource, title: String) = LyricCandidate(
        key = source.name,
        source = source,
        title = title,
        artist = "Candidate artist",
        album = "Candidate album",
        coverUrl = "",
        durationMs = 120_000,
    )

    private fun text(resource: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *args)

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
