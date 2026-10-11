package com.ljyh.mei.utils.lyric

import android.content.Context
import com.ljyh.mei.constants.QqTimeout
import com.ljyh.mei.constants.QqTimeoutKey
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.di.repository.CachedLyricRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.encrypt.QRCUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.FlowPreview::class)

/**
 * 歌词管理器
 *
 * 协调歌词的获取、合并、缓存、预加载和AI增强全过程。
 *
 * 数据流概要：
 * 1. [loadLyrics] 被调用时重置所有源状态为 Loading
 * 2. 并行拉取三源（网易云、AM、QQ），各自更新对应的 StateFlow
 * 3. [combine] 监听三个 StateFlow，任一变化触发 [mergeAndApply]
 * 4. [mergeLyrics] 按优先级选出最佳歌词
 * 5. 内存缓存 (lyricCache) 和 Room 持久化 (cached_lyric) 加速后续加载
 * 6. 歌词预加载 ([preloadLyrics]) 提前缓存下一首
 * 7. 本地对唱检测 ([DuetDetector]) 合并后对对唱歌词进行对齐
 */
@Singleton
class LyricManager @Inject constructor(
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val cachedLyricRepository: CachedLyricRepository,
    private val duetDetector: DuetDetector,
    private val preloader: LyricPreloader,
    @ApplicationContext private val context: Context
) {

    private val TAG = "LyricManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ==================== 状态暴露 ====================

    /** 当前歌词数据，UI 层通过 collectAsState 消费 */
    private val _lyricData =
        MutableStateFlow(createDefaultLyricData("歌词加载中", source = LyricSource.Loading))
    val lyricData: StateFlow<LyricData> = _lyricData.asStateFlow()

    /** QQ 音乐搜索结果，供手动选歌 sheet 使用 */
    private val _qqSearchResult = MutableStateFlow<Resource<SearchResult>>(Resource.Loading)
    val qqSearchResult: StateFlow<Resource<SearchResult>> = _qqSearchResult.asStateFlow()

    // ==================== 当前歌曲状态 ====================

    /** 当前正在加载歌词的歌曲 ID，用于防止重复加载 */
    private var currentSongId: String? = null

    val songId: String? get() = currentSongId

    /** 当前歌词拉取协程 Job，切歌时 cancel */
    private var fetchJob: Job? = null

    /** Current QQ lyric request, including manually selected songs. */
    private var qqFetchJob: Job? = null

    /** Reject suspended merges from an earlier load, including an A -> B -> A switch. */
    private var loadGeneration = 0L

    // ==================== 歌词缓存 ====================

    /** 内存缓存，FIFO 淘汰，最多 5 首 */
    private val lyricCache = LinkedHashMap<String, LyricData>()

    /** 预加载协程 Job */
    private var preloadJob: Job? = null

    // ==================== 三源 StateFlow ====================

    /** 网易云歌词拉取状态 */
    private val netLyricResult = MutableStateFlow<Resource<Lyric>>(Resource.Loading)

    /** QQ 音乐歌词拉取状态 */
    private val qqLyricResult = MutableStateFlow<Resource<LyricResult>>(Resource.Loading)

    /** AM (Apple Music TTML) 歌词拉取状态 */
    private val amLyricResult = MutableStateFlow<Resource<String>>(Resource.Loading)

    // ==================== 合并入口 ====================

    /**
     * 组合三个源的拉取状态，任一完成即触发合并。
     * sample(50) 防抖，避免短时间内多次触发。
     */
    init {
        combine(netLyricResult, qqLyricResult, amLyricResult) { net, qq, am ->
            loadGeneration to Triple(net, qq, am)
        }.sample(50)
            .onEach { (generation, sources) ->
                mergeAndApply(sources.first, sources.second, sources.third, generation)
            }.launchIn(scope)
    }

    // ==================== 公开 API ====================

    /**
     * 为指定歌曲加载歌词
     *
     * 流程：
     * 1. 检查缓存（内存 → Room）
     * 2. 并行拉取网易云、AM、QQ 三源
     * 3. 通过 combine → mergeAndApply 渐进式更新歌词
     *
     * @param metadata 歌曲元数据
     * @param forceReload 是否强制重新拉取，true 时跳过缓存和 currentSongId 拦截
     */
    fun loadLyrics(metadata: MediaMetadata, forceReload: Boolean = false) {
        val songId = metadata.id.toString()
        if (!forceReload && currentSongId == songId) return

        currentSongId = songId
        fetchJob?.cancel()
        qqFetchJob?.cancel()
        loadGeneration++

        // 重置所有源状态
        netLyricResult.value = Resource.Loading
        qqLyricResult.value = Resource.Loading
        amLyricResult.value = Resource.Loading
        _qqSearchResult.value = Resource.Loading
        lrcFallbackContent = null

        lastMetadata = metadata

        // A memory hit is already displayable; do not emit a loading frame before it.
        _lyricData.value = (if (!forceReload) lyricCache[songId] else null)
            ?: createDefaultLyricData("歌词加载中", source = LyricSource.Loading)

        // 网络拉取
        fetchJob = scope.launch {
            // Room 缓存查找（异步，不阻塞主线程）
            if (!forceReload && currentSongId == songId) {
                val dbCached = withContext(Dispatchers.IO) {
                    cachedLyricRepository.get(songId).firstOrNull()
                }
                if (dbCached != null && currentSongId == songId) {
                    dbCached.toLyricData()?.let { data ->
                        val current = _lyricData.value
                        val selected = selectLyricUpdate(
                            current, data,
                            sourcesFinished = false, alignmentFinished = false,
                        )
                        if (selected !== current) {
                            _lyricData.value = selected
                            lyricCache[songId] = selected
                            trimCache()
                        }
                    }
                }
            }

            delay(100)

            launch { fetchNetEaseLyric(songId) }
            launch { fetchAMLLyric(songId) }

            // QQ 音乐拉取（带超时控制）
            val localSong = qqSongRepository.getQQSong(songId).firstOrNull()
            val qqTimeout = try {
                QqTimeout.valueOf(
                    context.dataStore.data.first()[QqTimeoutKey] ?: QqTimeout.Sec8.name
                ).seconds
            } catch (_: Exception) {
                8
            }
            try {
                withTimeout(qqTimeout * 1000L) {
                    if (localSong != null) {
                        fetchQQLyric(localSong)
                    } else {
                        autoSearchAndPickBest(metadata)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                qqLyricResult.value = Resource.Error("QQ timed out")
            }

            // 预加载已有 QQSong 时，补充填充搜索结果供 Sheet 使用
            if (localSong != null) {
                launch { searchAndMatchBest(metadata) }
            }
        }
    }

    /**
     * 自动搜索 QQ 音乐并选择最佳匹配
     *
     * 先按歌名搜索，无 duration 匹配时回退为"歌名+歌手"搜索
     */
    private suspend fun autoSearchAndPickBest(metadata: MediaMetadata) {
        val best = searchAndMatchBest(metadata)
        if (best != null) {
            val qqSong = QQSong(
                id = metadata.id.toString(),
                qid = best.id.toString(),
                title = best.title,
                artist = best.singer.joinToString(",") { it.name },
                album = best.album.title,
                duration = best.interval
            )
            qqSongRepository.insertSong(qqSong)
            fetchQQLyric(qqSong)
        } else {
            qqLyricResult.value = Resource.Error("No matching QQ song")
        }
    }

    /**
     * 两级搜索匹配：先按歌名，无匹配则按"歌名+歌手"
     *
     * @return 匹配到的 QQ 歌曲，未匹配到返回 null
     */
    private suspend fun searchAndMatchBest(metadata: MediaMetadata): SearchResult.Request.Data.Body.ItemSong? {
        val currentDurationSec = metadata.duration / 1000
        val artistName = metadata.artists.firstOrNull()?.name ?: ""
        val title = metadata.title
        val cleanedTitle = cleanTitle(title)

        // 1. 清洗后的歌名
        if (cleanedTitle != title) {
            Timber.tag(TAG).d("QQ search : $cleanedTitle")
            val best = trySearchMatch(cleanedTitle, currentDurationSec)
            _qqSearchResult.value = trySearchLastResult
            if (best != null) return best
        }

        // 2. 原始歌名
        Timber.tag(TAG).d("QQ search retry with: $title")
        val bestByTitle = trySearchMatch(title, currentDurationSec)
        _qqSearchResult.value = trySearchLastResult
        if (bestByTitle != null) return bestByTitle

        // 3. 清洗后歌名+歌手
        if (artistName.isNotBlank() && cleanedTitle != title) {
            val combined = "$cleanedTitle $artistName"
            Timber.tag(TAG).d("QQ search retry with cleanedTitle+artist: $combined")
            val best = trySearchMatch(combined, currentDurationSec)
            _qqSearchResult.value = trySearchLastResult
            if (best != null) return best
        }

        // 4. 原始歌名+歌手
        if (artistName.isNotBlank()) {
            val combined = "$title $artistName"
            Timber.tag(TAG).d("QQ search retry with title+artist: $combined")
            val best = trySearchMatch(combined, currentDurationSec)
            _qqSearchResult.value = trySearchLastResult
            if (best != null) return best
        }

        return null
    }

    /**
     * 去除歌名中的括号内容（如 feat./with/remix 等附加信息）
     *
     * 处理中文括号（）和英文括号 ()。
     * 例如 "abc (feat. xxx)" → "abc"
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""[\(（][^)）]*[\)）]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** 缓存最后一次 QQ 搜索结果，供 _qqSearchResult 和预加载流程使用 */
    private var trySearchLastResult: Resource<SearchResult> = Resource.Loading

    /**
     * 搜索 QQ 音乐并在前 5 条结果中匹配时长（±5 秒）
     *
     * @param keyword 搜索关键词
     * @param targetDurationSec 目标时长（秒）
     * @return 匹配到的歌曲，未匹配到返回 null
     */
    private suspend fun trySearchMatch(
        keyword: String,
        targetDurationSec: Long
    ): SearchResult.Request.Data.Body.ItemSong? {
        val result = repository.searchNew(keyword)
        trySearchLastResult = result
        if (result !is Resource.Success) return null
        val songs = result.data.request.data.body.itemSong
        return songs.take(5).firstOrNull { song ->
            abs(targetDurationSec - song.interval) <= 5
        }
    }

    /**
     * 拉取网易云歌词
     *
     * 写入 netLyricResult（Lyric 结构体，含 lrc/yrc/tlyric/ytlrc 等字段）。
     */
    private suspend fun fetchNetEaseLyric(id: String) {
        try {
            netLyricResult.value = repository.getLyricV1(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "NetEase fetch error")
            netLyricResult.value = Resource.Error("NetEase fetch failed")
        }
    }

    /**
     * 拉取 Apple Music TTML 逐字歌词
     *
     * 写入 amLyricResult（原始 TTML 字符串）。
     */
    private suspend fun fetchAMLLyric(id: String) {
        try {
            amLyricResult.value = repository.getAMLLyric(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AML fetch error")
            amLyricResult.value = Resource.Error("AML fetch failed")
        }
    }

    /** 当前 QQ 歌曲引用（用于 LRC 回退） */
    private var currentQQSong: QQSong? = null

    /**
     * 拉取 QQ 音乐歌词
     *
     * 写入 qqLyricResult。若返回 QRC 格式（qrcT ≠ 0），额外拉取 LRC 兜底。
     */
    fun fetchQQLyric(song: QQSong) {
        qqFetchJob?.cancel()
        qqFetchJob = scope.launch {
            currentQQSong = song
            qqLyricResult.value = Resource.Loading
            try {
                val result = repository.getLyricNew(
                    song.title, song.album, song.artist, song.duration, song.qid.toLong()
                )
                qqLyricResult.value = result
                if (result is Resource.Success) {
                    val qrcT = result.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo.data.qrcT
                    if (qrcT != 0) {
                        fetchQQLyricLrc(song)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "QQ fetch error")
                qqLyricResult.value = Resource.Error("QQ fetch failed")
            }
        }
    }

    /**
     * QQ 歌词 LRC 回退
     *
     * 当主歌词是 QRC 逐字格式时，额外拉取纯 LRC 作为兜底（qrc=0, qrcT=0）。
     * 解码后存入 lrcFallbackContent 并触发重合并。
     */
    private suspend fun fetchQQLyricLrc(song: QQSong) {
        try {
            val lrcResult = repository.getLyricLrc(
                song.title, song.album, song.artist, song.duration, song.qid.toLong()
            )
            if (lrcResult is Resource.Success) {
                val lrcContent = QRCUtils.decodeLyric(
                    lrcResult.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo.data.lyric
                )
                lrcFallbackContent = lrcContent
                remergeLyrics()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "QQ LRC fallback fetch error")
        }
    }

    /** QQ LRC 回退内容，供 mergeLyrics 中 QQ 源使用 */
    private var lrcFallbackContent: String? = null

    /**
     * 触发重合并
     *
     * 当 LRC 回退数据到达时，用当前三个源的现有状态重新合并歌词。
     */
    private fun remergeLyrics() {
        val generation = loadGeneration
        scope.launch {
            mergeAndApply(
                netLyricResult.value,
                qqLyricResult.value,
                amLyricResult.value,
                generation,
            )
        }
    }

    // ==================== 合并逻辑 ====================

    /** Resolve source selection and duet alignment before publishing a single snapshot. */
    private suspend fun mergeAndApply(
        net: Resource<Lyric>,
        qq: Resource<LyricResult>,
        am: Resource<String>,
        generation: Long,
    ) {
        val songIdAtStart = currentSongId ?: return
        fun isCurrentSnapshot() = generation == loadGeneration && currentSongId == songIdAtStart
                && net === netLyricResult.value && qq === qqLyricResult.value
                && am === amLyricResult.value
        if (!isCurrentSnapshot()) return
        val netTerminal = net !is Resource.Loading
        val qqTerminal = qq !is Resource.Loading
        val sourcesFinished = netTerminal && qqTerminal && am !is Resource.Loading
        val alignmentFinished = am is Resource.Success || (netTerminal && qqTerminal)
        val fallbackContent = lrcFallbackContent

        data class MergeResult(
            val lyricData: LyricData,
            val cacheContent: String?,
            val cacheTranslation: String?,
            val cacheParserType: String,
        )

        val mergeResult = withContext(Dispatchers.IO) {
            val isPureMusic = (net as? Resource.Success)?.data?.pureMusic == true
            val sources = mutableListOf<LyricSourceData>()
            (am as? Resource.Success)?.let { sources.add(LyricSourceData.AM(it.data)) }
            (net as? Resource.Success)?.data?.let { sources.add(LyricSourceData.NetEase(it)) }
            (qq as? Resource.Success)?.data?.musicMusichallSongPlayLyricInfoGetPlayLyricInfo?.data?.let { data ->
                try {
                    val decoded = data.copy(
                        lyric = QRCUtils.decodeLyric(data.lyric),
                        trans = QRCUtils.decodeLyric(data.trans, true),
                        roma = QRCUtils.decodeLyric(data.roma),
                    )
                    sources.add(LyricSourceData.QQMusic(decoded, data.qrcT != 0, fallbackContent))
                } catch (e: Exception) {
                    Timber.e(e, "QRC decoding failed")
                }
            }

            var lyricData = mergeLyrics(sources, isPureMusic)
            // Role detection needs NetEase's timeline even when QQ supplies the words.
            // Finish it before any UI update or suspending cache write.
            if (netTerminal && qqTerminal && am !is Resource.Success) {
                val netease = sources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()
                val lrcText = netease?.lyric?.lrc?.lyric
                if (netease != null && !lrcText.isNullOrBlank() && duetDetector.isDuetLikely(lrcText)) {
                    val qqSource = sources.filterIsInstance<LyricSourceData.QQMusic>().firstOrNull()
                    val dueted = if (qqSource != null) {
                        duetDetector.mergeWithDuet(netease, qqSource)
                    } else {
                        duetDetector.singleDuet(netease)
                    }
                    if (dueted != null && dueted.lyricLine.lines.isNotEmpty()) lyricData = dueted
                }
            }
            val (content, translation, parserType) = buildCacheInfo(sources, lyricData)
            MergeResult(lyricData, content, translation, parserType)
        }

        if (!isCurrentSnapshot()) return
        val current = _lyricData.value
        val selected = selectLyricUpdate(
            current, mergeResult.lyricData, sourcesFinished, alignmentFinished,
        )
        if (selected === current) return

        _lyricData.value = selected
        if (selected.source == LyricSource.Loading || selected.source == LyricSource.Empty) return
        lyricCache[songIdAtStart] = selected
        trimCache()
        val content = mergeResult.cacheContent ?: return
        cachedLyricRepository.insert(
            CachedLyric(
                songId = songIdAtStart,
                content = content,
                translation = mergeResult.cacheTranslation,
                isVerbatim = selected.isVerbatim,
                isPureMusic = selected.isPureMusic,
                sourceName = selected.source.name,
                parserType = mergeResult.cacheParserType,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private var lastMetadata: MediaMetadata? = null

    private fun getCurrentMetadata(): MediaMetadata? = lastMetadata

    /**
     * 手动搜索 QQ 音乐（供选歌 sheet 使用）
     *
     * 结果写入 [_qqSearchResult]，UI 层通过 [qqSearchResult] 观察。
     */
    fun searchQQSong(keyword: String) {
        _qqSearchResult.value = Resource.Loading
        scope.launch {
            _qqSearchResult.value = repository.searchNew(keyword)
        }
    }

    /**
     * 用户手动选择 QQ 歌曲作为歌词来源
     *
     * 插入 QQSong 映射到 Room，然后拉取歌词。
     */
    fun selectQQSongForLyric(metadata: MediaMetadata, song: SearchResult.Request.Data.Body.ItemSong) {
        scope.launch {
            val qqSong = QQSong(
                id = metadata.id.toString(),
                qid = song.id.toString(),
                title = song.title,
                artist = song.singer.joinToString(",") { it.name },
                album = song.album.title,
                duration = song.interval
            )
            qqSongRepository.insertSong(qqSong)

            // A manual selection is an explicit source switch. Cancel the automatic
            // multi-source load and clear its results so NetEase/AM cannot win the
            // merge again while the selected QQ lyric is being fetched.
            currentSongId = metadata.id.toString()
            lastMetadata = metadata
            fetchJob?.cancel()
            qqFetchJob?.cancel()
            loadGeneration++
            netLyricResult.value = Resource.Error("Manual QQ selection")
            qqLyricResult.value = Resource.Loading
            amLyricResult.value = Resource.Error("Manual QQ selection")
            lrcFallbackContent = null
            _lyricData.value = createDefaultLyricData("歌词加载中", source = LyricSource.Loading)

            fetchQQLyric(qqSong)
        }
    }

    /**
     * 取消当前所有拉取和预加载任务
     */
    fun cancelAll() {
        fetchJob?.cancel()
        qqFetchJob?.cancel()
        preloadJob?.cancel()
        currentSongId = null
        loadGeneration++
    }

    // ==================== 歌词预加载 ====================

    /**
     * 预加载指定歌曲的歌词到内存缓存
     *
     * 由 [PlayerStateContainer] 在切歌时调用，提前拉取下一首。
     * 委托给 [LyricPreloader] 执行，不干扰当前歌词展示。
     */
    fun preloadLyrics(metadata: MediaMetadata) {
        val songId = metadata.id.toString()
        if (lyricCache.containsKey(songId) || currentSongId == songId) return

        preloadJob?.cancel()
        preloadJob = scope.launch {
            val result = preloader.preload(metadata)
            if (result != null && result.source != LyricSource.Empty
                && result.source != LyricSource.Loading && result.lyricLine.lines.isNotEmpty()
            ) {
                // A late preload must not replace an already aligned foreground cache.
                lyricCache[songId] = lyricCache[songId]?.let { existing ->
                    selectLyricUpdate(existing, result, sourcesFinished = false, alignmentFinished = false)
                } ?: result
                trimCache()
            }
        }
    }

    // ==================== 缓存管理 ====================

    /** 内存缓存 FIFO 逐出，保留最近 5 首 */
    private fun trimCache() {
        while (lyricCache.size > 5) {
            lyricCache.remove(lyricCache.entries.first().key)
        }
    }

}

/** Returns raw text and its matching parser for persistent lyric caching. */
internal fun buildCacheInfo(
    sources: List<LyricSourceData>,
    lyricData: LyricData
): Triple<String?, String?, String> {
    return when (lyricData.source) {
        LyricSource.AM -> Triple(null, null, "TTML")
        LyricSource.NetEaseCloudMusic -> {
            val netease = sources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()
            if (lyricData.isVerbatim) {
                Triple(null, null, "YRC")
            } else {
                val lrc = netease?.lyric?.lrc?.lyric?.takeIf { it.isNotBlank() }
                val translation = netease?.lyric?.tlyric?.lyric?.takeIf { it.isNotBlank() }
                Triple(lrc, translation, "LRC")
            }
        }

        LyricSource.QQMusic -> {
            val qq = sources.filterIsInstance<LyricSourceData.QQMusic>().firstOrNull()
            val translation = qq?.lyric?.trans?.takeIf { it.isNotBlank() }
            if (lyricData.isVerbatim) {
                // QRC must retain its word timestamps, never the LRC fallback.
                Triple(qq?.lyric?.lyric?.takeIf { it.isNotBlank() }, translation, "QRC")
            } else {
                val lrc = qq?.lrcContent?.takeIf { it.isNotBlank() }
                    ?: qq?.lyric?.lyric?.takeIf { it.isNotBlank() }
                Triple(lrc, translation, "LRC")
            }
        }

        else -> Triple(null, null, "LRC")
    }
}

/** Restores cached lyrics, including legacy LRC entries incorrectly tagged as QRC. */
internal fun CachedLyric.toLyricData(): LyricData? {
    var restoredVerbatim = isVerbatim
    val lyrics = when (parserType) {
        "TTML" -> TTMLParser().parse(content)
        "YRC" -> YRCParser.parse(content, translation)
        "QRC" -> {
            // Cache text and translation have already been decoded before insertion.
            val qrc = QRCParser.parse(content, translation)
            if (qrc.lines.isNotEmpty()) {
                qrc
            } else {
                restoredVerbatim = false
                LRCParser.parse(content, translation)
            }
        }
        else -> LRCParser.parse(content, translation)
    }
    if (lyrics.lines.isEmpty()) return null
    return LyricData(
        isVerbatim = restoredVerbatim,
        isPureMusic = isPureMusic,
        source = try {
            LyricSource.valueOf(sourceName)
        } catch (_: IllegalArgumentException) {
            LyricSource.Empty
        },
        lyricLine = lyrics
    )
}
