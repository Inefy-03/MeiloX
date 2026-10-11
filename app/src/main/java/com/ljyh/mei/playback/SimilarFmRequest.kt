package com.ljyh.mei.playback

internal const val SIMILAR_FM_TITLE = "相似歌曲 FM"

@ConsistentCopyVisibility
internal data class SimilarFmRequest private constructor(val sourceIds: List<String>) {
    fun body(unplayedSongIds: List<String> = emptyList()): Map<String, String> = linkedMapOf(
        "limit" to "3",
        "sourceIds" to sourceIds.joinToString(prefix = "[", postfix = "]", separator = ","),
        "sourceType" to "song",
        "mode" to "SIMILAR",
        // The official client excludes its in-memory queue from the database backlog.
        // Mei has no separate FM backlog, so live requests use the empty array.
        "unplaySonIds" to unplayedSongIds.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }
            .joinToString(prefix = "[", postfix = "]", separator = ","),
    )

    companion object {
        fun fromSongIds(songIds: List<String?>): SimilarFmRequest? {
            if (songIds.isEmpty()) return null
            val ids = songIds.map { it?.toLongOrNull()?.takeIf { id -> id > 0 } ?: return null }
            return SimilarFmRequest(ids.map(Long::toString))
        }
    }
}
