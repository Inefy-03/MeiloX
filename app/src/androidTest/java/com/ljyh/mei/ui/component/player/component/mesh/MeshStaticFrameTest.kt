package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
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
