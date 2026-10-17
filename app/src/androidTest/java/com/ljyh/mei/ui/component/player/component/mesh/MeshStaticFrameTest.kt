package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ljyh.mei.playback.PlaybackBeatMeter
import com.ljyh.mei.ui.component.player.LocalPlayerBackgroundReadyChanged
import com.ljyh.mei.ui.component.player.component.FluidBackground
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshStaticFrameTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settledStaticLayerSurvivesExplicitRedrawsWithoutAnimating() {
        val renderer = MeshGradientRenderer().apply {
            staticMode = true
            setAlbum(meshTestAlbum())
            advanceFrame(StartNanos)
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val expected = host.capture()
        assertTrue("The first album must be ready", renderer.hasAlbum)
        assertTrue("The static frame must contain artwork", expected.any {
            Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24
        })
        assertFalse("A settled static frame needs no continuous rendering", renderer.needsAnimation)

        repeat(3) { redraw ->
            val actual = host.capture {
                renderer.advanceFrame(StartNanos + (redraw + 1) * 60_000_000_000L)
            }
            assertArrayEquals("An explicit redraw must retain the complete static frame", expected, actual)
            assertFalse("Redrawing a static layer must remain on demand", renderer.needsAnimation)
        }
    }

    @Test
    fun pausedPlaybackCompletesAlbumCrossfadeThenStops() {
        assertAlbumCrossfadeSettles(staticMode = false)
    }

    @Test
    fun staticModeCompletesAlbumCrossfadeThenStops() {
        assertAlbumCrossfadeSettles(staticMode = true)
    }

    @Test
    fun resumingPlaybackExcludesTimeSpentPaused() {
        val renderer = MeshGradientRenderer().apply {
            setAlbum(meshTestAlbum())
            advanceFrame(StartNanos)
            advanceFrame(StartNanos + FrameNanos)
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val playing = host.capture()
        val paused = host.capture {
            renderer.isPlaying = false
            renderer.advanceFrame(PausedNanos)
        }
        assertArrayEquals("Pausing must preserve the current flow phase", playing, paused)
        assertFalse("Paused flow needs no continuous rendering", renderer.needsAnimation)

        val resumed = host.capture {
            renderer.isPlaying = true
            renderer.advanceFrame(PausedNanos + 60_000_000_000L)
        }
        assertArrayEquals("The first resumed frame must exclude the paused interval", paused, resumed)
        val advanced = host.capture {
            renderer.advanceFrame(PausedNanos + 60_000_000_000L + FrameNanos)
        }
        assertTrue("Flow must advance after playback resumes", advanced.indices.any {
            colorDistance(advanced[it], resumed[it]) > 2
        })
    }

    @Test
    fun fluidBackgroundPublishesReadinessAcrossAlbumChangesAndDisposal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun cover(color: Int): File {
            val file = File.createTempFile("mesh-readiness-", ".png", context.cacheDir)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            return file
        }
        val first = cover(Color.rgb(210, 80, 40))
        val second = cover(Color.rgb(40, 80, 210))
        val imageUrl = mutableStateOf<String?>(null)
        val visible = mutableStateOf(true)
        val events = CopyOnWriteArrayList<Boolean>()
        val beatMeter = PlaybackBeatMeter()
        fun redBlueBalance(): Double {
            val image = compose.onNodeWithTag("fluidBackground").captureToImage()
            val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
            return pixels.sumOf { Color.red(it) - Color.blue(it) }.toDouble() / pixels.size
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(
                    LocalPlayerBackgroundReadyChanged provides { events += it },
                ) {
                    if (visible.value) {
                        FluidBackground(
                            imageUrl = imageUrl.value,
                            beatMeter = beatMeter,
                            isPlaying = false,
                            backdrop = backdrop,
                            modifier = Modifier.size(96.dp).testTag("fluidBackground"),
                        )
                    }
                }
            }
            compose.runOnIdle {
                assertTrue("A background without an album must report not ready", events.isNotEmpty())
                assertTrue("An empty background must never report ready", events.none { it })
                imageUrl.value = first.toURI().toString()
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                events.any { it }
            }
            assertTrue("The ready background must display the first cover", redBlueBalance() > 40)
            val notReadyCount = events.count { !it }
            compose.runOnIdle { imageUrl.value = second.toURI().toString() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                redBlueBalance() < -40
            }
            assertEquals("Replacing an album must retain the ready background", notReadyCount,
                events.count { !it })

            compose.runOnIdle { visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("Disposal must publish not ready", false, events.last())
                assertEquals(notReadyCount + 1, events.count { !it })
            }
        } finally {
            first.delete()
            second.delete()
        }
    }

    private fun assertAlbumCrossfadeSettles(staticMode: Boolean) {
        val renderer = MeshGradientRenderer().apply {
            this.staticMode = staticMode
            isPlaying = false
            setAlbum(meshTestAlbum(Color.rgb(220, 50, 50)))
            advanceFrame(StartNanos)
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val initial = host.capture()
        val transitioning = host.capture {
            renderer.setAlbum(meshTestAlbum(Color.rgb(50, 50, 220)))
            assertTrue("An album change must schedule its crossfade", renderer.needsAnimation)
            repeat(3) { renderer.advanceFrame(StartNanos + (it + 1) * FrameNanos) }
            assertTrue("The crossfade must remain active before it settles", renderer.needsAnimation)
        }
        val final = host.capture {
            repeat(5) { renderer.advanceFrame(StartNanos + (it + 4) * FrameNanos) }
        }
        assertTrue("The crossfade must change the displayed album", initial.indices.any {
            colorDistance(initial[it], final[it]) > 24
        })
        assertTrue("The intermediate frame must differ from the settled album", final.indices.any {
            colorDistance(transitioning[it], final[it]) > 5
        })
        assertFalse("Paused or static flow must stop after the crossfade", renderer.needsAnimation)
        assertArrayEquals("Settled crossfades must survive later redraws", final, host.capture {
            renderer.advanceFrame(PausedNanos)
        })
    }

    private companion object {
        const val PausedNanos = 3_600_000_000_000L
    }
}
