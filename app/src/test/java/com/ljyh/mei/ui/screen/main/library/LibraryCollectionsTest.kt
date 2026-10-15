package com.ljyh.mei.ui.screen.main.library

import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.LibraryCollectionPage
import com.ljyh.mei.ui.navigation.ContentFeature
import com.ljyh.mei.ui.navigation.LibraryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionsTest {
    @Test
    fun defaultPageUsesTheMergedAlbumPageOnlyForTheDefaultStyle() {
        val enabledFeatures = ContentFeature.entries.toSet()
        LibraryPage.entries.forEach { page ->
            assertEquals(
                page,
                resolveLibraryDefaultPage(page.name, LibraryStyle.AppleMusic, enabledFeatures),
            )
            assertEquals(
                if (page == LibraryPage.Podcasts) LibraryPage.Albums else page,
                resolveLibraryDefaultPage(page.name, LibraryStyle.Default, enabledFeatures),
            )
        }
    }

    @Test
    fun emptyUnknownAndDisabledDefaultPagesKeepTheStandardEntry() {
        val enabledFeatures = ContentFeature.entries.toSet()
        LibraryStyle.entries.forEach { style ->
            listOf("", "removed-page").forEach { value ->
                assertNull(resolveLibraryDefaultPage(value, style, enabledFeatures))
            }
            LibraryPage.entries.forEach { page ->
                page.requiredFeature?.let { feature ->
                    assertNull(
                        resolveLibraryDefaultPage(page.name, style, enabledFeatures - feature),
                    )
                }
            }
        }
    }

    @Test
    fun overlappingPagesKeepServerOrderAndAdvanceByRawOffset() {
        val first = LibraryCollectionState<String>().append(
            LibraryCollectionPage(listOf("album-1", "album-2"), nextOffset = 2, hasMore = true),
            key = { it },
        )
        val second = first.append(
            LibraryCollectionPage(listOf("album-2", "album-3"), nextOffset = 4, hasMore = true),
            key = { it },
        )

        assertEquals(listOf("album-1", "album-2", "album-3"), second.items)
        assertEquals(4, second.nextOffset)
        assertTrue(second.hasMore)
        assertFalse(second.isLoading)
    }

    @Test
    fun failedNextPageRetainsRowsAndCursorForRetry() {
        val first = LibraryCollectionState<String>().append(
            LibraryCollectionPage(listOf("playlist-1"), nextOffset = 100, hasMore = true),
            key = { it },
        )
        val failed = first.copy(isLoadingMore = true).failed("Temporarily unavailable")
        assertEquals(first.items, failed.items)
        assertEquals(100, failed.nextOffset)
        assertTrue(failed.hasMore)
        assertFalse(failed.isLoadingMore)
        assertFalse(failed.retryFromStart)

        val retried = failed.append(
            LibraryCollectionPage(listOf("playlist-2"), nextOffset = 101, hasMore = false),
            key = { it },
        )
        assertEquals(listOf("playlist-1", "playlist-2"), retried.items)
        assertNull(retried.error)
        assertFalse(retried.hasMore)
    }

    @Test
    fun failedRefreshRetriesFromStartEvenWhenTheLastPageWasAlreadyLoaded() {
        val existing = LibraryCollectionState(
            items = listOf("album-1", "album-2"), nextOffset = 2, hasMore = false,
        )
        val failed = existing.failed("Temporarily unavailable", retryFromStart = true)
        assertEquals(existing.items, failed.items)
        assertTrue(failed.retryFromStart)
        assertFalse(
            failed.append(LibraryCollectionPage(emptyList(), 2, false), key = { it }).retryFromStart,
        )

        val refreshed = LibraryCollectionState<String>().append(
            LibraryCollectionPage(listOf("album-2"), nextOffset = 1, hasMore = false),
            key = { it },
        )
        assertEquals(listOf("album-2"), refreshed.items)
        assertFalse(refreshed.retryFromStart)
        assertNull(refreshed.error)
    }

    @Test
    fun nonAdvancingPageStopsPaginationEvenWhenServerClaimsMore() {
        val state = LibraryCollectionState(items = listOf("album-1"), nextOffset = 50, hasMore = true)
        val result = state.append(
            LibraryCollectionPage(emptyList(), nextOffset = 50, hasMore = true),
            key = { it },
        )
        assertEquals(state.items, result.items)
        assertFalse(result.hasMore)
    }

    @Test
    fun accountIdentityIncludesSessionWithoutExposingCredential() {
        val account = LibraryAccount("123", "private-session-a")
        assertEquals(account, LibraryAccount("123", "private-session-a"))
        assertNotEquals(account, LibraryAccount("123", "private-session-b"))
        assertNotEquals(account, LibraryAccount("456", "private-session-a"))
        assertFalse(account.toString().contains("private-session"))
    }
}
