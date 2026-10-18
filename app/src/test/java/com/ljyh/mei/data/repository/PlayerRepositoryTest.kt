package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PlayerRepositoryTest {
    private val expectedSession = "a".repeat(64)

    @Test
    fun unknownLikeIsReadBeforeTogglingAndOnlySuccessfulWritesChangeSharedState() = runBlocking {
        var responseCode = 200
        val fixture = Fixture { request ->
            if (request.url.encodedPath.contains("song/like")) {
                """{"code":200,"ids":[123]}"""
            } else {
                """{"code":$responseCode,"songs":[],"playlistId":1}"""
            }
        }

        assertEquals(Resource.Success(false), fixture.repository.toggleLike("123", expectedSession))
        assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        assertEquals(2, fixture.requests.size)
        fixture.requests.forEach { assertEquals(expectedSession, it.header(NETEASE_EXPECTED_SESSION_HEADER)) }
        assertFalse(fixture.body(1)["like"].asBoolean)

        responseCode = 301
        assertTrue(fixture.repository.toggleLike("123", expectedSession) is Resource.Error)
        assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun rejectedStatusReadsNeverInventAnUnlikedSongOrReachTheWriteEndpoint() = runBlocking {
        val fixture = Fixture { """{"code":301,"ids":[]}""" }

        assertTrue(fixture.repository.checkSongLike(123, expectedSession) is Resource.Error)
        assertTrue(fixture.repository.toggleLike("123", expectedSession) is Resource.Error)
        assertTrue(fixture.repository.songLikes.value.isEmpty())
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.all { it.url.encodedPath.contains("song/like") })
    }

    @Test
    fun rapidTogglesWaitForTheConfirmedResultAndUseOppositeTargets() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        var writes = 0
        val fixture = Fixture { request ->
            if (request.url.encodedPath.contains("song/like")) {
                """{"code":200,"ids":[]}"""
            } else {
                if (++writes == 1) {
                    writeStarted.complete(Unit)
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                }
                """{"code":200,"songs":[],"playlistId":1}"""
            }
        }
        fixture.repository.checkSongLike(123, expectedSession)
        val first = async { fixture.repository.toggleLike("123", expectedSession) }
        writeStarted.await()
        try {
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.toggleLike("123", expectedSession) }
            assertEquals(2, fixture.requests.size)
            releaseWrite.countDown()
            assertEquals(Resource.Success(true), first.await())
            assertEquals(Resource.Success(false), second.await())
            assertEquals(listOf(true, false), listOf(1, 2).map { fixture.body(it)["like"].asBoolean })
            assertEquals(mapOf("123" to false), fixture.repository.songLikes.value)
        } finally {
            releaseWrite.countDown()
        }
    }

    @Test
    fun accountResetRejectsOldReadsAndQueuedActionsWithoutRepopulatingState() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CountDownLatch(1)
        val fixture = Fixture {
            readStarted.complete(Unit)
            check(releaseRead.await(5, TimeUnit.SECONDS))
            """{"code":200,"ids":[123]}"""
        }
        val read = async { fixture.repository.checkSongLike(123, expectedSession) }
        readStarted.await()
        try {
            val queuedToggle = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.toggleLike("123", expectedSession) }
            fixture.repository.resetLikeState()
            releaseRead.countDown()

            assertTrue(read.await() is Resource.Error)
            assertTrue(queuedToggle.await() is Resource.Error)
            assertTrue(fixture.repository.songLikes.value.isEmpty())
            assertEquals(1, fixture.requests.size)
        } finally {
            releaseRead.countDown()
        }
    }

    @Test
    fun accountResetAlsoDiscardsAnAlreadyRunningWriteResult() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        val fixture = Fixture { request ->
            if (request.url.encodedPath.contains("song/like")) {
                """{"code":200,"ids":[]}"""
            } else {
                writeStarted.complete(Unit)
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                """{"code":200,"songs":[],"playlistId":1}"""
            }
        }
        fixture.repository.checkSongLike(123, expectedSession)
        val toggle = async { fixture.repository.toggleLike("123", expectedSession) }
        writeStarted.await()
        try {
            fixture.repository.resetLikeState()
            releaseWrite.countDown()

            assertTrue(toggle.await() is Resource.Error)
            assertTrue(fixture.repository.songLikes.value.isEmpty())
        } finally {
            releaseWrite.countDown()
        }
    }

    private class Fixture(response: (Request) -> String) {
        val requests = CopyOnWriteArrayList<Request>()
        private val retrofit = Retrofit.Builder()
            .baseUrl("https://example.test/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .body(response(chain.request()).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val repository = PlayerRepository(
            retrofit.create(QQMusicUApiService::class.java),
            retrofit.create(ApiService::class.java),
            retrofit.create(WeApiService::class.java),
        )

        fun body(index: Int) = JsonParser.parseString(
            Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8(),
        ).asJsonObject
    }
}
