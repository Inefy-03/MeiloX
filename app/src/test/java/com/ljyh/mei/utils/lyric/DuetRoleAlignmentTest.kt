package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.qq.u.emptyData
import com.ljyh.mei.ui.model.LyricSourceData
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuetRoleAlignmentTest {
    private val detector = DuetDetector()

    @Test
    fun productionCreditsFromSong521482952DoNotDefineSingers() {
        val labels = listOf("演唱", "作词", "作曲", "编曲", "混音", "绘图", "题字", "出品")
        val lrc = labels.mapIndexed { index, label ->
            "[00:0${index + 1}.00]$label: Credit"
        }.joinToString("\n")

        assertFalse(detector.isDuetLikely(lrc))
        val result = detector.mergeWithDuet(netease(lrc), qq(1_000, 2_000, 12_000))!!
        assertTrue(result.lyricLine.lines.all {
            (it as KaraokeLine).alignment == KaraokeAlignment.Start
        })
    }

    @Test
    fun englishAndCombinedCreditLabelsDoNotDefineSingers() {
        val lrc = """
            [00:01.00]Lyrics: Credit
            [00:02.00]【Composer】Credit
            [00:03.00]【Written By】Credit
            [00:04.00]【Arrangement】Credit
            [00:05.00]词/曲: Credit
            [00:06.00]【Lyrics & Music】Credit
        """.trimIndent()

        assertFalse(detector.isDuetLikely(lrc))
    }

    @Test
    fun standaloneEnglishCreditsDoNotDefineSingers() {
        val lrc = """
            [00:01.00]Lyrics:
            [00:01.50]Credit
            [00:02.00]Music:
            [00:02.50]Credit
        """.trimIndent()

        assertFalse(detector.isDuetLikely(lrc))
    }

    @Test
    fun creditsDoNotAffectRealSingerAlignment() {
        val lrc = """
            [00:01.00]作词: Credit
            [00:02.00]作曲: Credit
            [00:10.00]Alice: First
            [00:20.00]Bob: Second
        """.trimIndent()

        assertTrue(detector.isDuetLikely(lrc))
        val result = detector.mergeWithDuet(netease(lrc), qq(1_000, 2_000, 10_000, 20_000))!!
        assertEquals(
            listOf(
                KaraokeAlignment.Unspecified, KaraokeAlignment.Unspecified,
                KaraokeAlignment.Start, KaraokeAlignment.End
            ),
            result.lyricLine.lines.map { (it as KaraokeLine).alignment }
        )
    }

    @Test
    fun chineseSingerRolesStillDefineSeparateVoices() {
        val lrc = """
            [00:10.00]男: First
            [00:20.00]女: Second
        """.trimIndent()

        assertTrue(detector.isDuetLikely(lrc))
        val result = detector.mergeWithDuet(netease(lrc), qq(10_000, 20_000))!!
        assertEquals(
            listOf(KaraokeAlignment.Start, KaraokeAlignment.End),
            result.lyricLine.lines.map { (it as KaraokeLine).alignment }
        )
    }

    @Test
    fun qqAlignmentUsesOriginalRoleTimestampsAndKeepsTheFinalSinger() {
        val lrc = """
            [ti:Example]
            [ar:Example]
            [00:10.00]Alice: First
            [00:20.00]Bob: Second
            [00:27.00]Final line
        """.trimIndent()
        val qqLrc = """
            [00:00.00]Credit
            [00:10.00]First
            [00:20.00]Second
            [00:27.00]Final line
        """.trimIndent()
        val source = qq(10_000, 20_000, 27_000)

        for (fallback in listOf(null, qqLrc)) {
            val result = detector.mergeWithDuet(
                netease(lrc), source.copy(lrcContent = fallback)
            )!!
            assertEquals(
                listOf(KaraokeAlignment.Start, KaraokeAlignment.End, KaraokeAlignment.End),
                result.lyricLine.lines.map { (it as KaraokeLine).alignment }
            )
        }
    }

    private fun netease(lrc: String) = LyricSourceData.NetEase(
        Lyric(
            code = 200, klyric = null, lrc = Lyric.Lrc(lrc, 1), qfy = false,
            romalrc = null, sfy = false, sgc = false, tlyric = null, ytlrc = null,
            yrc = null, pureMusic = false
        )
    )

    private fun qq(vararg timestamps: Int) = LyricSourceData.QQMusic(
        lyric = emptyData.copy(
            lyric = timestamps.joinToString("\n") { "[$it,1000]Line($it,1000)" },
            qrcT = 1
        ),
        isQRC = true
    )
}
