package com.ljyh.mei.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.media3.session.MediaSession
import androidx.media3.session.PlatformLyricsSessionAccess
import com.ljyh.mei.R
import timber.log.Timber

internal const val COLOR_OS_TRANSLATION_ACTION =
    "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
private const val TRANSLATION_ACTION_OWNER = "com.ljyh.mei.playback.ColorOsLyricsPublisher"
private const val LYRIC_INFO = "lyricInfo"
private const val MAX_METADATA_BYTES = 512 * 1024

/** Overlays lyrics on the host session; only OPlus controls receive the Bridge display action. */
internal class ColorOsLyricsPublisher(
    context: Context,
    session: MediaSession,
    private val translationActionEnabled: Boolean = Build.MANUFACTURER.let { manufacturer ->
        listOf("OPPO", "OnePlus", "realme", "OPlus").any { manufacturer.equals(it, ignoreCase = true) }
    },
) {
    private val platformSession = PlatformLyricsSessionAccess.get(session)
    private val controller = platformSession.controller
    private val translationAction = PlaybackState.CustomAction.Builder(
        COLOR_OS_TRANSLATION_ACTION,
        context.getString(R.string.lyrics_translation),
        context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_manage,
    ).setExtras(Bundle().apply { putBoolean(TRANSLATION_ACTION_OWNER, true) }).build()
    private var mediaId: String? = null
    private var payload: String? = null
    private var hasTranslation = false
    private var lastPublished: String? = null
    private var released = false
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishMetadata()
            publishPlaybackState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = publishPlaybackState()
    }

    init {
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun setLyrics(mediaId: String?, payload: String?, hasTranslation: Boolean = false) {
        if (released) return
        this.mediaId = mediaId
        this.payload = payload
        this.hasTranslation = hasTranslation
        publishMetadata()
        publishPlaybackState()
    }

    private fun currentPayload(metadata: MediaMetadata): String? = payload.takeIf {
        mediaId != null && metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == mediaId
    }

    private fun publishMetadata() {
        if (released) return
        try {
            val original = controller.metadata ?: return
            val current = original.getString(LYRIC_INFO)
            val desired = currentPayload(original)
            if (current == desired) return
            // Remove only data written by this publisher, never another metadata owner's field.
            if (desired == null && (lastPublished == null || current != lastPublished)) return
            val candidate = copyMetadata(original).apply {
                if (desired != null) putString(LYRIC_INFO, desired)
            }.build()
            val parcel = Parcel.obtain()
            val fits = try {
                candidate.writeToParcel(parcel, 0)
                parcel.dataSize() <= MAX_METADATA_BYTES
            } finally {
                parcel.recycle()
            }
            if (desired != null && !fits) return
            platformSession.setMetadata(candidate)
            lastPublished = desired
        } catch (error: Exception) {
            Timber.w(error, "ColorOS lyric metadata publication failed")
        }
    }

    private fun publishPlaybackState() {
        if (released) return
        try {
            val original = controller.playbackState ?: return
            val metadata = controller.metadata
            val showTranslation = translationActionEnabled && hasTranslation && metadata != null &&
                currentPayload(metadata)?.let { it == metadata.getString(LYRIC_INFO) } == true
            val actions = original.customActions.orEmpty()
            val owned = actions.filter {
                it.action == COLOR_OS_TRANSLATION_ACTION && it.extras?.getBoolean(TRANSLATION_ACTION_OWNER) == true
            }
            if (showTranslation && actions.any { it.action == COLOR_OS_TRANSLATION_ACTION }) return
            if (!showTranslation && owned.isEmpty()) return
            val builder = PlaybackState.Builder()
                .setState(original.state, original.position, original.playbackSpeed, original.lastPositionUpdateTime)
                .setBufferedPosition(original.bufferedPosition)
                .setActions(original.actions)
                .setErrorMessage(original.errorMessage)
                .setActiveQueueItemId(original.activeQueueItemId)
                .setExtras(original.extras)
            actions.filterNot { it in owned }.forEach(builder::addCustomAction)
            if (showTranslation) builder.addCustomAction(translationAction)
            platformSession.setPlaybackState(builder.build())
        } catch (error: Exception) {
            Timber.w(error, "ColorOS translation action publication failed")
        }
    }

    fun release() {
        if (released) return
        controller.unregisterCallback(callback)
        setLyrics(null, null)
        released = true
    }

    @Suppress("DEPRECATION")
    private fun copyMetadata(original: MediaMetadata): MediaMetadata.Builder {
        val parcel = Parcel.obtain()
        val values = try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(MediaMetadata::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
        // A fresh typed builder avoids ColorOS losing lazily parcelled bitmap fields.
        // Unknown keys still retain their original public MediaMetadata value types.
        return MediaMetadata.Builder().apply {
            values.keySet().forEach { key ->
                if (key == LYRIC_INFO) return@forEach
                when (val value = values.get(key)) {
                    is Bitmap -> putBitmap(key, value)
                    is Rating -> putRating(key, value)
                    is Long -> putLong(key, value)
                    is CharSequence -> putText(key, value)
                    null -> when (key) {
                        MediaMetadata.METADATA_KEY_ART,
                        MediaMetadata.METADATA_KEY_ALBUM_ART,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON -> putBitmap(key, null)
                        MediaMetadata.METADATA_KEY_RATING,
                        MediaMetadata.METADATA_KEY_USER_RATING -> putRating(key, null)
                        else -> putText(key, null)
                    }
                    // Leave unusual vendor metadata untouched instead of dropping its fields.
                    else -> error("Unsupported media metadata value type")
                }
            }
        }
    }
}
