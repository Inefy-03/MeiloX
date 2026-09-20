package com.ljyh.mei.ui.component.sheet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.component.player.LocalPlayerBackdropFrame
import com.ljyh.mei.ui.glass.GlassSurface
import com.ljyh.mei.ui.glass.GlassPressHighlight
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import kotlin.math.roundToInt

internal val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetLayers?> { null }

@Stable
internal class PlayerSheetLayers(
    val state: BottomSheetState,
    val miniHost: GraphicsLayer,
    val miniContent: GraphicsLayer,
    val fullContent: GraphicsLayer,
    val artwork: GraphicsLayer,
) {
    var hostBounds by mutableStateOf(Rect.Zero)
        private set
    var miniBounds by mutableStateOf(Rect.Zero)
    var miniContentBounds by mutableStateOf(Rect.Zero)
    var miniArtworkBounds by mutableStateOf(Rect.Zero)
    var fullArtworkBounds by mutableStateOf(Rect.Zero)
    var artworkCornerRadius by mutableStateOf(12.dp)
    var sharedArtworkEnabled by mutableStateOf(true)
    var circularArtwork by mutableStateOf(false)
    var artworkRecorded by mutableStateOf(false)
    var artworkOverlayMounted by mutableStateOf(false)
    private var miniVisualBounds = Rect.Zero
    private var frozenMiniContent: Rect? = null
    private var frozenMiniArtwork: Rect? = null
    private var frozenContainer: Rect? = null
    private var defaultContainer: Rect? = null
    private val pressRelease = PlayerPressRelease()
    private val pressFraction get() = pressRelease.fraction(state.progress)

    private fun releasePress(rect: Rect): Rect {
        val visual = frozenContainer ?: return rect
        val layout = defaultContainer ?: return rect
        return playerContainerRect(playerUntransformRect(rect, visual, layout), rect, pressFraction)
    }

    private var miniPressHighlight = GlassPressHighlight()
    val transitionPressHighlight get() = miniPressHighlight.copy(
        progress = miniPressHighlight.progress * pressFraction * playerPressHighlightAlpha(state.progress),
    )

    fun updateMiniVisualBounds(bounds: Rect, highlight: GlassPressHighlight) {
        if (!state.isTransitioning) {
            miniVisualBounds = bounds
            miniPressHighlight = highlight
        }
    }

    var miniContentCoordinates: LayoutCoordinates? = null
    var miniArtworkCoordinates: LayoutCoordinates? = null
    private var frozenMiniScale = Offset(1f, 1f)
    val sourceMiniContent get() = releasePress(frozenMiniContent ?: miniContentCoordinates.visualBounds() ?: miniContentBounds)
    val miniScaleX get() = mix(1f, frozenMiniScale.x, pressFraction)
    val miniScaleY get() = mix(1f, frozenMiniScale.y, pressFraction)
    private var frozenArtwork: Pair<Rect, Rect>? = null

    val sourceContainer get() = frozenContainer?.let { playerContainerRect(defaultContainer ?: it, it, pressFraction) } ?: if (miniVisualBounds.isUsable()) {
        miniVisualBounds.translate(miniBounds.topLeft)
    } else miniBounds
    val sourceArtwork get() = releasePress(frozenArtwork?.first ?: frozenMiniArtwork ?: miniArtworkCoordinates.visualBounds() ?: miniArtworkBounds)
    val targetArtwork get() = frozenArtwork?.second ?: fullArtworkBounds
    val drawsArtworkOverlay get() = artworkOverlayMounted && canDrawArtworkOverlay
    val canDrawArtworkOverlay get() = sharedArtworkEnabled && state.isTransitioning && artworkRecorded &&
        sourceArtwork.isUsable() && targetArtwork.isUsable() && hostBounds.isUsable()

    fun updateHost(bounds: Rect) {
        if (bounds == hostBounds) return
        hostBounds = bounds
        frozenContainer = null
        frozenArtwork = null
    }

    fun updateFrozenBounds() {
        if (!state.isTransitioning) {
            if (state.isCollapsed) fullArtworkBounds = Rect.Zero
            if (state.isExpanded) {
                miniVisualBounds = Rect.Zero
                miniPressHighlight = GlassPressHighlight()
            }
            frozenContainer = null
            frozenArtwork = null
            frozenMiniContent = null
            frozenMiniArtwork = null
            defaultContainer = null
            pressRelease.reset()
        } else {
            pressRelease.update(state.progress)
            if (frozenContainer == null && miniBounds.isUsable()) {
                defaultContainer = miniBounds
                frozenMiniContent = miniContentCoordinates.visualBounds() ?: miniContentBounds
                frozenMiniArtwork = miniArtworkCoordinates.visualBounds() ?: miniArtworkBounds
                val contentSize = miniContentCoordinates?.size
                frozenMiniScale = Offset(
                    frozenMiniContent!!.width / (contentSize?.width ?: miniContent.size.width).coerceAtLeast(1),
                    frozenMiniContent!!.height / (contentSize?.height ?: miniContent.size.height).coerceAtLeast(1),
                )
                frozenContainer = if (miniVisualBounds.isUsable()) {
                    miniVisualBounds.translate(miniBounds.topLeft)
                } else miniBounds
            }
            if (frozenArtwork == null && miniArtworkBounds.isUsable() && fullArtworkBounds.isUsable()) {
                frozenArtwork = (frozenMiniArtwork ?: miniArtworkBounds) to fullArtworkBounds
            }
        }
    }
}

