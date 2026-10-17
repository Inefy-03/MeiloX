package com.ljyh.mei.ui.glass

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassBottomBarInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val home = GlassTabItem("home", "Home", SfSymbol.House)
    private val radio = GlassTabItem("radio", "Radio", SfSymbol.RadioFilled)
    private val library = GlassTabItem("library", "Library", SfSymbol.MusicNoteList)
    private val items = mutableStateOf(listOf(home, radio, library))
    private val selectedKey = mutableStateOf(home.key)
    private val compactProgress = mutableFloatStateOf(0f)
    private val selections = mutableListOf<String>()
    private var expansions = 0

    @Test
    fun destinationUpdatesNeverRequestAnotherNavigation() {
        showBar()

        listOf(library.key, radio.key, home.key).forEach { destination ->
            compose.runOnIdle { selectedKey.value = destination }
            compose.runOnIdle { assertTrue(selections.isEmpty()) }
        }
    }

    @Test
    fun reorderedTabsAndCompactTransitionsOnlyNavigateAfterAnActualDrag() {
        selectedKey.value = library.key
        showBar()

        compose.runOnIdle { items.value = listOf(library, home, radio) }
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        listOf(0.5f, 1f, 0.5f, 0f).forEach { progress ->
            compose.runOnIdle { compactProgress.floatValue = progress }
            compose.runOnIdle { assertTrue(selections.isEmpty()) }
        }

        compose.onNodeWithTag("bottomBar").performTouchInput {
            swipe(
                start = Offset(width / 6f, center.y),
                end = Offset(width * 5f / 6f, center.y),
                durationMillis = 300,
            )
        }
        compose.runOnIdle { assertEquals(listOf(radio.key), selections) }
    }

    @Test
    fun tabTapAndIndicatorDragEachSelectTheirDestinationOnce() {
        showBar()

        compose.onNodeWithContentDescription(radio.label).performClick()
        compose.runOnIdle { assertEquals(listOf(radio.key), selections) }

        // The selected tab's indicator handles the drag; the other tabs use click actions.
        compose.onNodeWithTag("bottomBar").performTouchInput {
            swipe(
                start = center,
                end = Offset(width * 5f / 6f, center.y),
                durationMillis = 300,
            )
        }

        compose.runOnIdle {
            assertEquals(listOf(radio.key, library.key), selections)
            assertEquals(library.key, selectedKey.value)
        }
    }

    @Test
    fun compactTapAndDragExpandWithoutSelectingATab() {
        compactProgress.floatValue = 1f
        showBar()

        compose.onNodeWithContentDescription(home.label).performClick()
        compose.runOnIdle {
            assertEquals(1, expansions)
            assertTrue(selections.isEmpty())
            compactProgress.floatValue = 1f
        }

        compose.onNodeWithContentDescription(home.label).performTouchInput {
            swipe(
                start = center,
                end = Offset(center.x + width, center.y),
                durationMillis = 300,
            )
        }

        compose.runOnIdle {
            assertEquals(2, expansions)
            assertTrue(selections.isEmpty())
        }
    }

    private fun showBar() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                ) {
                    GlassBottomBar(
                        items = items.value,
                        selectedKey = selectedKey.value,
                        onSelected = { key ->
                            selections += key
                            selectedKey.value = key
                        },
                        onExpand = {
                            expansions += 1
                            compactProgress.floatValue = 0f
                        },
                        compactProgress = compactProgress,
                        compactSize = 48.dp,
                        modifier = Modifier.width(320.dp).testTag("bottomBar"),
                        backdrop = emptyBackdrop(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
