package com.ljyh.mei.playback

import android.content.ComponentName
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.ljyh.mei.R
import com.ljyh.mei.constants.NotificationAction
import com.ljyh.mei.constants.NotificationLeftActionKey
import com.ljyh.mei.constants.NotificationRightActionKey
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NotificationControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun buttonsUseSfIconsLabelsAndIndependentSlots() {
        assertButton(button(), R.drawable.ic_notification_sf_repeat, R.string.notification_mode_sequential)
        assertButton(button(repeat = Player.REPEAT_MODE_ALL), R.drawable.ic_notification_sf_repeat, R.string.notification_mode_repeat_all)
        assertButton(button(repeat = Player.REPEAT_MODE_ONE), R.drawable.ic_notification_sf_repeat_1, R.string.notification_mode_repeat_one)
        assertButton(button(shuffle = true), R.drawable.ic_notification_sf_shuffle, R.string.notification_mode_shuffle)
        assertButton(button(action = NotificationAction.Like), R.drawable.ic_notification_sf_heart, R.string.notification_add_like)
        assertButton(
            button(action = NotificationAction.Like, left = false, liked = true, enabled = false),
            R.drawable.ic_notification_sf_heart_fill, R.string.notification_remove_like,
            left = false, enabled = false,
        )
        assertButton(
            button(action = NotificationAction.FloatingLyrics),
            R.drawable.ic_notification_sf_text_rectangle, R.string.notification_enable_floating_lyrics,
            floatingLyricsEnabled = false,
        )
        assertButton(
            button(action = NotificationAction.FloatingLyrics, left = false, floatingLyricsEnabled = true),
            R.drawable.ic_notification_sf_text_rectangle_fill, R.string.notification_disable_floating_lyrics,
            left = false, floatingLyricsEnabled = true,
        )
        assertNull(button(action = NotificationAction.None))
        assertEquals(NotificationAction.Like, NotificationAction.fromPreference("like", NotificationAction.PlayMode))
        assertEquals(NotificationAction.PlayMode, NotificationAction.fromPreference("unknown", NotificationAction.PlayMode))
        assertEquals(NotificationAction.Like, NotificationAction.fromPreference(null, NotificationAction.Like))
    }

    @Test
    fun serviceUpdatesControllerPreferencesAndRestoresStoredChoices() = runBlocking {
        val keys = listOf(NotificationLeftActionKey, NotificationRightActionKey)
        val original = context.dataStore.data.first().let { preferences -> keys.associateWith { preferences[it] } }
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java)))
                .buildAsync()
        }
        try {
            val controller = future.get(10, TimeUnit.SECONDS)
            context.dataStore.edit {
                it[NotificationLeftActionKey] = NotificationAction.Like.name
                it[NotificationRightActionKey] = NotificationAction.PlayMode.name
            }
            awaitPreferences(controller) { buttons ->
                buttons.size == 2 &&
                    buttons[0].sessionCommand?.customAction == NOTIFICATION_LEFT_COMMAND &&
                    buttons[0].iconResId in setOf(R.drawable.ic_notification_sf_heart, R.drawable.ic_notification_sf_heart_fill) &&
                    buttons[1].sessionCommand?.customAction == NOTIFICATION_RIGHT_COMMAND &&
                    buttons[1].iconResId == when {
                        controller.shuffleModeEnabled -> R.drawable.ic_notification_sf_shuffle
                        controller.repeatMode == Player.REPEAT_MODE_ONE -> R.drawable.ic_notification_sf_repeat_1
                        else -> R.drawable.ic_notification_sf_repeat
                    }
            }
            context.dataStore.edit {
                it[NotificationLeftActionKey] = NotificationAction.FloatingLyrics.name
                it[NotificationRightActionKey] = NotificationAction.FloatingLyrics.name
            }
            awaitPreferences(controller) { buttons ->
                val floatingLyricsEnabled = buttons.firstOrNull()?.extras?.getBoolean(FLOATING_LYRICS_ENABLED) ?: false
                buttons.size == 2 && buttons.withIndex().all { (index, button) ->
                    val left = index == 0
                    button.sessionCommand?.customAction == (if (left) NOTIFICATION_LEFT_COMMAND else NOTIFICATION_RIGHT_COMMAND) &&
                        button.iconResId == (if (floatingLyricsEnabled) R.drawable.ic_notification_sf_text_rectangle_fill else R.drawable.ic_notification_sf_text_rectangle) &&
                        button.displayName.toString() == context.getString(
                            if (floatingLyricsEnabled) R.string.notification_disable_floating_lyrics else R.string.notification_enable_floating_lyrics,
                        ) &&
                        button.extras.getBoolean(FLOATING_LYRICS_BUTTON) &&
                        button.extras.containsKey(FLOATING_LYRICS_ENABLED) &&
                        button.extras.getBoolean(FLOATING_LYRICS_ENABLED) == floatingLyricsEnabled &&
                        button.slots.asList() == listOf(
                            if (left) CommandButton.SLOT_BACK_SECONDARY else CommandButton.SLOT_FORWARD_SECONDARY,
                            CommandButton.SLOT_OVERFLOW,
                        )
                }
            }
            context.dataStore.edit {
                it[NotificationLeftActionKey] = NotificationAction.None.name
                it[NotificationRightActionKey] = NotificationAction.None.name
            }
            awaitPreferences(controller) { it.isEmpty() }
        } finally {
            try {
                context.dataStore.edit { preferences ->
                    original.forEach { (key, value) ->
                        if (value == null) preferences.remove(key) else preferences[key] = value
                    }
                }
            } finally {
                instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
            }
        }
    }

    private fun button(
        action: NotificationAction = NotificationAction.PlayMode,
        left: Boolean = true,
        repeat: Int = Player.REPEAT_MODE_OFF,
        shuffle: Boolean = false,
        liked: Boolean = false,
        enabled: Boolean = true,
        floatingLyricsEnabled: Boolean = false,
    ) = notificationButton(context, action, left, repeat, shuffle, liked, enabled, floatingLyricsEnabled)

    private fun assertButton(
        button: CommandButton?,
        icon: Int,
        label: Int,
        left: Boolean = true,
        enabled: Boolean = true,
        floatingLyricsEnabled: Boolean? = null,
    ) {
        requireNotNull(button)
        assertEquals(CommandButton.ICON_UNDEFINED, button.icon)
        assertEquals(icon, button.iconResId)
        assertEquals(context.getString(label), button.displayName.toString())
        assertEquals(if (left) NOTIFICATION_LEFT_COMMAND else NOTIFICATION_RIGHT_COMMAND, button.sessionCommand?.customAction)
        assertEquals(
            listOf(if (left) CommandButton.SLOT_BACK_SECONDARY else CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW),
            button.slots.asList(),
        )
        assertEquals(enabled, button.isEnabled)
        if (floatingLyricsEnabled != null) {
            assertTrue(button.extras.getBoolean(FLOATING_LYRICS_BUTTON))
            assertTrue(button.extras.containsKey(FLOATING_LYRICS_ENABLED))
            assertEquals(floatingLyricsEnabled, button.extras.getBoolean(FLOATING_LYRICS_ENABLED))
        }
        assertTrue(context.resources.getDrawable(icon, context.theme).intrinsicWidth > 0)
    }

    private fun awaitPreferences(controller: MediaController, matches: (List<CommandButton>) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var matched = false
        var observed = emptyList<CommandButton>()
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                observed = controller.mediaButtonPreferences
                matched = matches(observed)
            }
            if (matched) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Unexpected media button preferences: ${observed.map { it.sessionCommand?.customAction to it.iconResId }}")
    }
}