/** Root-space child bounds already contain the Backdrop graphics-layer transform. */
private fun LayoutCoordinates?.visualBounds(): Rect? {
    if (this == null || !isAttached) return null
    return Rect(localToRoot(Offset.Zero), localToRoot(Offset(size.width.toFloat(), size.height.toFloat())))
}

@Composable
internal fun rememberPlayerSheetLayers(state: BottomSheetState): PlayerSheetLayers {
    val miniHost = rememberGraphicsLayer()
    val mini = rememberGraphicsLayer()
    val full = rememberGraphicsLayer()
    val artwork = rememberGraphicsLayer()
    return remember(state, miniHost, mini, full, artwork) {
        PlayerSheetLayers(state, miniHost, mini, full, artwork)
    }
}

/** Always draw live at rest. Recording is only a source for the shared transition. */
internal fun Modifier.recordPlayerContent(
    layer: GraphicsLayer,
    capture: () -> Boolean,
    drawInPlace: () -> Boolean,
): Modifier = drawWithContent {
    if (capture()) layer.record { this@drawWithContent.drawContent() }
    if (drawInPlace()) drawContent()
}

@Composable
internal fun Modifier.playerArtwork(
    cornerRadius: Dp = 12.dp,
    circle: Boolean = false,
    shared: Boolean = true,
): Modifier {
    val layers = LocalPlayerSheet.current ?: return this
    SideEffect {
        layers.sharedArtworkEnabled = shared
        layers.artworkCornerRadius = cornerRadius
        layers.circularArtwork = circle
    }
    DisposableEffect(layers) {
        onDispose {
            layers.fullArtworkBounds = Rect.Zero
            layers.artworkRecorded = false
        }
    }
    return onGloballyPositioned {
        // Preserve the full size and offscreen position of a paged-out cover.
        layers.fullArtworkBounds = it.visualBounds() ?: Rect.Zero
    }
        .drawWithContent {
            if (layers.state.isTransitioning) {
                layers.artwork.record { this@drawWithContent.drawContent() }
                if (!layers.artworkRecorded) layers.artworkRecorded = true
            }
            // Shared artwork only appears at its measured overlay position during motion.
            if (!layers.state.isTransitioning || !shared) drawContent()
        }
}

private fun Modifier.sheetGestures(
    state: BottomSheetState,
    onHorizontalSwipe: ((HorizontalSwipeDirection) -> Unit)?,
): Modifier = pointerInput(state, onHorizontalSwipe) {
    if (onHorizontalSwipe != null) {
        val tracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onHorizontalDrag = { change, _ -> tracker.addPointerInputChange(change) },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().x
                if (velocity > 500f) onHorizontalSwipe(HorizontalSwipeDirection.Right)
                if (velocity < -500f) onHorizontalSwipe(HorizontalSwipeDirection.Left)
            },
        )
    }
}.pointerInput(state) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            state.beginDrag()
        },
        onVerticalDrag = { change, amount ->
            tracker.addPointerInputChange(change)
            state.dispatchRawDelta(amount)
        },
        onDragCancel = state::cancelDrag,
        onDragEnd = { state.performFling(-tracker.calculateVelocity().y, null) },
    )
}

