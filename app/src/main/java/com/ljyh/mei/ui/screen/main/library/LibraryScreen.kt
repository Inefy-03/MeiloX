package com.ljyh.mei.ui.screen.main.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.R
import androidx.compose.ui.graphics.Color
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.constants.LibraryStyleKey
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.screen.main.library.component.LibraryMobileLayout
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.utils.rememberPreference

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
) {
    val libraryStyle by rememberEnumPreference(LibraryStyleKey, LibraryStyle.Default)
    if (category == null && libraryStyle == LibraryStyle.AppleMusic) {
        LibraryCategoryList(isNavigationTab)
        return
    }
    val navController = LocalNavController.current
    val playlists by viewModel.playlists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val accountId by viewModel.accountId.collectAsState()
    val likedSongs by viewModel.likedSongs.collectAsState()
    val likedSongsLoading by viewModel.likedSongsLoading.collectAsState()
    val likedSongsError by viewModel.likedSongsError.collectAsState()
    val userId by rememberPreference(UserIdKey, "")
    val cookie by rememberPreference(CookieKey, "")
    var selectedPage by rememberSaveable { mutableStateOf(LibraryPage.Songs) }
    val mergeAlbumPodcasts = libraryStyle == LibraryStyle.Default && category == null
    val displayedPage = category ?: if (mergeAlbumPodcasts && selectedPage == LibraryPage.Podcasts) {
        LibraryPage.Albums
    } else selectedPage
    val accountMatches = accountId == userId && cookie.isNotBlank()
    val (createdPlaylists, collectedPlaylists) = remember(playlists.items, accountMatches, userId) {
        if (accountMatches) playlists.items.partition { it.author == userId }
        else emptyList<com.ljyh.mei.data.model.room.Playlist>() to emptyList()
    }

    if (userId.isNotBlank() && cookie.isNotBlank()) {
        LibraryMobileLayout(
            userPhoto = "",
            isNavigationTab = isNavigationTab,
            selectedPage = displayedPage,
            isCategoryPage = category != null,
            mergeAlbumPodcasts = mergeAlbumPodcasts,
            onPageSelect = { selectedPage = it },
            createdPlaylists = createdPlaylists,
            collectedPlaylists = collectedPlaylists,
            playlistState = if (accountMatches) playlists else LibraryCollectionState(),
            albumState = if (accountMatches) albums else LibraryCollectionState(),
            onRefreshPlaylists = viewModel::refreshPlaylists,
            onLoadMorePlaylists = viewModel::loadMorePlaylists,
            onRefreshAlbums = viewModel::refreshAlbums,
            onLoadMoreAlbums = viewModel::loadMoreAlbums,
            onPlaylistClick = { id -> Screen.PlayList.navigate(navController) { addPath(id) } },
            onAlbumClick = { id -> Screen.Album.navigate(navController) { addPath(id) } },
            userId = if (accountMatches) userId else "",
            likedSongs = if (accountMatches) likedSongs else emptyList(),
            likedSongsLoading = !accountMatches || likedSongsLoading,
            likedSongsError = if (accountMatches) likedSongsError else null,
            onRetryLikedSongs = viewModel::retryLikedSongs,
        )
    } else {
        EmptyLoginState(navController, isNavigationTab, category)
    }
}

@Composable
fun EmptyLoginState(
    navController: MeiNavigator,
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
) {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(category?.titleRes ?: R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) {
        item {
            Box(
                modifier = Modifier.fillParentMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                com.ljyh.mei.ui.glass.GlassCard(
                    modifier = Modifier.padding(24.dp),
                    onClick = { Screen.NeteaseLogin.navigate(navController) },
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.ljyh.mei.ui.glass.SfIcon("person.crop.circle", null, size = 42.dp)
                        Text(
                            stringResource(com.ljyh.mei.R.string.library_sign_in),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCategoryList(isNavigationTab: Boolean) {
    val podcastsEnabled by rememberPreference(PodcastsEnabledKey, true)
    val categories = LibraryPage.entries.filter { it != LibraryPage.Podcasts || podcastsEnabled }
    val navController = LocalNavController.current
    val colors = LocalGlassColors.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        horizontalContentPadding = 6.dp,
        largeTitleHorizontalPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        backgroundColor = if (colors.isDark) colors.groupedBackground else Color.White,
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = { if (isNavigationTab) GlobalProfileAvatarButton() },
    ) {
        categories.forEachIndexed { index, page ->
            item(key = "library-category:${page.name}") {
                IosListRow(
                    title = stringResource(page.titleRes),
                    leading = { SfIcon(page.symbol, null, tint = colors.accent) },
                    showTopSeparator = index > 0,
                    onClick = {
                        Screen.LibraryCategory.navigate(navController) { addPath(page.name) }
                    },
                )
            }
        }
    }
}
