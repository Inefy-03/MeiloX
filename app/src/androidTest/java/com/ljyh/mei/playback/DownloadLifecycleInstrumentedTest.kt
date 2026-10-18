package com.ljyh.mei.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.DownloadManager
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local files, Room and loopback HTTP only; no account endpoint is used. */
@RunWith(AndroidJUnit4::class)
class DownloadLifecycleInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun retiredRequestsCannotChangePausedReplacedOrDeletedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.downloadDao()
            val original = DownloadTask("fixture", requestId = "old")
            dao.insert(original)
            assertEquals(1, dao.updateOwnedProgress("fixture", "old", DownloadStatus.DOWNLOADING, 5, 1))
            dao.updateStatus("fixture", DownloadStatus.PAUSED)
            assertEquals(0, dao.updateOwnedProgress("fixture", "old", DownloadStatus.COMPLETED, 100, 2))
            assertEquals(DownloadStatus.PAUSED, dao.getBySongId("fixture")?.status)
            dao.insert(original.copy(requestId = "new"))
            assertEquals(0, dao.updateOwnedProgress("fixture", "old", DownloadStatus.FAILED, 0, 3))
            assertEquals(1, dao.updateOwnedProgress("fixture", "new", DownloadStatus.DOWNLOADING, 9, 4))
            dao.delete("fixture")
            assertEquals(0, dao.updateOwnedProgress("fixture", "new", DownloadStatus.COMPLETED, 100, 5))
            dao.insert(DownloadTask("legacy"))
            assertEquals(1, dao.claimLegacyRequest("legacy", "worker"))
            assertEquals(0, dao.claimLegacyRequest("legacy", "retired"))
            assertEquals("worker", dao.getBySongId("legacy")?.requestId)
        } finally { db.close() }
    }

    @Test fun cancellationClosesABlockedAudioReadAndRemovesPartialFile() = runBlocking {
        StalledAudio().use { server ->
            val file = File.createTempFile("download-cancel-", ".wav", context.cacheDir)
            try {
                val job = async(Dispatchers.IO) { downloadAudioFile(OkHttpClient(), server.url, file) {} }
                assertTrue(server.entered.await(5, TimeUnit.SECONDS))
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(server.closed.await(3, TimeUnit.SECONDS))
                assertFalse(file.exists())
            } finally { file.delete() }
        }
    }

    @Test fun pausingARealWorkerKeepsItsRowPausedAndDoesNotPublish() = runBlocking {
        val songId = "download-fixture-${UUID.randomUUID()}"
        val db = AppDatabase.getDatabase(context)
        StalledAudio().use { server ->
            try {
                DownloadManager.enqueue(context, listOf(SongDownloadInfo(songId, server.url,
                    "Download cancellation fixture", listOf("Fixture"), "Fixture", "", 0, "wav")),
                    "Lifecycle fixture", downloadPath = "Music/MeiloX-test-fixtures")
                assertTrue(server.entered.await(15, TimeUnit.SECONDS))
                DownloadManager.pauseSong(context, songId)
                withTimeout(5_000) {
                    while (db.downloadDao().getBySongId(songId)?.status != DownloadStatus.PAUSED) delay(30)
                }
                assertTrue(server.closed.await(5, TimeUnit.SECONDS))
                delay(200)
                assertEquals(DownloadStatus.PAUSED, db.downloadDao().getBySongId(songId)?.status)
                assertNull(db.songDao().getSong(songId).first()?.path)
            } finally {
                db.downloadDao().getBySongId(songId)?.requestId?.let {
                    androidx.work.WorkManager.getInstance(context).cancelWorkById(UUID.fromString(it)).result.get()
                }
                db.downloadDao().delete(songId)
            }
        }
    }

    private class StalledAudio : AutoCloseable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private var socket: Socket? = null
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val url = "http://127.0.0.1:${listener.localPort}/audio.wav"
        private val thread = Thread {
            try {
                listener.accept().use { client ->
                    socket = client
                    client.soTimeout = 15_000
                    val input = client.getInputStream()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val b = input.read(); if (b < 0) return@Thread
                        tail = (tail + b.toChar()).takeLast(4)
                    }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                        write(ByteArray(1024)); flush()
                    }
                    entered.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            } catch (_: Exception) { closed.countDown() }
        }.apply { isDaemon = true; start() }
        override fun close() { socket?.close(); listener.close(); thread.join(1_000) }
    }
}
