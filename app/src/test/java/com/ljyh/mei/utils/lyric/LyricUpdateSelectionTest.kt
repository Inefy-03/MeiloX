package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricUpdateSelectionTest {
    @Test
    fun cachedWordLyricsNeverRegressToNetEaseLineLyrics() {
        val cached = qqLyrics(translation = "Translation")
        val neteaseLines = LyricData(
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = LRCParser.parse("[00:01.00]Verse", null)
        )

        assertSame(cached, selectLyricUpdate(cached, neteaseLines, false, false))
        assertSame(cached, selectLyricUpdate(cached, neteaseLines, true, true))
    }

    @Test
    fun roomRestoreDoesNotRemoveCachedDuetAlignment() {
        val roomLyrics = qqLyrics(translation = "Translation")
        val memoryLyrics = roomLyrics.withAlignment(KaraokeAlignment.End)

        val selected = selectLyricUpdate(memoryLyrics, roomLyrics, false, false)

        assertSame(memoryLyrics, selected)
        assertEquals(
            KaraokeAlignment.End,
            (selected.lyricLine.lines.single() as KaraokeLine).alignment
        )
    }

    @Test
    fun higherPriorityVerbatimSourcesCanUpgradeWhileOtherSourcesLoad() {
        val cached = qqLyrics()
        val netease = LyricData(
            isVerbatim = true,
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = YRCParser.parse("[1000,1000](1000,1000,0)Verse", null)
        )
        val appleMusic = LyricData(
            isVerbatim = true,
            source = LyricSource.AM,
            lyricLine = TTMLParser().parse(
                """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Verse</span></p></div></body></tt>"""
            )
        )

        assertEquals(1, netease.lyricLine.lines.size)
        assertEquals(1, appleMusic.lyricLine.lines.size)
        val upgraded = selectLyricUpdate(cached, netease, false, false)
        assertSame(netease, upgraded)
        assertSame(appleMusic, selectLyricUpdate(upgraded, appleMusic, false, false))
    }

    @Test
    fun firstUsableLyricsReplaceTheLoadingPlaceholderImmediately() {
        val loading = createDefaultLyricData("Loading", source = LyricSource.Loading)
        val candidate = qqLyrics()

        assertSame(candidate, selectLyricUpdate(loading, candidate, false, false))
    }

    @Test
    fun pureMusicPlaceholderDoesNotBlockRealQqLineLyrics() {
        val placeholder = mergeLyrics(emptyList(), isPureMusic = true)
        val loading = createDefaultLyricData("Loading", source = LyricSource.Loading)
        assertEquals(LyricSource.Empty, placeholder.source)
        assertTrue(placeholder.isPureMusic)
        assertEquals(
            createDefaultLyricData("纯音乐，请欣赏", isPureMusic = true, source = LyricSource.Empty),
            selectLyricUpdate(loading, placeholder, true, true)
        )

        for (pureMusic in listOf(false, true)) {
            val lyrics = LyricData(
                isPureMusic = pureMusic,
                source = LyricSource.QQMusic,
                lyricLine = LRCParser.parse("[00:01.00]Verse", null)
            )
            assertSame(lyrics, selectLyricUpdate(placeholder, lyrics, false, false))
        }
    }

    @Test
    fun identicalReparsedLyricsKeepTheDisplayedObject() {
        val current = qqLyrics(translation = "Translation")
        val reparsed = qqLyrics(translation = "Translation")
        assertNotSame(current, reparsed)
        assertNotSame(current.lyricLine, reparsed.lyricLine)
        assertEquals(current, reparsed)

        assertSame(current, selectLyricUpdate(current, reparsed, true, true))
    }

    @Test
    fun sameQualityContentChangesWaitForAlignmentAndThenUpdate() {
        val current = qqLyrics(translation = "Old translation")
        val candidates = listOf(
            qqLyrics(translation = "New translation"),
            qqLyrics(start = 2_000, translation = "Old translation"),
            qqLyrics(text = "Revised verse", translation = "Old translation"),
            current.withAlignment(KaraokeAlignment.End)
        )

        for (candidate in candidates) {
            assertNotEquals(current, candidate)
            assertSame(current, selectLyricUpdate(current, candidate, false, false))
            assertSame(current, selectLyricUpdate(current, candidate, true, false))
            assertSame(candidate, selectLyricUpdate(current, candidate, false, true))
        }
    }

    @Test
    fun pendingEmptyResultKeepsLoadingUntilAllSourcesFinish() {
        val loading = createDefaultLyricData("Loading", source = LyricSource.Loading)
        val noLyrics = createDefaultLyricData("No lyrics", source = LyricSource.Empty)

        // Placeholders contain a display line, so line count alone cannot establish validity.
        assertEquals(1, noLyrics.lyricLine.lines.size)
        assertSame(loading, selectLyricUpdate(loading, noLyrics, false, true))
        assertSame(noLyrics, selectLyricUpdate(loading, noLyrics, true, false))
    }

    @Test
    fun identicalFinalEmptyResultAlsoKeepsTheDisplayedObject() {
        val current = createDefaultLyricData("No lyrics", source = LyricSource.Empty)
        val reparsed = createDefaultLyricData("No lyrics", source = LyricSource.Empty)
        assertNotSame(current, reparsed)
        assertEquals(current, reparsed)

        assertSame(current, selectLyricUpdate(current, reparsed, true, true))
    }

    @Test
    fun emptyTtmlKeepsLoadingUntilFinishedThenShowsTheEmptyPlaceholder() {
        val loading = createDefaultLyricData("Loading", source = LyricSource.Loading)
        val emptyTtml = LyricData(
            isVerbatim = true,
            source = LyricSource.AM,
            lyricLine = TTMLParser().parse(
                """<tt xmlns="http://www.w3.org/ns/ttml"><body><div/></body></tt>"""
            )
        )
        assertEquals(0, emptyTtml.lyricLine.lines.size)

        assertSame(loading, selectLyricUpdate(loading, emptyTtml, false, true))
        val finished = selectLyricUpdate(loading, emptyTtml, true, true)
        assertEquals(LyricSource.Empty, finished.source)
        assertEquals(1, finished.lyricLine.lines.size)
        assertEquals(createDefaultLyricData("没有歌词", source = LyricSource.Empty), finished)
    }

    @Test
    fun emptyResultsNeverReplaceUsableLyricsEvenAfterAllSourcesFinish() {
        val current = qqLyrics()
        val emptyParsedLines = LyricData(
            isVerbatim = true,
            source = LyricSource.AM,
            lyricLine = TTMLParser().parse(
                """<tt xmlns="http://www.w3.org/ns/ttml"><body><div/></body></tt>"""
            )
        )
        val candidates = listOf(
            createDefaultLyricData("No lyrics", source = LyricSource.Empty),
            createDefaultLyricData("Loading", source = LyricSource.Loading),
            emptyParsedLines
        )
        assertEquals(0, emptyParsedLines.lyricLine.lines.size)

        for (candidate in candidates) {
            assertSame(current, selectLyricUpdate(current, candidate, true, true))
        }
    }

    private fun qqLyrics(
        start: Int = 1_000,
        text: String = "Verse",
        translation: String? = null
    ): LyricData {
        val translatedLrc = translation?.let { "[00:0${start / 1000}.00]$it" }
        return LyricData(
            isVerbatim = true,
            source = LyricSource.QQMusic,
            lyricLine = QRCParser.parse("[$start,1000]$text($start,1000)", translatedLrc)
        )
    }

    private fun LyricData.withAlignment(alignment: KaraokeAlignment) = copy(
        lyricLine = lyricLine.copy(lines = lyricLine.lines.map {
            (it as KaraokeLine.MainKaraokeLine).copy(alignment = alignment)
        })
    )
}
