package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.isFullSourceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourcePolicyTest {
    @Test
    fun qualityFallbacksFollowTheRequestedQuality() {
        assertEquals(
            listOf("jymaster", "hires", "lossless", "exhigh", "standard"),
            playbackQualityFallbacks("JYMASTER"),
        )
        assertEquals(
            listOf("jyeffect", "lossless", "exhigh", "standard"),
            playbackQualityFallbacks("jyeffect"),
        )
        assertEquals(listOf("standard"), playbackQualityFallbacks(" standard "))
        assertEquals(
            listOf("dolby", "sky", "jyeffect", "hires", "lossless", "exhigh", "standard"),
            playbackQualityFallbacks("dolby"),
        )
    }

    @Test
    fun cacheKeysNormalizeQualityAndSeparateSourceRevisions() {
        assertEquals(
            playbackCacheKey("123", "EXHIGH", "ABCDEF", 10L),
            playbackCacheKey(" 123 ", "exhigh", "abcdef", 10L),
        )
        assertEquals(
            "meilox-media-v3:123:exhigh:abcdef",
            playbackCacheKey("123", "EXHIGH", "ABCDEF", 10L),
        )
        assertEquals(
            "meilox-media-v3:123:exhigh:size-1024",
            playbackCacheKey("123", "exhigh", "", 1_024L),
        )
        assertFalse(playbackCacheKey("123", "exhigh", "a", 10L) == "123")
        assertFalse(
            playbackCacheKey("123", "exhigh", "a", 10L) ==
                playbackCacheKey("123", "lossless", "a", 10L),
        )
        assertFalse(
            playbackCacheKey("123", "exhigh", "a", 10L) ==
                playbackCacheKey("123", "exhigh", "b", 10L),
        )
        assertTrue(
            playbackCacheKey("123", "exhigh", "a", 10L)
                .startsWith(playbackCacheKeyPrefix("123", "exhigh")),
        )
        assertTrue(
            playbackCacheKey("123", "exhigh", "a", 10L)
                .startsWith(playbackCacheKeyPrefix("123")),
        )
    }

    @Test
    fun effectiveQualityUsesTheServerLevelWhenFallbackIsUsed() {
        assertEquals("lossless", effectivePlaybackQuality("LOSSLESS", "jymaster"))
        assertEquals("exhigh", effectivePlaybackQuality(null, "EXHIGH"))
        assertEquals("dolby", effectivePlaybackQuality(" ", "DOLBY"))
    }

    @Test
    fun supportedPlaybackLevelsExcludeUnrecognizedServerFormats() {
        for (quality in MusicQuality.entries) {
            assertTrue(isSupportedPlaybackQuality(quality.text))
            assertTrue(isSupportedPlaybackQuality(" ${quality.text.uppercase()} "))
        }
        assertFalse(isSupportedPlaybackQuality(null))
        assertFalse(isSupportedPlaybackQuality(" "))
        assertFalse(isSupportedPlaybackQuality("vivid"))
        assertFalse(isSupportedPlaybackQuality("future-surround"))
    }

    @Test
    fun cachedQualityRequiresTheCurrentSongAndASupportedReportedLevel() {
        for (quality in MusicQuality.entries) {
            assertEquals(
                quality.text,
                cachedPlaybackQuality("123", playbackCacheKey("123", quality.text, "source", 10L)),
            )
        }
        assertNull(cachedPlaybackQuality("123", playbackCacheKey("123", "vivid", "source", 10L)))
        assertNull(cachedPlaybackQuality("123", playbackCacheKey("123", "future-surround", "source", 10L)))
        assertNull(cachedPlaybackQuality("123", playbackCacheKey("456", "dolby", "source", 10L)))
        assertNull(cachedPlaybackQuality("123", "meilox-media-v2:123:dolby:source"))
        assertNull(cachedPlaybackQuality("123", "meilox-media-v3:123:dolby:"))
        assertNull(cachedPlaybackQuality("123", "123"))
    }

    @Test
    fun outOfRangeReadRefreshesTheCurrentSource() {
        assertTrue(
            shouldRefreshPlaybackSource(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ),
        )
        assertFalse(
            shouldRefreshPlaybackSource(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
    }

    @Test
    fun fullSourceRequiresMatchingSuccessfulNonTrialSource() {
        val source = source()
        assertNotNull(SongUrl(200, listOf(source)).fullSourceFor("123"))
        assertNull(SongUrl(500, listOf(source)).fullSourceFor("123"))
        assertNull(SongUrl(200, listOf(source.copy(id = 456))).fullSourceFor("123"))
        assertNull(SongUrl(200, listOf(source.copy(code = 403))).fullSourceFor("123"))
        assertNull(SongUrl(200, listOf(source.copy(url = " "))).fullSourceFor("123"))
        assertNull(
            SongUrl(
                200,
                listOf(source.copy(freeTrialInfo = SongUrl.Data.FreeTrialInfo(end = 30))),
            ).fullSourceFor("123")
        )
    }

    @Test
    fun nonPaidFlagDoesNotRejectAFullSource() {
        assertTrue(source(payed = 0).isFullSourceFor("123"))
    }

    private fun source(
        id: Long = 123,
        code: Int = 200,
        url: String? = "https://example.invalid/song.flac",
        freeTrialInfo: SongUrl.Data.FreeTrialInfo? = null,
        payed: Int = 1,
    ) = SongUrl.Data(
        br = 320_000,
        canExtend = false,
        channelLayout = "stereo",
        closedGain = 0,
        closedPeak = 0,
        code = code,
        effectTypes = emptyList<Any>(),
        encodeType = "flac",
        expi = 7_200,
        fee = 0,
        flag = 0,
        freeTimeTrialPrivilege = SongUrl.Data.FreeTimeTrialPrivilege(
            remainTime = 0,
            resConsumable = false,
            type = 0,
            userConsumable = false,
        ),
        freeTrialInfo = freeTrialInfo,
        freeTrialPrivilege = SongUrl.Data.FreeTrialPrivilege(
            cannotListenReason = 0,
            freeLimitTagType = 0,
            listenType = 0,
            playReason = 0,
            resConsumable = false,
            userConsumable = false,
        ),
        gain = 0,
        id = id,
        level = "exhigh",
        levelConfuse = 0,
        md5 = "",
        message = "",
        musicId = id.toString(),
        payed = payed,
        peak = 0.0,
        podcastCtrp = 0,
        rightSource = 0,
        size = 1_024,
        time = 240_000,
        type = "flac",
        uf = 0,
        url = url,
        urlSource = 0,
    )
}
