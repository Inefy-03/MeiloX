package com.ljyh.mei.playback

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.lyric.YRCParser
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLyricTimelineTest {
    @Test
    fun expiredYrcCreditsDoNotOverlapTheTimedLyrics() {
        val parsed = YRCParser.parse(
            """
                {"t":-1000,"c":[{"tx":"Writer: Example"}]}
                {"t":-500,"c":[{"tx":"Composer: Example"}]}
                [0,1000](0,400,0)First (400,600,0)line
                [1000,1000](1000,1000,0)Second line
            """.trimIndent(),
            "[00:00.000]First translation\n[00:01.000]Second translation",
        )
        assertEquals(listOf(-1_000, -500, 0, 1_000), parsed.lines.map { it.start })

        val lines = metadata().toSystemLyricSong(
            LyricData(isVerbatim = true, source = LyricSource.NetEaseCloudMusic, lyricLine = parsed),
        ).lyrics.orEmpty()

        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertEquals(listOf(0L, 1_000L), lines.map { it.begin })
        assertEquals(listOf(1_000L, 2_000L), lines.map { it.end })
        assertEquals(listOf(1_000L, 1_000L), lines.map { it.duration })
        assertEquals(listOf("First translation", "Second translation"), lines.map { it.translation })
        val words = lines.first().words.orEmpty()
        assertEquals(listOf("First ", "line"), words.map { it.text })
        assertEquals(listOf(0L, 400L), words.map { it.begin })
        assertEquals(listOf(400L, 1_000L), words.map { it.end })
        assertEquals(listOf(400L, 600L), words.map { it.duration })
    }

    @Test
    fun aKaraokeLineCrossingZeroKeepsItsRemainingInterval() {
        val data = LyricData(
            isVerbatim = true,
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(
                lines = listOf(
                    KaraokeLine.MainKaraokeLine(
                        syllables = listOf(
                            KaraokeSyllable("First ", -200, 100),
                            KaraokeSyllable("line", 100, 600),
                        ),
                        translation = "Translation",
                        alignment = KaraokeAlignment.End,
                        start = -200,
                        end = 600,
                    ),
                ),
            ),
        )

        val line = metadata().toSystemLyricSong(data).lyrics.orEmpty().single()

        assertEquals("First line", line.text)
        assertEquals(0L, line.begin)
        assertEquals(600L, line.end)
        assertEquals(600L, line.duration)
        assertEquals("Translation", line.translation)
        assertTrue(line.isAlignedRight)
        assertEquals(listOf(0L, 100L), line.words.orEmpty().map { it.begin })
        assertEquals(listOf(100L, 600L), line.words.orEmpty().map { it.end })
        assertEquals(listOf(100L, 500L), line.words.orEmpty().map { it.duration })
    }

    @Test
    fun lineLyricsUseTheNextStartThenSongDurationForMissingEnds() {
        val data = LyricData(
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(
                lines = listOf(
                    SyncedLine("Opening line", null, 0, 0),
                    SyncedLine("First line", "Translation", 1_000, 1_000),
                    SyncedLine("Second line", null, 2_000, 2_000),
                ),
            ),
        )

        val song = metadata(duration = 5_000).toSystemLyricSong(data)
        val lines = song.lyrics.orEmpty()

        assertEquals(5_000L, song.duration)
        assertEquals(listOf(0L, 1_000L, 2_000L), lines.map { it.begin })
        assertEquals(listOf(1_000L, 2_000L, 5_000L), lines.map { it.end })
        assertEquals(listOf(1_000L, 1_000L, 3_000L), lines.map { it.duration })
        assertEquals("Translation", lines[1].translation)
        lines.forEach { assertNull(it.words) }
        assertEquals(5_000L, metadata(duration = 0).toSystemLyricSong(data).lyrics.orEmpty().last().end)
    }

    @Test
    fun placeholdersAndPureMusicDoNotExportDisplayTextAsLyrics() {
        val data = LyricData(
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(lines = listOf(SyncedLine("Display text", null, 0, 1_000))),
        )

        val placeholders = listOf(
            null,
            data.copy(source = LyricSource.Empty),
            data.copy(source = LyricSource.Loading),
            data.copy(isPureMusic = true),
        )

        placeholders.forEach { assertTrue(metadata().toSystemLyricSong(it).lyrics.orEmpty().isEmpty()) }
    }

    private fun metadata(duration: Long = 199_090) = MediaMetadata(
        id = 48,
        title = "Example song",
        coverUrl = "",
        artists = listOf(MediaMetadata.Artist(id = 1, name = "Example artist")),
        duration = duration,
        album = MediaMetadata.Album(id = 1, title = "Example album"),
    )
}
