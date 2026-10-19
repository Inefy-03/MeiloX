package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.model.synced.UncheckedSyncedLine

/** Derive display timings from the original snapshot without changing its metadata. */
internal fun LyricData.withTimingOffset(offsetMs: Int): LyricData {
    if (offsetMs == 0) return this
    return copy(lyricLine = lyricLine.copy(lines = lyricLine.lines.map { it.shiftTiming(offsetMs) }))
}

private fun ISyncedLine.shiftTiming(offsetMs: Int): ISyncedLine {
    return when (this) {
        is SyncedLine -> copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
        is UncheckedSyncedLine -> copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
        is KaraokeLine.MainKaraokeLine -> copy(
            start = start.shiftTiming(offsetMs),
            end = end.shiftTiming(offsetMs),
            syllables = syllables.map { it.shiftTiming(offsetMs) },
            accompanimentLines = accompanimentLines?.map { it.shiftTiming(offsetMs) },
        )
        is KaraokeLine.AccompanimentKaraokeLine -> shiftTiming(offsetMs)
        else -> throw IllegalArgumentException("Unsupported lyric line type: ${javaClass.name}")
    }
}

private fun KaraokeLine.AccompanimentKaraokeLine.shiftTiming(
    offsetMs: Int,
): KaraokeLine.AccompanimentKaraokeLine {
    return copy(
        start = start.shiftTiming(offsetMs),
        end = end.shiftTiming(offsetMs),
        syllables = syllables.map { it.shiftTiming(offsetMs) },
    )
}

private fun KaraokeSyllable.shiftTiming(offsetMs: Int): KaraokeSyllable {
    return copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
}

private fun Int.shiftTiming(offsetMs: Int): Int {
    val shifted = toLong() + offsetMs
    // Reject the entire derivation rather than clipping individual timestamps and durations.
    require(shifted in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Lyric timing offset exceeds the supported range"
    }
    return shifted.toInt()
}
