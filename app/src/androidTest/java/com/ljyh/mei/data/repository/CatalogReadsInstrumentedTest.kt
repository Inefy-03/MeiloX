package com.ljyh.mei.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.utils.dataStore
import java.io.IOException
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read through the installed app's existing session without publishing account changes. */
@RunWith(AndroidJUnit4::class)
class CatalogReadsInstrumentedTest {
    private val client = RetrofitModule.provideOkHttpClient()
    private val retrofit = RetrofitModule.provideRetrofit(client)
    private val webRetrofit = RetrofitModule.provideWeApiRetrofit(client)
    private val api = RetrofitModule.provideApiService(retrofit)
    private val eapi = RetrofitModule.provideMeloXEapiService(retrofit)
    private val weapi = RetrofitModule.provideMeloXWeapiService(webRetrofit)

    @Test
    fun commentPagesAndRepliesDecodeThroughTheCurrentTransport() = runBlocking {
        val repository = CommentRepository(
            api, RetrofitModule.provideWeApiService(webRetrofit), eapi, weapi,
        )
        val result = repository.getComment("28639182")
        assertTrue("Comment request failed: $result", result is Resource.Success)
        val data = checkNotNull((result as Resource.Success).data.data)
        val comments = checkNotNull(data.comments)
        assertNotNull(data.hasMore)
        val parent = comments.firstOrNull { (it.showFloorComment?.replyCount ?: 0) > 0 }
        assertNotNull("Expected a public comment with replies", parent)
        val replies = repository.getFloorComment(checkNotNull(parent).commentId, "28639182")
        assertTrue("Reply request failed: $replies", replies is Resource.Success)
        assertNotNull((replies as Resource.Success).data.data?.comments)
    }

    @Test
    fun allPlaylistChoicesUseTheExistingAccountWithoutReplacingItsSession() = runBlocking {
        val context = AppContext.instance
        val before = context.dataStore.data.first()[CookieKey]
        val repository = LibraryCollectionsRepository(api, weapi, eapi, context)
        val account = checkNotNull(repository.accounts.first()) { "An existing account is required" }
        val playlists = repository.allPlaylists(account)
        assertEquals(playlists.size, playlists.distinctBy { it.id }.size)
        assertTrue("The existing account should have a playlist", playlists.isNotEmpty())
        assertEquals(account, repository.accounts.first())
        assertTrue("The account credential must remain unchanged", before == context.dataStore.data.first()[CookieKey])
    }

    @Test
    fun podcastPagesUseRawOffsetsThroughTheCurrentTransport() = runBlocking {
        val context = AppContext.instance
        val before = context.dataStore.data.first()[CookieKey]
        val recommendations = weapi.post("/weapi/djradio/recommend/v1")
        assertEquals(200, recommendations.get("code").asInt)
        val radio = checkNotNull(recommendations.getAsJsonArray("djRadios"))
            .map { it.asJsonObject }
            .firstOrNull { (it.get("programCount")?.asInt ?: 0) > 1 }
        assertNotNull("Expected a public podcast with more than one episode", radio)
        val radioId = checkNotNull(radio).get("id").asLong
        suspend fun page(offset: Int) = weapi.post(
            "/weapi/dj/program/byradio",
            mapOf("radioId" to radioId, "offset" to offset, "limit" to 1, "asc" to false),
        ).let { response ->
            assertEquals(200, response.get("code").asInt)
            parsePodcastProgramPage(response, offset)
        }

        val first = page(0)
        assertTrue(first.hasMore)
        assertEquals(1, first.fetchedCount)
        assertEquals(1, first.programs.size)
        val second = page(first.fetchedCount)
        assertEquals(1, second.fetchedCount)
        assertEquals(1, second.programs.size)
        assertTrue(first.programs.single().id != second.programs.single().id)
        assertTrue("The account credential must remain unchanged", before == context.dataStore.data.first()[CookieKey])
    }

    @Test
    fun podcastSearchUsesRelevantDesktopResultsWithoutReplacingTheMobileSession() = runBlocking<Unit> {
        val context = AppContext.instance
        val before = context.dataStore.data.first()
        assumeTrue("An existing mobile session is required", before.neteaseSessionType() == NeteaseSessionType.Mobile)
        val readOnly = readOnlyCatalogClient()
        try {
            val repository = SearchRepository(
                RetrofitModule.provideApiService(RetrofitModule.provideRetrofit(readOnly)),
            )
            val result = repository.search("HOYO", type = 1009, limit = 10)
            assertTrue("Podcast search failed", result is Resource.Success)
            val podcasts = (result as Resource.Success).data.result.podcasts.orEmpty()
            val matches = podcasts.take(5).count { it.name.contains("HOYO", ignoreCase = true) }
            assertTrue("Leading radio results must match the HOYO query", matches > 0)
            Log.i("CatalogReads", "podcast results=${podcasts.size} matchingLeading=$matches session=Mobile")
        } finally {
            assertTrue(
                "The existing account credential must remain unchanged",
                before[CookieKey] == context.dataStore.data.first()[CookieKey],
            )
            assertEquals(NeteaseSessionType.Mobile, context.dataStore.data.first().neteaseSessionType())
            readOnly.connectionPool.evictAll()
            readOnly.dispatcher.executorService.shutdown()
        }
    }

    private fun readOnlyCatalogClient(): OkHttpClient {
        val allowedPaths = setOf(
            "/api/search/get", "/weapi/search/get", "/eapi/search/get",
        )
        val guard = Interceptor { chain ->
            val url = chain.request().url
            if (url.host !in setOf("music.163.com", "interface.music.163.com") ||
                url.encodedPath.trimEnd('/') !in allowedPaths
            ) throw IOException("This regression allows catalog reads only")
            // Reading another account must not accept background session-cookie updates.
            chain.proceed(chain.request()).newBuilder()
                .removeHeader("Set-Cookie")
                .removeHeader("x-encr-ssid")
                .removeHeader("x-encr-sskey")
                .build()
        }
        return RetrofitModule.provideOkHttpClient().newBuilder().apply {
            interceptors().add(0, guard)
            addNetworkInterceptor(guard)
            followRedirects(false)
            followSslRedirects(false)
        }.build()
    }

}
