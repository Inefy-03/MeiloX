package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES30
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshStaticFrameTest {
    @Test
    fun staticRedrawRestoresLostBackBufferWithoutRestartingAnimation() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        ))
        check(count[0] > 0)
        val context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT)
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, Size, EGL14.EGL_HEIGHT, Size, EGL14.EGL_NONE), 0,
        )
        check(surface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        val renderer = MeshGradientRenderer()
        val album = Bitmap.createBitmap(Size, Size, Bitmap.Config.ARGB_8888).apply {
            val colors = IntArray(Size * Size) { index ->
                Color.rgb(64 + index % Size * 3, 64 + index / Size * 3, 128)
            }
            setPixels(colors, 0, Size, 0, 0, Size, Size)
        }
        var continuousRenderingNeeded = true
        renderer.onRenderDemandChanged = { continuousRenderingNeeded = it }
        try {
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, Size, Size)
            renderer.setStaticMode(true)
            renderer.setAlbum(album)
            renderer.onDrawFrame(null)
            val expected = readPixels()
            assertTrue("The initial album frame must be visible", renderer.hasRenderedAlbum)
            assertTrue("The fixture must contain visible artwork", expected.indices.any {
                it % 4 != 3 && (expected[it].toInt() and 255) > 24
            })
            assertFalse("A settled static frame needs no continuous rendering", continuousRenderingNeeded)

            repeat(3) {
                // A window swap may discard the next back buffer. A pbuffer clear models
                // that deterministically without depending on a particular GPU's swap policy.
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, Size, Size)
                GLES30.glClearColor(1f, 0f, 1f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                val previousVersion = renderer.renderedFrameVersion
                renderer.onDrawFrame(null)
                assertArrayEquals("An explicit redraw must restore the complete static frame", expected, readPixels())
                assertEquals(previousVersion + 1, renderer.renderedFrameVersion)
                assertFalse("Replaying the frame must keep on-demand rendering", continuousRenderingNeeded)
            }
        } finally {
            renderer.release()
            if (!album.isRecycled) album.recycle()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun readPixels(): ByteArray {
        val buffer = ByteBuffer.allocateDirect(Size * Size * 4)
        GLES30.glReadPixels(0, 0, Size, Size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        assertEquals("Rendering must not produce GL errors", GLES30.GL_NO_ERROR, GLES30.glGetError())
        return ByteArray(buffer.capacity()).also { buffer.get(it) }
    }

    private companion object {
        const val Size = 64
    }
}
