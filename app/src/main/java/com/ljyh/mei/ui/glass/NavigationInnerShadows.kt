package com.ljyh.mei.ui.glass

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.RuntimeXfermode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/** GPU-only inner shadows above the glass background, below its highlights and foreground. */
internal class NavigationInnerShadowRenderer {
    private val narrowMask = RenderNode("Navigation narrow inner shadow")
    private val wideMask = RenderNode("Navigation wide inner shadow")
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.CLEAR }
    private val plusPaint = Paint().apply {
        blendMode = BlendMode.PLUS
        colorFilter = BlendModeColorFilter(Color.rgb(40, 40, 40), BlendMode.SRC_IN)
    }
    private val darkPlusPaint = Paint().apply {
        blendMode = BlendMode.PLUS
        colorFilter = BlendModeColorFilter(Color.rgb(26, 26, 26), BlendMode.SRC_IN)
    }
    private val linearBurnPaint = if (Build.VERSION.SDK_INT >= 36) createLinearBurnPaint() else null
    private val burnMask = if (Build.VERSION.SDK_INT < 36) {
        RenderNode("Navigation linear burn alpha").apply {
            setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(RuntimeShader(BurnAlphaShader), "mask"),
            )
        }
    } else null
    private val fallbackBurnPaint = Paint().apply { blendMode = BlendMode.COLOR_BURN }
    private val fallbackDimPaint = Paint().apply {
        colorFilter = BlendModeColorFilter(Color.BLACK, BlendMode.SRC_IN)
        alpha = 25 // 1 - 230/255, exactly the complement of #E6E6E6.
    }
    private var key: GeometryKey? = null
    private var outlinePath = android.graphics.Path()
    private var shadowHolePath = android.graphics.Path()
    private var padding = 0f

    fun draw(
        scope: DrawScope,
        shape: Shape,
        bounds: Rect = Rect(Offset.Zero, scope.size),
        isDark: Boolean = false,
    ) {
        val canvas = scope.drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated || bounds.isEmpty) return
        val nextKey = GeometryKey(shape, bounds.size, scope.layoutDirection, scope.density, scope.fontScale)
        if (key != nextKey) updateGeometry(scope, nextKey)
        val save = canvas.save()
        try {
            canvas.translate(bounds.left, bounds.top)
            canvas.clipPath(outlinePath)
            val offset = 40f * scope.density
            val plus = if (isDark) darkPlusPaint else plusPaint
            // Figma stacks effects bottom to top: wide burn, upper Plus, lower Plus.
            if (linearBurnPaint != null) {
                drawMask(canvas, wideMask, bounds.size, offset, linearBurnPaint)
            } else {
                // For the opaque sampled backdrop, t = 1-m*(1-S), a = m*S/t.
                // ColorBurn(S,a), then black SrcOver(m*(1-S)), equals
                // (1-m)*D + m*max(D+S-1,0), including the blurred alpha edges.
                drawMask(canvas, requireNotNull(burnMask), bounds.size, offset, fallbackBurnPaint)
                drawMask(canvas, wideMask, bounds.size, offset, fallbackDimPaint)
            }
            drawMask(canvas, narrowMask, bounds.size, -offset, plus)
            drawMask(canvas, narrowMask, bounds.size, offset, plus)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun updateGeometry(scope: DrawScope, nextKey: GeometryKey) {
        val outline = nextKey.shape.createOutline(nextKey.size, nextKey.layoutDirection, scope)
        outlinePath = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }.asAndroidPath()
        shadowHolePath = expandedShadowHole(40f * scope.density)
        // Figma exports blur 10/30 as Gaussian sigma 5/15. Android converts its
        // public radius to sigma = radius * 0.57735 + 0.5; invert that in pixels.
        val narrowSigma = 5f * scope.density
        val wideSigma = 15f * scope.density
        val narrowRadius = (narrowSigma - 0.5f) / 0.57735f
        val wideRadius = (wideSigma - 0.5f) / 0.57735f
        padding = ceil(40f * scope.density + 3f * wideSigma)
        val width = ceil(nextKey.size.width + padding * 2f).toInt()
        val height = ceil(nextKey.size.height + padding * 2f).toInt()
        narrowMask.setRenderEffect(RenderEffect.createBlurEffect(narrowRadius, narrowRadius, Shader.TileMode.CLAMP))
        wideMask.setRenderEffect(RenderEffect.createBlurEffect(wideRadius, wideRadius, Shader.TileMode.CLAMP))
        recordMask(narrowMask, width, height)
        recordMask(wideMask, width, height)
        burnMask?.let { node ->
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            try {
                recording.drawRenderNode(wideMask)
            } finally {
                node.endRecording()
            }
        }
        key = nextKey
    }

    private fun recordMask(node: RenderNode, width: Int, height: Int) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        try {
            recording.drawColor(Color.WHITE)
            recording.translate(padding, padding)
            recording.drawPath(shadowHolePath, clearPaint)
        } finally {
            node.endRecording()
        }
    }

    private fun expandedShadowHole(spread: Float): android.graphics.Path {
        // Figma exports spread -40 as a square-kernel dilation of the inner hole.
        // Navigation outlines have full-width/height center chords. Four translated
        // copies and two connecting strips preserve their original corner curves.
        // Increasing the shape size or using a round stroke changes those curves.
        val bounds = android.graphics.RectF()
        outlinePath.computeBounds(bounds, true)
        val expanded = android.graphics.Path()
        for (x in floatArrayOf(-spread, spread)) {
            for (y in floatArrayOf(-spread, spread)) {
                val corner = android.graphics.Path(outlinePath).apply { offset(x, y) }
                expanded.op(corner, android.graphics.Path.Op.UNION)
            }
        }
        val strips = android.graphics.Path().apply {
            addRect(
                bounds.centerX() - spread, bounds.top - spread,
                bounds.centerX() + spread, bounds.bottom + spread,
                android.graphics.Path.Direction.CW,
            )
            addRect(
                bounds.left - spread, bounds.centerY() - spread,
                bounds.right + spread, bounds.centerY() + spread,
                android.graphics.Path.Direction.CW,
            )
        }
        expanded.op(strips, android.graphics.Path.Op.UNION)
        return expanded
    }

    private fun drawMask(canvas: Canvas, node: RenderNode, size: Size, offset: Float, paint: Paint) {
        val save = canvas.saveLayer(0f, 0f, size.width, size.height, paint)
        try {
            canvas.translate(-padding, offset - padding)
            canvas.drawRenderNode(node)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        narrowMask.discardDisplayList()
        wideMask.discardDisplayList()
        burnMask?.discardDisplayList()
        key = null
    }

    private data class GeometryKey(
        val shape: Shape,
        val size: Size,
        val layoutDirection: LayoutDirection,
        val density: Float,
        val fontScale: Float,
    )

    private companion object {
        @RequiresApi(36)
        fun createLinearBurnPaint() = Paint().apply {
            colorFilter = BlendModeColorFilter(Color.rgb(230, 230, 230), BlendMode.SRC_IN)
            xfermode = RuntimeXfermode(LinearBurnShader)
        }

        // Premultiplied source-over linear burn, without division at zero alpha.
        const val LinearBurnShader = """
half4 main(half4 src, half4 dst) {
    half overlap = src.a * dst.a;
    half3 burn = max(half3(0.0), src.rgb * dst.a + dst.rgb * src.a - overlap);
    return half4(src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a) + burn,
        src.a + dst.a - overlap);
}
"""

        const val BurnAlphaShader = """
uniform shader mask;
half4 main(float2 position) {
    float m = clamp(mask.eval(position).a, 0.0, 1.0);
    float s = 230.0 / 255.0;
    float a = m * s / (1.0 - m + m * s);
    return half4(half3(s * a), a);
}
"""
    }
}
