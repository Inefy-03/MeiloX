package com.ljyh.mei.data.network

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.utils.dataStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual issue #44 probe against the signed-in account. Disabled in ordinary test runs.
 *
 * Arguments: issue44SimilarRadio=true, sourceIds=[41416746,26537202,22692733] (optional).
 * Fetches two recommendation batches without playing songs or changing favorites.
 * Status output includes only protocol parameters, HTTP status, and public song IDs.
 */
@RunWith(AndroidJUnit4::class)
class HomeSimilarRadioInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(NeteaseInterceptor())
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request).also { response ->
                    report("wire_response path=${request.url.encodedPath} http=${response.code}")
                }
            }
            .build()
    }
    private val weApiService by lazy {
        RetrofitModule.provideWeApiService(RetrofitModule.provideWeApiRetrofit(client))
    }

    @Test
    fun diagnoseSimilarRadio() {
        assumeTrue(arguments.getString("issue44SimilarRadio") == "true")
        runBlocking(Dispatchers.IO) {
            val preferences = instrumentation.targetContext.dataStore.data.first()
            val signedIn = !preferences[CookieKey].isNullOrBlank()
            report("session signedIn=$signedIn")
            assertTrue("The probe requires a signed-in account", signedIn)

            val sourceIds = parseSourceIds(
                arguments.getString("sourceIds") ?: "[41416746,26537202,22692733]",
            )
            val firstIds = requestBatch("first", sourceIds, emptyList())
            val secondIds = requestBatch("continuation", sourceIds, firstIds)
            // Continuation validates the protocol, not server-side deduplication guarantees.
            val repeatedIds = secondIds.filter { it in firstIds }.distinct()
            report("continuation repeatedSongIds=${encodeIds(repeatedIds)}")
        }
    }

    private suspend fun requestBatch(
        stage: String,
        sourceIds: List<Long>,
        unplayedIds: List<Long>,
    ): List<Long> {
        val parameters = linkedMapOf(
            "limit" to "3",
            "sourceIds" to encodeIds(sourceIds),
            "sourceType" to "song",
            "mode" to "SIMILAR",
            // The official similar-FM endpoint uses this spelling and a numeric array string.
            "unplaySonIds" to encodeIds(unplayedIds),
        )
        report("request stage=$stage ${parameters.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
        val response = weApiService.getRadio(parameters)
        val songIds = response.data.orEmpty().map { it.id }
        report("response stage=$stage code=${response.code} songIds=${encodeIds(songIds)}")
        assertEquals("$stage recommendation business code", 200, response.code)
        assertTrue("$stage recommendation batch must not be empty", songIds.isNotEmpty())
        assertTrue("$stage recommendation song IDs must be positive", songIds.all { it > 0L })
        return songIds
    }

    private fun parseSourceIds(raw: String): List<Long> {
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()
        requireNotNull(array) { "sourceIds must be a JSON array of positive song IDs" }
        val ids = array.map { element ->
            val id = runCatching { element.asJsonPrimitive.asString.toLongOrNull() }.getOrNull()
            requireNotNull(id?.takeIf { it > 0L }) { "sourceIds must contain only positive song IDs" }
        }
        require(ids.isNotEmpty()) { "sourceIds must not be empty" }
        return ids
    }

    private fun encodeIds(ids: List<Long>): String = ids.joinToString(prefix = "[", postfix = "]")

    private fun report(message: String) {
        Log.i("Issue44SimilarRadioProbe", message)
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("stream", "Issue44SimilarRadioProbe $message\n") },
        )
    }
}
