package com.openminis.app.tools

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.MinisNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

/** MediaSession controls gated by the existing user-granted notification listener. */
object AndroidMediaSessionOps {

    private fun sessions(context: Context): Pair<List<MediaController>?, ToolExecutionResult?> {
        if (!MinisNotificationListenerService.isEnabled(context)) {
            return null to ToolExecutionResult(
                JSONObject()
                    .put("error", "notification_listener_required")
                    .put("message", "Enable Minis Notification Access before reading or controlling other apps' media sessions.")
                    .put("settings_action", MinisNotificationListenerService.SETTINGS_ACTION)
                    .toString(2),
                false,
            )
        }
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MinisNotificationListenerService::class.java)
            manager.getActiveSessions(component).orEmpty() to null
        } catch (t: Throwable) {
            null to ToolExecutionResult("Error: media_session_unavailable: ${t.message}", false)
        }
    }

    fun info(context: Context): ToolExecutionResult {
        val (controllers, error) = sessions(context)
        error?.let { return it }
        val active = controllers.orEmpty().filter { it.isMeaningful() }
        val items = JSONArray()
        active.forEach { items.put(it.toJson()) }
        return ToolExecutionResult(
            JSONObject()
                .put("hasActiveMedia", active.isNotEmpty())
                .put("sessions", items)
                .toString(2),
            true,
        )
    }

    fun control(context: Context, action: String, packageName: String?, seekPositionMs: Long?, volumePercent: Int?): ToolExecutionResult {
        if (action == "volume") {
            val percent = volumePercent ?: return ToolExecutionResult("Error: volume_percent is required for volume", false)
            return try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val value = (max * percent.coerceIn(0, 100) / 100f).toInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                ToolExecutionResult(JSONObject().put("volume_percent", percent.coerceIn(0, 100)).put("stream_volume", value).put("stream_max", max).toString(), true)
            } catch (t: Throwable) {
                ToolExecutionResult("Error: media_volume failed: ${t.message}", false)
            }
        }

        val (controllers, error) = sessions(context)
        error?.let { return it }
        val controller = controllers.orEmpty()
            .filter { it.isMeaningful() && (packageName.isNullOrBlank() || it.packageName == packageName) }
            .let { matches -> matches.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: matches.firstOrNull() }
            ?: return ToolExecutionResult(
                JSONObject().put("error", "no_active_media").put("package_name", packageName ?: JSONObject.NULL).toString(),
                false,
            )
        return try {
            val controls = controller.transportControls
            when (action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> if (controller.playbackState?.state in PLAYING_STATES) controls.pause() else controls.play()
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
                "fast_forward" -> controls.fastForward()
                "rewind" -> controls.rewind()
                "seek" -> controls.seekTo(seekPositionMs ?: return ToolExecutionResult("Error: seek_position_ms is required for seek", false))
                else -> return ToolExecutionResult("Error: invalid media action: $action", false)
            }
            ToolExecutionResult(JSONObject().put("accepted", true).put("action", action).put("session", controller.toJson()).toString(2), true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: control_media failed: ${t.message}", false)
        }
    }

    private fun MediaController.isMeaningful(): Boolean {
        val state = playbackState?.state
        return state != null && state != PlaybackState.STATE_NONE ||
            !metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
    }

    private fun MediaController.toJson(): JSONObject {
        val metadata = metadata
        val state = playbackState
        return JSONObject()
            .put("package_name", packageName)
            .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("duration_ms", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: JSONObject.NULL)
            .put("playback_state", state?.state.toPlaybackState())
            .put("position_ms", state?.position ?: JSONObject.NULL)
            .put("actions", state?.actions ?: 0)
    }

    private fun Int?.toPlaybackState(): String = when (this) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackState.STATE_STOPPED -> "stopped"
        else -> "none"
    }

    private val PLAYING_STATES = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
    )
}

class AndroidMediaInfoHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.media.info",
        description = "Get active external MediaSession metadata and playback state. Requires Notification Access.",
        parameters = emptyMap(),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidMediaSessionOps.info(context)
}

class AndroidMediaControlHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.media.control",
        description = "Control an active external MediaSession or music volume. Requires Notification Access except volume.",
        parameters = mapOf(
            "action" to AgentToolParam("string", "play/pause/toggle/next/previous/fast_forward/rewind/seek/volume", listOf("play", "pause", "toggle", "next", "previous", "fast_forward", "rewind", "seek", "volume")),
            "package_name" to AgentToolParam("string", "Optional target media app package"),
            "seek_position_ms" to AgentToolParam("integer", "Seek destination for action=seek"),
            "volume_percent" to AgentToolParam("integer", "0..100 for action=volume"),
        ),
        required = listOf("action"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidMediaSessionOps.control(
            context,
            a.optString("action"),
            a.optString("package_name").ifBlank { null },
            if (a.has("seek_position_ms")) a.optLong("seek_position_ms") else null,
            if (a.has("volume_percent")) a.optInt("volume_percent") else null,
        )
    }
}
