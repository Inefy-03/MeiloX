package com.ljyh.mei.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.google.gson.Gson
import com.ljyh.mei.constants.RestorePlaybackPositionKey
import com.ljyh.mei.constants.RestorePlaybackStateKey
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackShuffleSnapshotTest {
    private val gson = Gson()

    @Test
    fun versionTwoRoundTripKeepsOrderSourceCurrentIndexAndProgress() {
        val original = PlaybackSnapshot(
            items = listOf("11", "22", "11").map { PlaybackItemSnapshot(mediaId = it) },
            currentIndex = 2,
            positionMs = 12345L,
            shuffleModeEnabled = true,
            shuffleOrder = listOf(2, 0, 1),
            playlistSource = PlaylistQueueSource(123L, "server-alg"),
        )
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertEquals(original, restored)
        assertTrue(restored.shuffleOrder!!.isPlaybackPermutation(restored.items.size))
    }

    @Test
    fun legacySnapshotKeepsQueueWithoutInventingAServerOrder() {
        val restored = gson.fromJson(
            """{"schemaVersion":1,"items":[{"mediaId":"11"}],"currentIndex":0,
                "positionMs":7000,"shuffleModeEnabled":true,"sourceType":"queue"}""",
            PlaybackSnapshot::class.java,
        )
        assertEquals(1, restored.schemaVersion)
        assertEquals("11", restored.items.single().mediaId)
        assertEquals(7000L, restored.positionMs)
        assertTrue(restored.shuffleModeEnabled)
        assertNull(restored.shuffleOrder)
        assertNull(restored.playlistSource)
    }

    @Test
    fun malformedPermutationIsRejectedWithoutDiscardingDecodedItems() {
        listOf("[0,0]", "[0,2]", "[-1,0]", "[0]", "[0,null]").forEach { order ->
            val restored = gson.fromJson(
                """{"schemaVersion":2,"items":[{"mediaId":"11"},{"mediaId":"22"}],"shuffleOrder":$order}""",
                PlaybackSnapshot::class.java,
            )
            assertEquals(2, restored.items.size)
            assertFalse(restored.shuffleOrder!!.isPlaybackPermutation(restored.items.size))
        }
    }

    @Test
    fun nonShuffleSnapshotStillRetainsItsStoredPermutation() {
        val original = PlaybackSnapshot(shuffleModeEnabled = false, shuffleOrder = listOf(1, 0))
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertFalse(restored.shuffleModeEnabled)
        assertEquals(listOf(1, 0), restored.shuffleOrder)
    }

    @Test
    fun fmSnapshotRetainsItsSourceType() {
        val original = PlaybackSnapshot(sourceType = PlaybackSnapshot.SOURCE_PERSONAL_FM)
        assertTrue(gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java).isFmMode)
    }

    @Test
    fun similarFmRoundTripKeepsTheOriginalSeedsForRefill() {
        val original = PlaybackSnapshot(
            sourceType = PlaybackSnapshot.SOURCE_PERSONAL_FM,
            similarFmSourceIds = listOf("42", "19", "7"),
        )
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertTrue(restored.isFmMode)
        assertEquals(listOf("42", "19", "7"), restored.similarFmSourceIds)
        assertEquals("[42,19,7]", SimilarFmRequest.fromSongIds(restored.similarFmSourceIds!!)!!.body()["sourceIds"])
    }

    @Test
    fun oldPersonalFmSnapshotDoesNotInventSimilarSources() {
        val restored = gson.fromJson(
            """{"schemaVersion":2,"sourceType":"personal_fm","items":[]}""",
            PlaybackSnapshot::class.java,
        )
        assertTrue(restored.isFmMode)
        assertNull(restored.similarFmSourceIds)
    }

    @Test
    fun restorePreferencesIndependentlyControlProgressAndStateAfterTheLatestCheckpoint() {
        val saved = PlaybackSnapshot(
            savedAtEpochMs = 100L,
            items = listOf("11", "22").map { PlaybackItemSnapshot(mediaId = it) },
            shuffleOrder = listOf(1, 0),
            playlistSource = PlaylistQueueSource(123L, "server-alg"),
            queueTitle = "Saved queue",
        )
        val latest = saved.withCheckpoint(PlaybackCheckpoint(100L, 200L, 1, 12345L, 2, true, true))

        listOf(false, true).forEach { restorePosition ->
            listOf(false, true).forEach { restoreState ->
                val restored = latest.withRestorePreferences(preferencesOf(
                    RestorePlaybackPositionKey to restorePosition,
                    RestorePlaybackStateKey to restoreState,
                ))
                assertEquals(if (restorePosition) 12345L else 0L, restored.positionMs)
                assertEquals(restoreState, restored.playWhenReady)
                assertEquals(latest, restored.copy(positionMs = latest.positionMs, playWhenReady = true))
            }
        }
        assertEquals(12345L, latest.positionMs)
        assertTrue(latest.playWhenReady)
    }

    @Test
    fun missingRestorePreferencesKeepExistingBehaviorAndNeverStartAPausedSnapshot() {
        val playing = PlaybackSnapshot(positionMs = 7000L, playWhenReady = true)
        assertEquals(playing, playing.withRestorePreferences(emptyPreferences()))
        val paused = playing.copy(playWhenReady = false)
        assertEquals(paused, paused.withRestorePreferences(emptyPreferences()))
        assertEquals(paused, paused.withRestorePreferences(preferencesOf(RestorePlaybackStateKey to true)))
        assertEquals(0L, paused.copy(positionMs = -1L).withRestorePreferences(emptyPreferences()).positionMs)
    }
}
