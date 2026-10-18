package com.ljyh.mei.utils

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.SongDownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.io.File

object DownloadManager {
    private const val SONG_WORK_NAME_PREFIX = "download_song_"
    private val operations = Mutex()
    private val managementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getDefaultDownloadPath(): String {
        return "Music/Mei"
    }

    suspend fun enqueue(
        context: Context,
        songs: List<SongDownloadInfo>,
        playlistName: String,
        playlistId: String = "",
        downloadPath: String = getDefaultDownloadPath()
    ) {
        withContext(Dispatchers.IO) { operations.withLock {
            enqueueLocked(context, songs, playlistName, playlistId, downloadPath)
        } }
    }

    private suspend fun enqueueLocked(
        context: Context,
        songs: List<SongDownloadInfo>,
        playlistName: String,
        playlistId: String,
        downloadPath: String,
    ) {
        DownloadWorker.createNotificationChannel(context)

        val db = AppDatabase.getDatabase(context)

        val uniqueSongs = songs.distinctBy(SongDownloadInfo::songId)
        val requests = uniqueSongs.associate { info -> info.songId to UUID.randomUUID() }
        val tasks = uniqueSongs.map { info ->
            DownloadTask(
                songId = info.songId,
                url = info.url ?: "",
                fileName = "",
                fileType = info.fileType.ifBlank {
                    val pathWithoutQuery = (info.url ?: "").substringBefore("?")
                    val lastSegment = pathWithoutQuery.substringAfterLast("/")
                    lastSegment.substringAfterLast(".", "")
                },
                status = DownloadStatus.PENDING,
                progress = 0,
                songTitle = info.songTitle,
                songArtist = info.songArtist.joinToString("/"),
                songAlbum = info.songAlbum,
                songCover = info.songCover,
                quality = info.quality,
                requestId = requests.getValue(info.songId).toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }

        db.downloadDao().insertAll(tasks)

        val wm = WorkManager.getInstance(context)
        uniqueSongs.forEach { song ->
            val uniqueWorkName = SONG_WORK_NAME_PREFIX + song.songId
            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setId(requests.getValue(song.songId))
                .addTag("download")
                .addTag(uniqueWorkName)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(DownloadWorker.KEY_SONG_IDS, Gson().toJson(listOf(song.songId)))
                        .putString(DownloadWorker.KEY_PLAYLIST_NAME, playlistName)
                        .putString(DownloadWorker.KEY_DOWNLOAD_PATH, downloadPath)
                        .build()
                )
                .build()
            wm.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, workRequest)
        }
        Timber.tag("DownloadManager").d("Song work enqueued: playlist=$playlistId, songs=${songs.size}")
    }

    fun pauseSong(context: Context, songId: String) {
        managementScope.launch { operations.withLock {
            val task = AppDatabase.getDatabase(context).downloadDao().getBySongId(songId) ?: return@withLock
            AppDatabase.getDatabase(context).downloadDao().updateStatus(songId, DownloadStatus.PAUSED)
            cancelRequest(context, task)
        } }
    }

    suspend fun resumeSong(
        context: Context,
        songId: String,
        playlistName: String,
        downloadPath: String = getDefaultDownloadPath()
    ) = withContext(Dispatchers.IO) { operations.withLock {
        val db = AppDatabase.getDatabase(context)
        val task = db.downloadDao().getBySongId(songId) ?: return@withLock
        enqueueLocked(
            context = context,
            songs = listOf(
                SongDownloadInfo(
                    songId = task.songId,
                    url = task.url,
                    songTitle = task.songTitle,
                    songArtist = task.songArtist.split("/").map { it.trim() }.filter { it.isNotBlank() },
                    songAlbum = task.songAlbum,
                    songCover = task.songCover,
                    duration = 0,
                    quality = task.quality,
                )
            ),
            playlistName = playlistName,
            playlistId = "resume_${System.currentTimeMillis()}",
            downloadPath = downloadPath
        )
    } }

    fun deleteTask(context: Context, songId: String) {
        managementScope.launch { operations.withLock {
            val db = AppDatabase.getDatabase(context)
            val task = db.downloadDao().getBySongId(songId) ?: return@withLock
            db.downloadDao().delete(songId)
            cancelRequest(context, task)
            val song = db.songDao().getSong(songId).first()
            song?.path?.let { path ->
                runCatching {
                    if (path.startsWith("content://")) {
                        context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                    } else {
                        File(path).takeIf(File::exists)?.delete()
                    }
                }.onFailure { Timber.w(it, "Unable to remove downloaded file for %s", songId) }
            }
            db.songDao().updatePath(songId, null)
        } }
    }

    fun deleteAll(context: Context) {
        managementScope.launch { operations.withLock {
            val db = AppDatabase.getDatabase(context)
            val tasks = db.downloadDao().getAll().first()
            db.downloadDao().deleteAll()
            tasks.forEach { task ->
                cancelRequest(context, task)
                val song = db.songDao().getSong(task.songId).first()
                song?.path?.let { path ->
                    runCatching {
                        if (path.startsWith("content://")) {
                            context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                        } else {
                            File(path).takeIf(File::exists)?.delete()
                        }
                    }.onFailure { Timber.w(it, "Unable to remove downloaded file for %s", task.songId) }
                }
                db.songDao().updatePath(task.songId, null)
            }
        } }
    }

    fun cancelAll(context: Context) {
        managementScope.launch { operations.withLock {
            val dao = AppDatabase.getDatabase(context).downloadDao()
            dao.getAll().first().filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
                .forEach { task ->
                    dao.updateStatus(task.songId, DownloadStatus.PAUSED)
                    cancelRequest(context, task)
                }
        } }
    }

    private fun cancelRequest(context: Context, task: DownloadTask) {
        val manager = WorkManager.getInstance(context)
        val request = runCatching { UUID.fromString(task.requestId) }.getOrNull()
        if (request != null) manager.cancelWorkById(request)
        else manager.cancelUniqueWork(SONG_WORK_NAME_PREFIX + task.songId)
    }

    fun isSongDownloaded(songId: String): Boolean {
        val db = AppDatabase.getDatabase(AppContext.instance)
        val song = kotlinx.coroutines.runBlocking { db.songDao().getSong(songId).first() }
        val path = song?.path ?: return false
        if (path.startsWith("content://")) return true
        return File(path).exists()
    }

    suspend fun isSongDownloading(songId: String): Boolean {
        val db = AppDatabase.getDatabase(AppContext.instance)
        val task = db.downloadDao().getBySongId(songId)
        return task?.status == DownloadStatus.DOWNLOADING || task?.status == DownloadStatus.PENDING
    }
}
