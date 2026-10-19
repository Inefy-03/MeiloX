package com.ljyh.mei.ui.component.player.component.applemusic

import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import com.ljyh.mei.ui.component.player.LocalPlayerArtwork
import com.ljyh.mei.ui.component.sheet.playerArtwork
import com.ljyh.mei.ui.component.sheet.PlayerArtworkGeometry
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.constants.MiniPlayerHeight
import com.ljyh.mei.constants.PlayerHorizontalPadding
import com.ljyh.mei.constants.ThumbnailCornerRadius
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.component.LyricScreen
import com.ljyh.mei.ui.component.player.component.PlayerControlsSection
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.sheet.HorizontalSwipeDirection
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.trackBackdropPosition
import com.ljyh.mei.ui.model.LyricSource
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun AppleMusicPlayer(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler,
    collapsedBackdrop: Backdrop,
    playerBackgroundBackdrop: LayerBackdrop,
    playerContentBackdrop: LayerBackdrop,
    playerCoverBackdrop: LayerBackdrop,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val isDark = LocalGlassColors.current.isDark
    val configuration = LocalConfiguration.current

    // --- Apple Music 特定状态 ---
    var showLyrics by remember { mutableStateOf(false) }
    var playerBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    // --- 从状态容器获取数据 ---
    val sharedArtwork = LocalPlayerArtwork.current
    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    val playbackState by stateContainer.playbackState
    val isDragging by remember { derivedStateOf { stateContainer.isDragging } }
    val lyricLine by remember { derivedStateOf { stateContainer.lyricLine } }
    val isLiked by stateContainer.isLiked
    val sheetExpanded by remember(state) { derivedStateOf { state.isExpanded } }

    // --- Apple Music 特定的 LaunchedEffect ---
    BackHandler(enabled = sheetExpanded && showLyrics) {
        showLyrics = false
    }


    // --- Animation & Geometry Calculation ---
    val lyricAnimFraction = remember { Animatable(0f) }
    LaunchedEffect(showLyrics, sheetExpanded) {
        // Keep the measured sheet endpoint stable while its shared artwork owns motion.
        if (sheetExpanded) {
            lyricAnimFraction.animateTo(
                if (showLyrics) 1f else 0f,
                appleMusicLyricSpring(),
            )
        }
    }

    val albumInfoAlpha = remember { derivedStateOf { appleMusicAlbumInfoAlpha(lyricAnimFraction.value) } }
    val headerInfoAlpha = remember { derivedStateOf { appleMusicHeaderInfoAlpha(lyricAnimFraction.value) } }
    val albumInfoVisible by remember { derivedStateOf { albumInfoAlpha.value > 0f } }
    val headerInfoVisible by remember { derivedStateOf { headerInfoAlpha.value > 0f } }

    // Keep the same default fade spring as AnimatedVisibility without disposing lyrics.
    val lyricVisibilityAlpha = animateFloatAsState(
        targetValue = if (showLyrics) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "LyricsVisibility",
    )

    val animatedPlaybackCoverScale = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.9f,
        animationSpec = if (sheetExpanded) spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ) else androidx.compose.animation.core.snap(),
        label = "AppleMusicCoverScale"
    )


    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                if (playerBoundsInRoot != bounds) playerBoundsInRoot = bounds
            },
    ) {
        val screenWidth = maxWidth
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        // --- 响应式布局判断 ---
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isCompactHeight = maxHeightPx < with(density) { 600.dp.toPx() }

        // --- 1. 定义关键尺寸参数 ---

        // B. Normal Expanded
        val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
        val topSafeArea = (statusBarTop - (playerBoundsInRoot?.top ?: 0f)).coerceAtLeast(0f)

        val bottomControlsHeightDp = if (isCompactHeight || isLandscape) 220.dp else 300.dp
        val bottomControlsHeight = with(density) { bottomControlsHeightDp.toPx() }
        var albumMoreAnchor by remember(screenWidth, maxHeightPx, isLandscape, isCompactHeight) {
            mutableStateOf<Offset?>(null)
        }
        val controlsSpacerHeight = animateDpAsState(
            targetValue = if (stateContainer.controlsVisible) bottomControlsHeightDp else 16.dp,
            label = "spacer",
        )
        val sourceBadgeAnchorProgress = remember(controlsSpacerHeight, bottomControlsHeightDp) {
            {
                ((controlsSpacerHeight.value - 16.dp) / (bottomControlsHeightDp - 16.dp))
                    .coerceIn(0f, 1f)
            }
        }

        // --- 动态计算封面大小 ---
        val normalPaddingH = with(density) { (PlayerHorizontalPadding + 24.dp).toPx() }
        val maxAvailableWidth = maxWidthPx - (normalPaddingH * 2)

        val minTopMargin = with(density) { 16.dp.toPx() }
        val availableVerticalSpace = maxHeightPx - bottomControlsHeight - topSafeArea - minTopMargin

        val normalSize = min(maxAvailableWidth, availableVerticalSpace.coerceAtLeast(0f)).coerceAtLeast(1f)

        val realAvailableHeight = (maxHeightPx - bottomControlsHeight - topSafeArea)
        val verticalBias = (realAvailableHeight - normalSize) / 2
        val normalTop = topSafeArea + verticalBias.coerceAtLeast(with(density) { 12.dp.toPx() })

        val normalStart = (maxWidthPx - normalSize) / 2

        // C. Header (Top Left Small)
        val headerSize = with(density) { 46.dp.toPx() }
        val headerTop = topSafeArea + with(density) { 40.dp.toPx() }
        val headerStart = with(density) { PlayerHorizontalPadding.toPx() }
        val headerRadius = with(density) { 4.dp.toPx() }

        val normalBounds = Rect(normalStart, normalTop, normalStart + normalSize, normalTop + normalSize)
        val headerBounds = Rect(headerStart, headerTop, headerStart + headerSize, headerTop + headerSize)
        val normalRadius = with(density) { 12.dp.toPx() }
        val normalShadow = with(density) { 16.dp.toPx() }
        // Keep image measurement stable; only layer properties observe animation frames.
        val artworkLayoutSize = normalSize.roundToInt().coerceAtLeast(1)
        val artworkFrame = remember(normalBounds, headerBounds, density, sheetExpanded, isPlaying) {
            derivedStateOf {
                appleMusicArtworkFrame(
                    normalBounds, headerBounds, normalRadius, headerRadius, normalShadow,
                    if (sheetExpanded) animatedPlaybackCoverScale.value else if (isPlaying) 1f else 0.9f,
                    lyricAnimFraction.value,
                )
            }
        }

        val coverUrl = mediaMetadata?.coverUrl
        // --- 3. UI Structure ---
        BottomSheet(
            state = state,
            modifier = Modifier.fillMaxSize(),
            collapsedDragOffset = miniPlayerVerticalOffset,
            collapsedDragHeight = MiniPlayerHeight,
            transitionBackdrop = collapsedBackdrop,
            onDismiss = {
                stateContainer.playerConnection.player.stop()
                stateContainer.playerConnection.player.clearMediaItems()
            },
            onHorizontalSwipe = { direction ->
                if (!sheetExpanded) {
                    when (direction) {
                        HorizontalSwipeDirection.Left -> stateContainer.playerConnection.seekToNext()
                        HorizontalSwipeDirection.Right -> stateContainer.playerConnection.seekToPrevious()
                    }
                }
            },
            backgroundContent = {
                FluidBackground(
                    imageUrl = coverUrl,
                    beatMeter = stateContainer.playerConnection.service.beatMeter,
                    isPlaying = isPlaying,
                    alpha = 1f,
                    backdrop = playerBackgroundBackdrop,
                )
            },
            collapsedContent = {
                MiniPlayer(
                    // The mini player has no progress indicator, so it does not
                    // need to subscribe to the expanded player's position clock.
                    position = 0L,
                    duration = 0L,
                    backdrop = collapsedBackdrop,
                    compactProgress = compactMiniPlayerProgress,
                    onClick = state::expandSoft,
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .layerBackdrop(playerContentBackdrop)
                    .trackBackdropPosition(playerContentBackdrop),
            ) {

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { topSafeArea.toDp() } + 7.dp)
                        .size(width = 58.dp, height = 4.dp)
                        .background(
                            Color.White.copy(alpha = if (isDark) 0.48f else 0.62f),
                            Capsule(),
                        ),
                )


                // Mode B: Lyric Player
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                // Premeasure the resident host, but remove hidden input targets.
                                if (showLyrics || lyricVisibilityAlpha.value > 0f) placeable.place(0, 0)
                            }
                        }
                        .playerLyricsFade(lyricVisibilityAlpha)
                        .then(if (showLyrics && sheetExpanded) Modifier else Modifier.clearAndSetSemantics { }),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(with(density) { (headerTop + headerSize).toDp() + 16.dp }))

                        LyricScreen(
                            lyricData = lyricLine,
                            active = showLyrics,
                            // The header ends 12.dp from the edge, with 10.dp inside Title.
                            sourceBadgeEndOffset = PlayerHorizontalPadding - 22.dp,
                            sourceBadgeAnchor = albumMoreAnchor,
                            sourceBadgeAnchorProgress = sourceBadgeAnchorProgress,
                            playerConnection = stateContainer.playerConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = PlayerHorizontalPadding),
                            onClick = {
                                mediaMetadata?.let {
                                    if (overlayHandler.currentOverlayValue is OverlayState.None) {
                                        stateContainer.playerViewModel.searchQQSong(it.title)
                                        overlayHandler.showQQMusicSelection(
                                            mediaMetadata = it
                                        )
                                    }
                                }
                            },
                            onLongClick = { source ->
                                if (source == LyricSource.QQMusic && mediaMetadata != null) {
                                    stateContainer.playerViewModel.deleteSongById(id = mediaMetadata!!.id.toString())
                                    android.widget.Toast.makeText(context, "已删除QQ音乐歌词", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            controlsVisible = stateContainer.controlsVisible,
                            onToggleControls = { stateContainer.controlsVisible = it },
                        )
                        Spacer(modifier = Modifier.layout { measurable, constraints ->
                            val height = constraints.constrainHeight(controlsSpacerHeight.value.roundToPx())
                            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        })
                    }
                }


                // Mode C: Header Info
                if (mediaMetadata != null) {
                    val headerTextWidth =
                        screenWidth - with(density) { (headerStart + headerSize).toDp() } - 24.dp

                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(density) { (headerStart + headerSize).toDp() } + 12.dp,
                                y = with(density) { headerTop.toDp() },
                            )
                            .height(with(density) { headerSize.toDp() })
                            .width(headerTextWidth)
                            .playerInfoFade(headerInfoAlpha, headerInfoVisible),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Title(
                            title = mediaMetadata!!.title,
                            subTitle = mediaMetadata!!.artists.joinToString { it.name },
                            isLiked = isLiked,
                            onLikeClick = { mediaMetadata?.let { stateContainer.playerViewModel.like(it.id.toString()) } },
                            onMoreClick = { overlayHandler.showMoreAction() },
                            onTitleClick = {
                                mediaMetadata?.let {
                                    overlayHandler.showAlbumArtist(
                                        album = it.album,
                                        artists = it.artists,
                                        cover = it.coverUrl
                                    )
                                }
                            },
                            titleStyle = MaterialTheme.typography.titleMedium,
                            subTitleStyle = MaterialTheme.typography.bodySmall,
                            needShadow = false,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                // --- 统一的底部控制区域 ---
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                ) {
                    val isBottomBarVisible = !showLyrics || stateContainer.controlsVisible

                    AnimatedVisibility(
                        visible = isBottomBarVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        Column(Modifier.fillMaxWidth()) {

                            if (!isCompactHeight && !isLandscape) {
                                mediaMetadata?.let {
                                    Title(
                                        title = it.title,
                                        subTitle = it.artists.joinToString { artist -> artist.name },
                                        isLiked = isLiked,
                                        onLikeClick = { stateContainer.playerViewModel.like(it.id.toString()) },
                                        onMoreClick = { overlayHandler.showMoreAction() },
                                        onMoreButtonPositioned = {
                                            if (!showLyrics) albumMoreAnchor = it.positionInRoot()
                                        },
                                        onTitleClick = {
                                            overlayHandler.showAlbumArtist(
                                                album = it.album,
                                                artists = it.artists,
                                                cover = it.coverUrl
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .playerInfoFade(albumInfoAlpha, albumInfoVisible)
                                            .padding(horizontal = PlayerHorizontalPadding)
                                            .padding(bottom = 12.dp)
                                            .padding(start = 8.dp)
                                    )
                                }
                            }

                            PlayerControlsSection(
                                position = { stateContainer.sliderPosition.toLong() },
                                duration = { stateContainer.duration },
                                isPlaying = isPlaying,
                                playbackState = playbackState,
                                playerConnection = stateContainer.playerConnection,
                                onLyricClick = { showLyrics = !showLyrics },
                                onPlaylistClick = { overlayHandler.showPlaylist() },
                                onSleepTimerClick = { overlayHandler.showSleepTimer() },
                                onAddToPlaylistClick = {
                                    mediaMetadata?.let {
                                        overlayHandler.showAddToPlaylist(it.id)
                                    }
                                },
                                onDownloadClick = {
                                    overlayHandler.handleMoreAction(com.ljyh.mei.ui.model.MoreAction.DOWNLOAD)
                                },
                                onMoreClick = { overlayHandler.showMoreAction() },
                                isCompact = isCompactHeight || isLandscape
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .layerBackdrop(playerCoverBackdrop)
                    .trackBackdropPosition(playerCoverBackdrop),
            ) {
                AnimatedContent(
                    targetState = mediaMetadata,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(durationMillis = 400)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 400)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(durationMillis = 400))
                            )
                    },
                    label = "CoverTransition",
                    modifier = Modifier
                        .graphicsLayer {
                            val frame = artworkFrame.value
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = frame.bounds.left
                            translationY = frame.bounds.top
                            scaleX = frame.bounds.width / size.width.coerceAtLeast(1f)
                            scaleY = frame.bounds.height / size.height.coerceAtLeast(1f)
                        }
                        .size(with(density) { artworkLayoutSize.toDp() })
                        .playerArtwork(
                            geometry = {
                                PlayerArtworkGeometry(
                                    artworkFrame.value.bounds,
                                    IntSize(artworkLayoutSize, artworkLayoutSize),
                                )
                            },
                            cornerRadius = {
                                val frame = artworkFrame.value
                                with(density) { (frame.cornerRadius * artworkLayoutSize / frame.bounds.width.coerceAtLeast(1f)).toDp() }
                            },
                            shadowElevation = {
                                val frame = artworkFrame.value
                                with(density) { (frame.shadowElevation * artworkLayoutSize / frame.bounds.width.coerceAtLeast(1f)).toDp() }
                            },
                            trackContainerTop = { showLyrics || lyricAnimFraction.value > 0f },
                        )
                        .graphicsLayer {
                            val frame = artworkFrame.value
                            val scale = frame.bounds.width / size.width.coerceAtLeast(1f)
                            shadowElevation = frame.shadowElevation / scale.coerceAtLeast(0.001f)
                            shape = ContinuousRoundedRectangle(frame.cornerRadius / scale.coerceAtLeast(0.001f))
                            clip = true
                        }
                        .then(
                            if (sheetExpanded) Modifier.clickable { showLyrics = !showLyrics }
                            else Modifier.clearAndSetSemantics { },
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) { currentMetadata ->
                    if (currentMetadata != null) {
                        val retainedPainter = remember(currentMetadata.coverUrl) {
                            sharedArtwork?.takeIf { it.first == currentMetadata.coverUrl }?.second
                        }
                        val painter = sharedArtwork?.takeIf { it.first == currentMetadata.coverUrl }?.second ?: retainedPainter
                        if (painter != null) Image(
                            painter = painter,
                            contentDescription = "Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
            }
        }
    }
}

internal fun Modifier.playerLyricsFade(alpha: State<Float>): Modifier = graphicsLayer {
    this.alpha = alpha.value
    // Keep descendant Plus blending connected to the background during the fade.
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** Retain text measurement while transparent groups have neither layout height nor input. */
private fun Modifier.playerInfoFade(alpha: State<Float>, visible: Boolean): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, if (visible) placeable.height else 0) {
            if (visible) placeable.place(0, 0)
        }
    }
        .graphicsLayer { this.alpha = alpha.value }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