/** Consume new touches on recording hosts before their controls receive them. */
private fun Modifier.blockDescendantInput(block: Boolean): Modifier = if (!block) this else
    clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    collapsedDragOffset: () -> Dp = { 0.dp },
    collapsedDragHeight: Dp = 0.dp,
    transitionBackdrop: Backdrop,
    onDismiss: (() -> Unit)? = null,
    onHorizontalSwipe: ((HorizontalSwipeDirection) -> Unit)? = null,
    backgroundContent: @Composable BoxScope.() -> Unit = {},
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val layers = checkNotNull(LocalPlayerSheet.current)
    val frame = LocalPlayerBackdropFrame.current
    val density = LocalDensity.current
    val active = state.isTransitioning
    val expandedCornerRadius = rememberPlayerScreenCornerRadius(layers.hostBounds)
    SideEffect { layers.updateFrozenBounds() }
    BackHandler(enabled = !state.isCollapsed && !state.isDismissed, onBack = state::collapseSoft)

    Box(modifier.fillMaxSize().onGloballyPositioned { layers.updateHost(it.boundsInRoot()) }) {
        if (!state.isDismissed || onDismiss == null) {
            Box(
                Modifier.fillMaxWidth()
                    .height(if (collapsedDragHeight > 0.dp) collapsedDragHeight else state.collapsedBound)
                    .offset {
                        val anchor = if (state.progress == 0f && !state.isDragging) state.value else state.collapsedBound
                        IntOffset(0, (state.expandedBound - anchor + collapsedDragOffset()).roundToPx())
                    }
                    .sheetGestures(state, onHorizontalSwipe)
                    .then(if (active || state.isExpanded) Modifier.clearAndSetSemantics { } else Modifier)
                    .recordPlayerContent(layers.miniHost, { state.isTransitioning }) { !state.isTransitioning && !state.isExpanded },
                content = collapsedContent,
            )
        }
        run {
            val visible = active || state.isExpanded
            // Keep pager/lyrics composition, but unplace the hidden host so it has no hit targets.
            Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (visible) placeable.place(0, 0)
                }
            }) {
                if (visible) {
                    // Never include a native Surface in a Compose layer recording.
                    Box(Modifier.fillMaxSize().blockDescendantInput(!state.isExpanded)
                        .background(if (state.isExpanded) backgroundColor else Color.Transparent)) {
                        backgroundContent()
                    }
                }
                BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .sheetGestures(state, onHorizontalSwipe)
                        .then(if (!state.isExpanded) Modifier.clearAndSetSemantics { } else Modifier)
                        .recordPlayerContent(layers.fullContent, { state.isTransitioning }) { state.isExpanded },
                    content = content,
                )
            }
        }
        if (active && layers.hostBounds.isUsable() && layers.sourceContainer.isUsable()) {
            val globalBounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, state.progress)
            val bounds = globalBounds.translate(-layers.hostBounds.topLeft)
            val radius = playerContainerCornerRadius(
                layers.sourceContainer.height / 2f, expandedCornerRadius, state.progress,
            )
            val shellShape = ContinuousRoundedRectangle(with(density) { radius.toDp() })
            Box(
                Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxSize(), backdrop = transitionBackdrop,
                    shape = shellShape, style = GlassSurfaceStyle.Navigation,
                    pressHighlight = layers.transitionPressHighlight,
                ) { }
            }
            Canvas(
                Modifier.fillMaxSize(),
            ) {
                val p = state.progress
                val outline = shellShape.createOutline(bounds.size, layoutDirection, this)
                val path = Path().apply {
                    when (outline) {
                        is Outline.Rectangle -> addRect(outline.rect)
                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                        is Outline.Generic -> addPath(outline.path)
                    }
                    translate(bounds.topLeft)
                }
                clipPath(path) {
                    // Cover live glass once; a second color mask would erase its refraction early.
                    val scale = bounds.width / size.width.coerceAtLeast(1f)
                    val image = frame?.value
                    if (image == null) {
                        drawRect(backgroundColor, bounds.topLeft, bounds.size, alpha = playerBackgroundAlpha(p))
                    } else {
                        withTransform({
                            translate(bounds.left, bounds.top)
                            scale(scale, scale, Offset.Zero)
                        }) {
                            drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                alpha = playerBackgroundAlpha(p))
                        }
                    }
                    if (layers.miniContent.size.width > 0) {
                        layers.miniContent.alpha = playerMiniContentAlpha(p)
                        val source = layers.sourceContainer
                        val offset = Offset(
                            layers.sourceMiniContent.left - layers.hostBounds.left,
                            layers.sourceMiniContent.top - source.top + bounds.top,
                        )
                        withTransform({
                            translate(offset.x + globalBounds.left - source.left, offset.y)
                            scale(layers.miniScaleX, layers.miniScaleY, Offset.Zero)
                        }) { drawLayer(layers.miniContent) }
                    }

                    if (layers.fullContent.size.width > 0) {
                        layers.fullContent.alpha = playerContentAlpha(p)
                        withTransform({
                            translate(bounds.left, bounds.top)
                            scale(scale, scale, Offset.Zero)
                        }) { drawLayer(layers.fullContent) }
                    }
                }
            }
        }
        if (active) {
            // Input ownership never depends on frame/artwork readiness. Keep one top hit target.
            Box(Modifier.fillMaxSize().sheetGestures(state, null).clearAndSetSemantics { })
        }
    }
}

