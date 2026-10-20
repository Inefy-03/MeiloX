package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicMarqueeText
import com.ljyh.mei.ui.component.player.component.applemusic.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppleMusicTitleRenderingTest {
    @get:Rule val compose = createComposeRule()

    private fun showMarquee(text: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(190.dp).background(Color.Black).testTag("metadata")) {
                    AppleMusicMarqueeText(
                        text = text,
                        style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp),
                        color = Color.White,
                        edgeWidth = 26.dp,
                        enabled = true,
                        modifier = Modifier.padding(start = 26.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun image() = compose.onNodeWithTag("metadata").captureToImage()

    @Test fun overflowingMetadataWaitsThenScrolls() {
        showMarquee("A long title with enough different words to overflow the available width")
        val initial = image()
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(samePixels(initial, image()))
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse("Overflowing metadata must scroll after the initial wait", samePixels(initial, image()))
    }

    @Test fun shortMetadataStaysStill() {
        showMarquee("Short title")
        val initial = image()
        compose.mainClock.advanceTimeBy(8_000)
        assertTrue("Fitting metadata must not scroll or fade", samePixels(initial, image()))
    }

    @Test fun pressingMetadataDoesNotDarkenItAndStillInvokesTheAction() {
        compose.mainClock.autoAdvance = false
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Title(
                    title = "Short title",
                    subTitle = "Artist",
                    isLiked = false,
                    onLikeClick = {},
                    onMoreClick = {},
                    onTitleClick = { clicks++ },
                    needShadow = false,
                    marqueeEnabled = false,
                    modifier = Modifier.width(360.dp).background(Color.Black).testTag("metadata"),
                )
            }
        }
        val initial = image()
        compose.onNodeWithText("Short title").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("The title press must not add a dark overlay", samePixels(initial, image()))
        compose.onNodeWithText("Short title").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun samePixels(first: ImageBitmap, second: ImageBitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y] != b[x, y]) return false
        }
        return true
    }
}
