package com.ljyh.mei.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassControlsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val checked = mutableStateOf(false)
    private val enabled = mutableStateOf(true)
    private val revision = mutableStateOf(0)
    private val calls = mutableListOf<Pair<Int, Boolean>>()
    private val listState = LazyListState()
    private var parentClicks = 0

    @Test
    fun toggleTapsUseLatestStateAndCallbackWithoutClickingTheParent() {
        showToggle()
        toggle().performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertTrue(checked.value)
            revision.value = 1
        }
        toggle().performTouchInput { down(center); up() }
        toggle().performTouchInput { down(center); up() }

        compose.runOnIdle {
            assertTrue(checked.value)
            assertEquals(listOf(0 to true, 1 to false, 1 to true), calls)
            assertEquals(0, parentClicks)
        }
    }

    @Test
    fun toggleDragUsesFinalPositionAndDoesNotScrollTheParentList() {
        showToggle()
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.45f, -height * 3f))
            up()
        }
        compose.runOnIdle { assertTrue(checked.value) }
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.45f, height * 3f))
            up()
        }

        compose.runOnIdle {
            assertFalse(checked.value)
            assertEquals(listOf(0 to true, 0 to false), calls)
            assertEquals(0, parentClicks)
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun canceledAndDisabledToggleGesturesNeverCommit() {
        showToggle()
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.45f, 0f))
            cancel()
        }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertTrue(calls.isEmpty())
            enabled.value = false
        }
        toggle().performTouchInput { down(center); up() }
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.45f, 0f))
            up()
        }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertTrue(calls.isEmpty())
            assertEquals(0, parentClicks)
        }
    }

    @Test
    fun disablingAnActiveDragCancelsItAndExternalStateStillControlsTheToggle() {
        showToggle()
        toggle().performTouchInput { down(center); moveBy(Offset(width * 0.45f, 0f)) }
        compose.runOnIdle { enabled.value = false }
        toggle().performTouchInput { up() }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertTrue(calls.isEmpty())
            checked.value = true
            enabled.value = true
        }
        toggle().performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertEquals(listOf(0 to false), calls)
        }
    }

    @Test
    fun toggleSemanticsExposeStateAndRespectDisabledActions() {
        showToggle()
        toggle()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .assertHasClickAction()
            .performClick()
            .assertIsOn()
        compose.runOnIdle { enabled.value = false }
        toggle().assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertFalse(action())
        }
        compose.runOnIdle {
            assertTrue(checked.value)
            assertEquals(listOf(0 to true), calls)
            assertEquals(0, parentClicks)
        }
    }

    private fun toggle() = compose.onNodeWithTag("toggle")

    private fun showToggle() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                ) {
                    LazyColumn(Modifier.width(320.dp).height(240.dp), state = listState) {
                        item {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = enabled.value) {
                                        parentClicks++
                                        checked.value = !checked.value
                                    }
                                    .padding(32.dp),
                            ) {
                                val callbackRevision = revision.value
                                GlassToggle(
                                    checked = checked.value,
                                    onCheckedChange = { value ->
                                        calls += callbackRevision to value
                                        checked.value = value
                                    },
                                    modifier = Modifier.testTag("toggle"),
                                    enabled = enabled.value,
                                    backdrop = emptyBackdrop(),
                                )
                            }
                        }
                        items(20) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