@Composable
internal fun PlayerSheetArtworkOverlay(layers: PlayerSheetLayers) {
    if (!layers.canDrawArtworkOverlay) return
    DisposableEffect(layers) {
        layers.artworkOverlayMounted = true
        onDispose { layers.artworkOverlayMounted = false }
    }
    val density = LocalDensity.current
    val progress = layers.state.progress
    val bounds = playerArtworkRect(layers.sourceArtwork, layers.targetArtwork, progress)
        .translate(-layers.hostBounds.topLeft)
    val targetRadius = if (layers.circularArtwork) layers.targetArtwork.width / 2 else
        with(density) { layers.artworkCornerRadius.toPx() } *
            (layers.targetArtwork.width / layers.artwork.size.width.coerceAtLeast(1))
    val radius = mix(with(density) { com.ljyh.mei.constants.ThumbnailCornerRadius.toPx() }, targetRadius, progress)
    val target = layers.targetArtwork.translate(-layers.hostBounds.topLeft)
    val scale = bounds.width / target.width.coerceAtLeast(1f)
    Box(
        Modifier.offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
            .size(with(density) { target.width.toDp() }, with(density) { target.height.toDp() })
            .clearAndSetSemantics { }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = bounds.height / target.height.coerceAtLeast(1f)
                translationX = bounds.left - target.left
                translationY = bounds.top - target.top
                shape = ContinuousRoundedRectangle(with(density) { (radius / scale.coerceAtLeast(0.001f)).toDp() })
                clip = true
                shadowElevation = 16.dp.toPx() * progress
            }
            .drawWithContent {
                layers.artwork.alpha = 1f
                withTransform({
                    val recordingScale = target.width / layers.artwork.size.width.coerceAtLeast(1)
                    scale(recordingScale, recordingScale, Offset.Zero)
                }) { drawLayer(layers.artwork) }
            },
    )
}

@Composable
private fun rememberPlayerScreenCornerRadius(hostBounds: Rect): Float {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    return remember(context, view, hostBounds, configuration.screenWidthDp, configuration.screenHeightDp) {
        val activity = context.playerActivity()
        if (activity == null || activity.isInMultiWindowMode || activity.isInPictureInPictureMode) {
            0f
        } else {
            val current = activity.windowManager.currentWindowMetrics.bounds
            val maximum = activity.windowManager.maximumWindowMetrics.bounds
            if (current.width() < maximum.width() || current.height() < maximum.height()) {
                0f
            } else {
                val insets = view.rootWindowInsets
                listOf(
                    RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
                ).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull()?.toFloat() ?: 0f
            }
        }
    }
}

private tailrec fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}
