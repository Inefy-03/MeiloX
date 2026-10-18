package com.ljyh.mei.ui.screen.cloud

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.model.melox.CloudSong
import com.ljyh.mei.data.repository.parseCloudMusicPage
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMusicTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cloudCursorCountsRawRowsBeforeFilteringAndDeduplication() {
        val parsed = parseCloudMusicPage(
            JsonParser.parseString(
                """{"data":[{"songId":1},{"songId":1},{},null],"count":205,"size":42,"maxSize":99}""",
            ).asJsonObject,
            offset = 200,
        )
        assertEquals(204, parsed.nextOffset)
        assertTrue(parsed.hasMore)
        assertEquals(42L, parsed.usedSize)
        assertEquals(99L, parsed.maxSize)
        val merged = CloudMusicUiState(page = page(listOf(song(1)), true, 200)).withCloudPage(parsed)
        assertEquals(listOf(1L), merged.page!!.songs.map(CloudSong::id))
        assertEquals(204, merged.page.nextOffset)
        assertTrue(merged.page.hasMore)
    }

    @Test
    fun invalidContinuationIsRejectedAndEmptyFinalPageIsAccepted() {
        assertThrows(IllegalStateException::class.java) {
            parseCloudMusicPage(JsonParser.parseString("""{"data":[],"hasMore":true}""").asJsonObject, 200)
        }
        assertThrows(IllegalStateException::class.java) {
            parseCloudMusicPage(JsonParser.parseString("""{"count":201}""").asJsonObject, 200)
        }
        val finalPage = parseCloudMusicPage(
            JsonParser.parseString("""{"data":[],"hasMore":false,"count":200}""").asJsonObject,
            200,
        )
        assertFalse(finalPage.hasMore)
        assertEquals(200, finalPage.nextOffset)
    }

    @Test
    fun cloudLoadsOneBoundedPagePerRequestAndContinuesAfterTwoHundredSongs() = runTest(dispatcher) {
        val requests = mutableListOf<Int>()
        lateinit var append: Continuation<CloudMusicPage>
        val viewModel = viewModel { offset ->
            requests += offset
            if (offset == 0) page((1L..200L).map(::song), true, 200)
            else suspendCoroutine { append = it }
        }
        runCurrent()
        assertEquals(listOf(0), requests)
        assertEquals(200, viewModel.state.value.page!!.songs.size)

        viewModel.loadMore()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0, 200), requests)
        assertTrue(viewModel.state.value.isLoadingMore)
        append.resume(page(listOf(song(200), song(201)), false, 202))
        runCurrent()
        viewModel.loadMore()
        runCurrent()

        assertEquals(listOf(0, 200), requests)
        assertEquals((1L..201L).toList(), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertEquals(202, viewModel.state.value.page!!.nextOffset)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun failedAppendPreservesRowsAndRetriesTheSameRawOffset() = runTest(dispatcher) {
        val requests = mutableListOf<Int>()
        var fail = true
        val viewModel = viewModel { offset ->
            requests += offset
            if (offset == 0) page(listOf(song(1)), true, 4)
            else if (fail) error("Page unavailable")
            else page(listOf(song(2)), false, 6)
        }
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(1L), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertEquals(4, viewModel.state.value.page!!.nextOffset)
        assertNotNull(viewModel.state.value.loadMoreError)
        fail = false
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0, 4, 4), requests)
        assertEquals(listOf(1L, 2L), viewModel.state.value.page!!.songs.map(CloudSong::id))
        assertNull(viewModel.state.value.loadMoreError)
    }

    private fun viewModel(load: suspend (Int) -> CloudMusicPage) = CloudMusicViewModel(
        loadPage = load,
        deleteSong = {},
        uploadSong = { _, _ -> },
    )

    private fun page(songs: List<CloudSong>, more: Boolean, nextOffset: Int) = CloudMusicPage(
        songs, count = 205, usedSize = 42, maxSize = 99, hasMore = more, nextOffset = nextOffset,
    )

    private fun song(id: Long) = CloudSong(id, "Song $id", "Artist", "Album", null, 1000, 42, 320, 0)
}
