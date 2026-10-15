package com.ljyh.mei.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeloXNavigationTest {
    @Test
    fun defaultLayoutMatchesMeloXNavigationDefaults() {
        val layout = NavigationLayout()

        assertEquals(
            listOf(AppTab.Recommended, AppTab.Music, AppTab.Podcasts),
            layout.normalizedHomeTabs,
        )
        assertEquals(listOf(LibraryPage.Cloud), layout.normalizedSeparatedPages)
        assertEquals(
            listOf(
                AppTab.Home,
                AppTab.Explore,
                AppTab.Library,
                AppTab.LibraryCloud,
                AppTab.Search,
            ),
            layout.visibleTabs,
        )
    }

    @Test
    fun disabledFeaturesRemoveDependentDestinations() {
        val enabled = setOf(ContentFeature.Downloads, ContentFeature.ListeningHistory)
        val layout = NavigationLayout(
            homeTabs = listOf(AppTab.Recommended, AppTab.Podcasts, AppTab.LibraryCloud),
            separatedLibraryPages = listOf(
                LibraryPage.Podcasts,
                LibraryPage.Cloud,
                LibraryPage.History,
            ),
            enabledFeatures = enabled,
        )

        assertFalse(AppTab.Podcasts in layout.normalizedHomeTabs)
        assertFalse(AppTab.LibraryCloud in layout.visibleTabs)
        assertFalse(AppTab.LibraryPodcasts in layout.visibleTabs)
        assertTrue(AppTab.LibraryHistory in layout.visibleTabs)
    }

    @Test
    fun albumAndPodcastPlacementsRemainIndependentlyConfigurable() {
        val layout = NavigationLayout(
            homeTabs = listOf(AppTab.Recommended, AppTab.LibraryAlbums),
            separatedLibraryPages = listOf(LibraryPage.Albums, LibraryPage.Podcasts),
        )

        assertTrue(AppTab.LibraryAlbums in AppTab.configurablePages)
        assertTrue(AppTab.LibraryPodcasts in AppTab.configurablePages)
        assertEquals(AppTab.LibraryAlbums, AppTab.fromLibraryPage(LibraryPage.Albums))
        assertEquals(AppTab.LibraryPodcasts, AppTab.fromLibraryPage(LibraryPage.Podcasts))
        assertEquals(listOf(LibraryPage.Albums, LibraryPage.Podcasts), layout.normalizedSeparatedPages)
        assertEquals(listOf(AppTab.Recommended, AppTab.LibraryAlbums), layout.normalizedHomeTabs)
        assertEquals(AppPagePlacement.Home, layout.placementOf(AppTab.LibraryAlbums))
        assertEquals(AppPagePlacement.TabBar, layout.placementOf(AppTab.LibraryPodcasts))
        assertFalse(AppTab.LibraryAlbums in layout.visibleTabs)
        assertTrue(AppTab.LibraryPodcasts in layout.visibleTabs)

        val embeddedPodcastLayout = layout.copy(separatedLibraryPages = listOf(LibraryPage.Albums))
        assertEquals(AppPagePlacement.Home, embeddedPodcastLayout.placementOf(AppTab.LibraryAlbums))
        assertEquals(AppPagePlacement.Library, embeddedPodcastLayout.placementOf(AppTab.LibraryPodcasts))
    }

    @Test
    fun placementsRespectHomeAndSeparatedLibraryPages() {
        val layout = NavigationLayout()

        assertEquals(AppPagePlacement.Home, layout.placementOf(AppTab.Recommended))
        assertEquals(AppPagePlacement.Home, layout.placementOf(AppTab.Music))
        assertEquals(AppPagePlacement.Library, layout.placementOf(AppTab.LibrarySongs))
        assertEquals(AppPagePlacement.TabBar, layout.placementOf(AppTab.LibraryCloud))
    }
}
