package com.ljyh.mei.constants

import androidx.annotation.StringRes
import com.ljyh.mei.R

enum class NotificationAction(
    @param:StringRes val labelRes: Int,
    val systemName: String,
) {
    PlayMode(R.string.player_action_play_mode, "repeat"),
    Like(R.string.notification_action_like, "heart"),
    FloatingLyrics(R.string.more_action_floating_lyrics, "text.rectangle"),
    None(R.string.notification_action_none, "minus");

    companion object {
        fun fromPreference(value: String?, default: NotificationAction): NotificationAction =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
