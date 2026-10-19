package com.ljyh.mei.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import kotlin.math.abs
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

    @Test
    fun navigationInnerShadowsPreserveContrastInsideSmallShapes() {
        val backingColors = listOf(Color.White, Color(0xFFF0F0F0), Color(0xFF202020), Color(0xFF404040))
        val bounds = listOf(Rect(8f, 8f, 52f, 52f), Rect(88f, 8f, 168f, 56f))
        compose.setContent {
            val renderers = remember { List(bounds.size) { NavigationInnerShadowRenderer() } }
            DisposableEffect(renderers) {
                onDispose { renderers.forEach { it.release() } }
            }
            Canvas(Modifier.width(176.dp).height(256.dp).testTag("navigationInnerShadows")) {
                val stripeHeight = 64.dp.toPx()
                backingColors.forEachIndexed { backingIndex, color ->
                    val stripeTop = backingIndex * stripeHeight
                    drawRect(
                        color = color,
                        topLeft = Offset(0f, stripeTop),
                        size = Size(size.width, stripeHeight),
                    )
                    bounds.forEachIndexed { shapeIndex, shapeBounds ->
                        renderers[shapeIndex].draw(
                            scope = this,
                            shape = if (shapeIndex == 0) CircleShape else Capsule(),
                            bounds = Rect(
                                left = shapeBounds.left.dp.toPx(),
                                top = stripeTop + shapeBounds.top.dp.toPx(),
                                right = shapeBounds.right.dp.toPx(),
                                bottom = stripeTop + shapeBounds.bottom.dp.toPx(),
                            ),
                            isDark = backingIndex >= 2,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val pixels = compose.onNodeWithTag("navigationInnerShadows").captureToImage().toPixelMap()
        fun pixelAt(xDp: Float, yDp: Float) = pixels[
            (xDp * pixels.width / 176f).toInt(),
            (yDp * pixels.height / 256f).toInt(),
        ]
        fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
        fun assertColor(message: String, expected: Color, actual: Color) {
            val tolerance = 1f / 255f
            assertEquals("$message red", expected.red, actual.red, tolerance)
            assertEquals("$message green", expected.green, actual.green, tolerance)
            assertEquals("$message blue", expected.blue, actual.blue, tolerance)
            assertEquals("$message alpha", expected.alpha, actual.alpha, tolerance)
        }
        backingColors.forEachIndexed { backingIndex, backingColor ->
            val isDark = backingIndex >= 2
            val stripeTop = backingIndex * 64f
            bounds.forEachIndexed { shapeIndex, shapeBounds ->
                val centerX = shapeBounds.center.x
                val topEdge = pixelAt(centerX, stripeTop + shapeBounds.top + 2f)
                val innerBand = pixelAt(centerX, stripeTop + shapeBounds.top + 10f)
                val bottomEdge = pixelAt(centerX, stripeTop + shapeBounds.bottom - 2f)
                val label = "Shape $shapeIndex over backing $backingIndex, dark=$isDark"
                val topContrast = (tone(topEdge) - tone(innerBand)) * 255f
                val bottomContrast = (tone(bottomEdge) - tone(innerBand)) * 255f
                val innerDarkening = (tone(backingColor) - tone(innerBand)) * 255f
                val minimumContrast = if (isDark) 3f else 5f
                // Negative spread keeps the bright rims narrow instead of whitening the whole face.
                assertTrue("$label top rim contrast: $topContrast", topContrast >= minimumContrast - 0.01f)
                assertTrue("$label bottom rim contrast: $bottomContrast", bottomContrast >= minimumContrast - 0.01f)
                assertTrue("$label inner darkening: $innerDarkening", innerDarkening >= 2f - 0.01f)
                if (isDark) {
                    val bottomLift = (tone(bottomEdge) - tone(backingColor)) * 255f
                    assertEquals("$label dark Plus rim lift", 9f, bottomLift, 2f)
                    assertEquals("$label dark LinearBurn band", 6f, innerDarkening, 2f)
                }
                assertEquals("$label stays opaque", 1f, innerBand.alpha, 1f / 255f)
                assertColor(
                    "$label outside bounds",
                    backingColor,
                    pixelAt(centerX, stripeTop + 4f),
                )
                assertColor(
                    "$label outside rounded corner",
                    backingColor,
                    pixelAt(shapeBounds.left + 2f, stripeTop + shapeBounds.top + 2f),
                )
            }
        }
    }

    @Test
    fun navigationInnerShadowsKeepBrightRimsAboveNeutralTint() {
        val lightBackings = listOf(Color.White, Color(0xFFF0F0F0))
        val darkBackings = listOf(Color(0xFF202020), Color(0xFF204080))
        val sizes = listOf(44 to 44, 80 to 48)
        val dark = mutableStateOf(false)
        compose.setContent {
            val isDark = dark.value
            val backingColors = if (isDark) darkBackings else lightBackings
            CompositionLocalProvider(
                LocalGlassColors provides defaultGlassColors(isDark = isDark),
                LocalGlassAppearance provides GlassAppearance(liquidGlassEnabled = false),
            ) {
                Column {
                    backingColors.forEachIndexed { backingIndex, backingColor ->
                        val backdrop = rememberCanvasBackdrop { drawRect(backingColor) }
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            sizes.forEachIndexed { shapeIndex, (width, height) ->
                                GlassSurface(
                                    modifier = Modifier.size(width.dp, height.dp)
                                        .testTag("navigationMaterial-$backingIndex-$shapeIndex"),
                                    backdrop = backdrop,
                                    shape = if (shapeIndex == 0) CircleShape else Capsule(),
                                    style = GlassSurfaceStyle.Navigation,
                                ) {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val lightReferences = mutableListOf<List<Color>>()
        listOf(false, true, false).forEachIndexed { appearanceIndex, isDark ->
            compose.runOnIdle { dark.value = isDark }
            compose.waitForIdle()
            lightBackings.indices.forEach { backingIndex ->
                sizes.forEachIndexed { shapeIndex, (_, height) ->
                    val pixels = compose.onNodeWithTag("navigationMaterial-$backingIndex-$shapeIndex")
                        .captureToImage().toPixelMap()
                    fun pixelAt(yDp: Int) = pixels[
                        pixels.width / 2,
                        (yDp * pixels.height / height.toFloat()).toInt(),
                    ]
                    fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
                    val topEdge = pixelAt(0)
                    val innerBand = pixelAt(10)
                    val bottomEdge = pixelAt(height - 2)
                    val center = pixelAt(height / 2)
                    val label = "Material $shapeIndex over backing $backingIndex, dark=$isDark"
                    val topContrast = (tone(topEdge) - tone(innerBand)) * 255f
                    val bottomContrast = (tone(bottomEdge) - tone(innerBand)) * 255f
                    val innerTone = tone(innerBand) * 255f
                    val minimumContrast = if (isDark) 3f else 5f
                    // Tint must not flatten the contrast created by the inner shadows above it.
                    assertTrue("$label top rim contrast: $topContrast", topContrast >= minimumContrast)
                    assertTrue("$label bottom rim contrast: $bottomContrast", bottomContrast >= minimumContrast)
                    if (isDark) {
                        if (backingIndex == 0) {
                            assertEquals("$label sampled red", 42f, center.red * 255f, 4f)
                            assertEquals("$label sampled green", 42f, center.green * 255f, 4f)
                            assertEquals("$label sampled blue", 43f, center.blue * 255f, 4f)
                        } else {
                            // The original dark mask attenuates the luminosity sample's channel difference.
                            val blueRedDifference = (center.blue - center.red) * 255f
                            assertEquals("$label luminosity preserves color difference", 86f, blueRedDifference, 3f)
                        }
                    } else {
                        assertTrue("$label inner tone: $innerTone", innerTone < 251f)
                    }
                    assertEquals("$label stays opaque", 1f, innerBand.alpha, 1f / 255f)
                    val samples = listOf(topEdge, innerBand, bottomEdge, center)
                    if (appearanceIndex == 0) {
                        lightReferences += samples
                    } else if (!isDark) {
                        val original = lightReferences[backingIndex * sizes.size + shapeIndex]
                        samples.zip(original).forEachIndexed { sampleIndex, (actual, expected) ->
                            val tolerance = 1f / 255f
                            assertEquals("$label restored red $sampleIndex", expected.red, actual.red, tolerance)
                            assertEquals("$label restored green $sampleIndex", expected.green, actual.green, tolerance)
                            assertEquals("$label restored blue $sampleIndex", expected.blue, actual.blue, tolerance)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun morphMaterialMatchesTheOriginalTileCompositionInBothAppearances() {
        val dark = mutableStateOf(false)
        val expanded = mutableStateOf(false)
        val backingIndex = mutableStateOf(0)
        val brightBackings = listOf(Color(0xFFF0F0F0), Color.White)
        val capsuleBounds = Rect(12f, 80f, 132f, 128f)
        val expandedBounds = Rect(8f, 12f, 136f, 196f)
        compose.setContent {
            val isDark = dark.value
            val boundsDp = if (expanded.value) expandedBounds else capsuleBounds
            val cornerRadiusDp = if (expanded.value) 18f else 24f
            val backing = when (backingIndex.value) {
                0 -> if (isDark) Color(0xFF343434) else Color(0xFFB3B3B3)
                else -> brightBackings[backingIndex.value - 1]
            }
            val tint = defaultGlassColors(isDark).container
            val tintMultiplier = 1.25f
            val backdrop = rememberCanvasBackdrop { drawRect(backing) }
            CompositionLocalProvider(
                LocalGlassAppearance provides GlassAppearance(liquidGlassEnabled = false),
            ) {
                val renderer = rememberGlassMorphRenderer(backdrop, active = true, renderScale = 0.25f)
                val referenceShadows = remember { NavigationInnerShadowRenderer(combineMasks = false) }
                val materialPaint = remember { Paint() }
                val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
                DisposableEffect(referenceShadows) {
                    onDispose { referenceShadows.release() }
                }
                Row {
                    listOf(true, false).forEach { actual ->
                        Canvas(
                            Modifier.size(144.dp, 208.dp)
                                .testTag(if (actual) "morphMaterialActual" else "morphMaterialReference")
                                .onGloballyPositioned { if (actual) coordinates.value = it },
                        ) {
                            assertTrue("The material comparison requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                            drawRect(Color(0xFF6C4769))
                            val bounds = Rect(
                                boundsDp.left.dp.toPx(), boundsDp.top.dp.toPx(),
                                boundsDp.right.dp.toPx(), boundsDp.bottom.dp.toPx(),
                            )
                            val radius = cornerRadiusDp.dp.toPx()
                            val shape = ContinuousRoundedRectangle(cornerRadiusDp.dp)
                            if (actual) {
                                val currentCoordinates = coordinates.value ?: return@Canvas
                                val origin = currentCoordinates.positionInRoot()
                                renderer.draw(
                                    scope = this,
                                    coordinates = currentCoordinates,
                                    viewport = Rect(origin, size),
                                    origin = origin,
                                    bounds = bounds,
                                    radius = radius,
                                    shape = shape,
                                    tint = tint,
                                    tintMultiplier = tintMultiplier,
                                    highlightAlpha = 0f,
                                    navigationOutline = false,
                                    navigationMaterial = true,
                                    isDark = isDark,
                                )
                            } else {
                                val outline = shape.createOutline(bounds.size, layoutDirection, this)
                                val path = Path().apply {
                                    when (outline) {
                                        is Outline.Rectangle -> addRect(outline.rect)
                                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                                        is Outline.Generic -> addPath(outline.path)
                                    }
                                    translate(bounds.topLeft)
                                }
                                // A uniform gray sample is unchanged by saturation and blur.
                                // Keep the former saveLayer/tint/shadow order inside every tile.
                                clipGlassShape(bounds, radius, shape, path) {
                                    val canvas = drawContext.canvas
                                    canvas.saveLayer(bounds, materialPaint)
                                    try {
                                        drawRect(backing)
                                        drawNavigationGlassMaterial(tint, isDark, tintMultiplier)
                                        referenceShadows.draw(this, shape, bounds, isDark)
                                    } finally {
                                        canvas.restore()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val scenarios = listOf(false, true).flatMap { isDark ->
            listOf(false, true).flatMap { isExpanded ->
                (0..brightBackings.size).map { backing -> Triple(isDark, isExpanded, backing) }
            }
        }
        scenarios.forEach { (isDark, isExpanded, backing) ->
            compose.runOnIdle {
                dark.value = isDark
                expanded.value = isExpanded
                backingIndex.value = backing
            }
            compose.waitForIdle()
            val actual = compose.onNodeWithTag("morphMaterialActual").captureToImage().toPixelMap()
            val reference = compose.onNodeWithTag("morphMaterialReference").captureToImage().toPixelMap()
            assertEquals(reference.width, actual.width)
            assertEquals(reference.height, actual.height)
            val bounds = if (isExpanded) expandedBounds else capsuleBounds
            val radius = if (isExpanded) 18f else 24f
            val scale = actual.width / 144f
            val samples = listOf(
                "face" to bounds.center,
                "top rim" to Offset(bounds.center.x, bounds.top + 2f),
                "upper inner band" to Offset(bounds.center.x, bounds.top + 10f),
                "bottom rim" to Offset(bounds.center.x, bounds.bottom - 2f),
                "left rim" to Offset(bounds.left + 2f, bounds.center.y),
                "right rim" to Offset(bounds.right - 2f, bounds.center.y),
                "corner interior" to Offset(bounds.left + radius * 0.75f, bounds.top + radius * 0.75f),
                "outside" to Offset(bounds.left - 2f, bounds.center.y),
            )
            val label = "Morph material, expanded=$isExpanded, dark=$isDark, backing=$backing"
            samples.forEach { (sample, point) ->
                val x = (point.x * scale).toInt()
                val y = (point.y * scale).toInt()
                val expected = reference[x, y]
                val observed = actual[x, y]
                val tolerance = 2f / 255f
                assertEquals("$label $sample red", expected.red, observed.red, tolerance)
                assertEquals("$label $sample green", expected.green, observed.green, tolerance)
                assertEquals("$label $sample blue", expected.blue, observed.blue, tolerance)
                assertEquals("$label $sample alpha", expected.alpha, observed.alpha, tolerance)
            }
            // Check the whole silhouette too; only a small AA fringe may differ.
            var totalError = 0f
            var outliers = 0
            val left = (bounds.left * scale).toInt()
            val top = (bounds.top * scale).toInt()
            val right = (bounds.right * scale).toInt()
            val bottom = (bounds.bottom * scale).toInt()
            for (y in top until bottom) {
                for (x in left until right) {
                    val expected = reference[x, y]
                    val observed = actual[x, y]
                    val error = maxOf(
                        abs(expected.red - observed.red), abs(expected.green - observed.green),
                        abs(expected.blue - observed.blue), abs(expected.alpha - observed.alpha),
                    )
                    totalError += error
                    if (error > 4f / 255f) outliers++
                }
            }
            val area = (right - left) * (bottom - top)
            assertTrue("$label mean pixel error: ${totalError / area}", totalError / area <= 1f / 255f)
            val fringePixels = 4 * ((right - left) + (bottom - top))
            assertTrue("$label differing pixels: $outliers", outliers <= fringePixels)
            fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
            val centerX = (bounds.center.x * scale).toInt()
            val rim = reference[centerX, ((bounds.bottom - 2f) * scale).toInt()]
            val band = reference[centerX, ((bounds.top + 10f) * scale).toInt()]
            assertTrue("$label reference must contain visible inner shadows", tone(rim) - tone(band) >= 3f / 255f)
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
