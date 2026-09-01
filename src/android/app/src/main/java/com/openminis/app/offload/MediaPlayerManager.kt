package com.openminis.app.offload

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import java.io.File
import java.security.MessageDigest

/**
 * Manages multiple concurrent MediaPlayer sessions keyed by session ID.
 * Supports audio playback with play/pause/resume/seek/stop/status operations.
 * SAF mount paths are resolved through RuntimePathRegistry. Canonical guest
 * files are staged through minisd into app cache before playback.
 */
object MediaPlayerManager {

    private const val TAG = "MediaPlayerManager"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private data class Session(
        val player: MediaPlayer,
        val filePath: String,
        val mediaType: String, // "audio" or "video"
        var state: PlayerState = PlayerState.IDLE,
    )

    enum class PlayerState { IDLE, PLAYING, PAUSED, STOPPED }

    private val sessions = mutableMapOf<String, Session>()

    private val audioExtensions = setOf(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus", "amr", "mid", "midi",
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "ts", "m4v",
    )

    /**
     * Start playback of a file in a new or existing session.
     * If the session already exists, the previous player is released first.
     */
    fun play(sessionId: String, filePath: String): String {
        // Stop existing session if any
        sessions[sessionId]?.let { existing ->
            releaseSession(existing)
        }

        val hostFile = when {
            isExternalMountPath(filePath) -> RuntimePathRegistry.resolveHostPath(filePath)
                ?: return "Error: cannot resolve mounted path '$filePath'"
            isCanonicalGuestPath(filePath) -> {
                val context = appContext
                    ?: return "Error: media player is not initialized"
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("$sessionId:$filePath".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val safeName = filePath.substringAfterLast('/')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "audio" }
                val staged = File(File(context.cacheDir, "media-player"), "$digest-$safeName")
                try {
                    WorkspaceFileClient.readToFileBlocking(sessionId, filePath, staged)
                    staged
                } catch (error: Throwable) {
                    return "Error: cannot read guest media '$filePath': ${error.message}"
                }
            }
            else -> return "Error: unsupported media path '$filePath'"
        }

        if (!hostFile.exists()) {
            return "Error: file not found at '$filePath' (resolved: ${hostFile.absolutePath})"
        }

        val ext = hostFile.extension.lowercase()
        val mediaType = when {
            audioExtensions.contains(ext) -> "audio"
            videoExtensions.contains(ext) -> "video"
            else -> "audio" // default to audio for unknown extensions
        }

        if (mediaType == "video") {
            return "Error: video playback requires a SurfaceView and is not supported in headless mode. " +
                "Only audio files can be played."
        }

        return try {
            val player = MediaPlayer().apply {
                setDataSource(hostFile.absolutePath)
                prepare()
                start()
            }

            val session = Session(
                player = player,
                filePath = filePath,
                mediaType = mediaType,
                state = PlayerState.PLAYING,
            )

            player.setOnCompletionListener {
                Log.d(TAG, "Playback completed: session=$sessionId")
                session.state = PlayerState.STOPPED
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: session=$sessionId what=$what extra=$extra")
                session.state = PlayerState.STOPPED
                true
            }

            sessions[sessionId] = session

            val durationMs = player.duration
            val durationStr = formatDuration(durationMs)
            "Playing '$filePath' (session=$sessionId, type=$mediaType, duration=$durationStr)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play $filePath", e)
            "Error: failed to play '$filePath': ${e.message}"
        }
    }

    fun pause(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        if (session.state != PlayerState.PLAYING) {
            return "Error: session '$sessionId' is not playing (state=${session.state})"
        }

        return try {
            session.player.pause()
            session.state = PlayerState.PAUSED
            val pos = formatDuration(session.player.currentPosition)
            "Paused session '$sessionId' at $pos"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun resume(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        if (session.state != PlayerState.PAUSED) {
            return "Error: session '$sessionId' is not paused (state=${session.state})"
        }

        return try {
            session.player.start()
            session.state = PlayerState.PLAYING
            val pos = formatDuration(session.player.currentPosition)
            "Resumed session '$sessionId' from $pos"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun seek(sessionId: String, positionMs: Int): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        if (session.state == PlayerState.STOPPED || session.state == PlayerState.IDLE) {
            return "Error: session '$sessionId' is ${session.state} – cannot seek"
        }

        return try {
            session.player.seekTo(positionMs)
            val pos = formatDuration(positionMs)
            "Seeked session '$sessionId' to $pos"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun stop(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        releaseSession(session)
        sessions.remove(sessionId)
        return "Stopped and released session '$sessionId'"
    }

    fun status(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        return try {
            val pos = if (session.state == PlayerState.STOPPED || session.state == PlayerState.IDLE) {
                "N/A"
            } else {
                formatDuration(session.player.currentPosition)
            }
            val dur = if (session.state == PlayerState.STOPPED || session.state == PlayerState.IDLE) {
                "N/A"
            } else {
                formatDuration(session.player.duration)
            }
            "Session '$sessionId': state=${session.state}, file=${session.filePath}, " +
                "type=${session.mediaType}, position=$pos, duration=$dur"
        } catch (e: Exception) {
            "Session '$sessionId': state=${session.state}, file=${session.filePath} (player error: ${e.message})"
        }
    }

    fun listSessions(): String {
        if (sessions.isEmpty()) return "No active media sessions."

        val sb = StringBuilder("Active media sessions (${sessions.size}):\n")
        for ((id, session) in sessions) {
            sb.append("  - $id: state=${session.state}, file=${session.filePath}, type=${session.mediaType}\n")
        }
        return sb.toString().trimEnd()
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun releaseSession(session: Session) {
        try {
            if (session.state == PlayerState.PLAYING || session.state == PlayerState.PAUSED) {
                session.player.stop()
            }
            session.player.release()
            session.state = PlayerState.STOPPED
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun isExternalMountPath(path: String): Boolean =
        path == "/var/minis/mounts" || path.startsWith("/var/minis/mounts/")

    private fun isCanonicalGuestPath(path: String): Boolean {
        val roots = listOf(
            "/var/minis",
            "/workspace",
            "/memory",
            "/skills",
            "/shared",
            "/home/minis",
        )
        return roots.any { path == it || path.startsWith("$it/") } &&
            !isExternalMountPath(path)
    }
}
