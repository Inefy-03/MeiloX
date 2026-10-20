package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.Artist
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.model.synced.UncheckedSyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun timingOffsetsShiftMainAndBackgroundVocalsWithoutChangingMetadata() {
        val original = timingLyrics()
        val originalMain = original.lyricLine.lines[0] as KaraokeLine.MainKaraokeLine
        val originalBackground = originalMain.accompanimentLines!!.single()
        val originalStandalone = original.lyricLine.lines[1] as KaraokeLine.AccompanimentKaraokeLine
        val originalSynced = original.lyricLine.lines[2] as SyncedLine
        val originalUnchecked = original.lyricLine.lines[3] as UncheckedSyncedLine

        for (offset in listOf(-500, 500)) {
            val shifted = original.withTimingOffset(offset)
            val main = shifted.lyricLine.lines[0] as KaraokeLine.MainKaraokeLine
            val background = main.accompanimentLines!!.single()
            val standalone = shifted.lyricLine.lines[1] as KaraokeLine.AccompanimentKaraokeLine
            val synced = shifted.lyricLine.lines[2] as SyncedLine
            val unchecked = shifted.lyricLine.lines[3] as UncheckedSyncedLine

            assertEquals(original.copy(lyricLine = shifted.lyricLine), shifted)
            assertEquals(original.lyricLine.copy(lines = shifted.lyricLine.lines), shifted.lyricLine)
            assertEquals(originalMain.copy(
                start = main.start, end = main.end,
                syllables = main.syllables, accompanimentLines = main.accompanimentLines,
            ), main)
            assertEquals(originalBackground.copy(
                start = background.start, end = background.end, syllables = background.syllables,
            ), background)
            assertEquals(originalStandalone.copy(
                start = standalone.start, end = standalone.end, syllables = standalone.syllables,
            ), standalone)
            assertEquals(originalSynced.copy(start = synced.start, end = synced.end), synced)

            assertEquals(listOf(250 + offset, 1_250 + offset), listOf(main.start, main.end))
            assertEquals(listOf(400 + offset, 900 + offset), listOf(background.start, background.end))
            assertEquals(listOf(1_400 + offset, 1_800 + offset), listOf(standalone.start, standalone.end))
            assertEquals(listOf(1_800 + offset, 2_400 + offset), listOf(synced.start, synced.end))
            for ((before, after) in listOf(
                originalMain to main, originalBackground to background, originalStandalone to standalone,
            )) {
                assertEquals(before.duration, after.duration)
                assertEquals(before.syllables.map {
                    it.copy(start = it.start + offset, end = it.end + offset)
                }, after.syllables)
            }
            assertEquals(originalSynced.duration, synced.duration)
            assertEquals(originalUnchecked.copy(start = 2_400 + offset, end = 2_300 + offset), unchecked)
            assertEquals(originalUnchecked.duration, unchecked.duration)
        }
    }

    @Test
    fun zeroTimingOffsetKeepsTheOriginalObject() {
        val original = timingLyrics()
        assertSame(original, original.withTimingOffset(0))
    }

    @Test
    fun timingAdjustmentsLeaveTheOriginalSnapshotAvailableForEverySelection() {
        val original = timingLyrics()
        val unchangedSnapshot = timingLyrics()

        val delayed = original.withTimingOffset(500)
        val advanced = original.withTimingOffset(-500)

        assertEquals(unchangedSnapshot, original)
        assertEquals(delayed, original.withTimingOffset(500))
        assertEquals(advanced, original.withTimingOffset(-500))
        assertEquals(original, delayed.withTimingOffset(-500))
        assertSame(original, original.withTimingOffset(0))
        assertNotSame(original.lyricLine, delayed.lyricLine)
    }

    @Test
    fun timingOffsetsRejectOverflowInsteadOfClippingDurations() {
        val original = timingLyrics()
        val main = original.lyricLine.lines[0] as KaraokeLine.MainKaraokeLine
        val background = main.accompanimentLines!!.single()
        val overflowingBackground = background.copy(syllables = listOf(
            KaraokeSyllable("Background", Int.MAX_VALUE - 100, Int.MAX_VALUE),
        ))
        val cases = listOf(
            SyncedLine("End", null, Int.MAX_VALUE - 100, Int.MAX_VALUE) to 500,
            SyncedLine("Start", null, Int.MIN_VALUE, Int.MIN_VALUE + 100) to -500,
            main.copy(accompanimentLines = listOf(overflowingBackground)) to 500,
        )

        for ((line, offset) in cases) {
            val source = original.copy(lyricLine = original.lyricLine.copy(lines = listOf(line)))
            assertThrows(IllegalArgumentException::class.java) { source.withTimingOffset(offset) }
        }
        assertEquals(timingLyrics(), original)
    }

    private fun timingLyrics(): LyricData {
        val background = KaraokeLine.AccompanimentKaraokeLine(
            syllables = listOf(KaraokeSyllable("Harmony", 400, 900, phonetic = "harmony")),
            translation = "Background translation",
            alignment = KaraokeAlignment.End,
            start = 400,
            end = 900,
            phonetic = "Background phonetic",
        )
        val main = KaraokeLine.MainKaraokeLine(
            syllables = listOf(
                KaraokeSyllable("Main", 250, 700, phonetic = "main"),
                KaraokeSyllable(" vocal", 700, 1_250, phonetic = "vocal"),
            ),
            translation = "Main translation",
            alignment = KaraokeAlignment.Start,
            start = 250,
            end = 1_250,
            phonetic = "Main phonetic",
            accompanimentLines = listOf(background),
        )
        return LyricData(
            isVerbatim = true,
            source = LyricSource.AM,
            lyricLine = SyncedLyrics(
                lines = listOf(
                    main,
                    background.copy(
                        syllables = listOf(KaraokeSyllable("Echo", 1_400, 1_800, phonetic = "echo")),
                        start = 1_400,
                        end = 1_800,
                    ),
                    SyncedLine("Plain line", "Line translation", 1_800, 2_400),
                    UncheckedSyncedLine("Unchecked line", null, 2_400, 2_300),
                ),
                title = "Title",
                id = "lyrics-id",
                artists = listOf(Artist(type = "person", name = "Singer")),
            ),
        )
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
