package com.openminis.app.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import java.io.File
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] Reading another app's private database by copying it into this app's cache.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Eta does the whole copy in one shell script; this project's privileged
 * surface takes arguments, so the same steps run as separate commands - stat for the size, readlink
 * to refuse a link that would make the copy read something other than the file that was measured,
 * then cp - for the database and for each SQLite sidecar. A missing sidecar is normal; a refused one
 * fails the whole snapshot (upstream's exit 25/26), because a copy without its WAL can answer with
 * rows that were never committed.
 *
 * The path comes from the caller's fixed table and never from model input, and every failure comes
 * back as a coded error rather than as an empty result.
 */
object PrivateDatabaseSnapshot {

    private const val PREFIX = "minis-private-db-"
    private const val TIMEOUT_MS = 15_000L

    /** Eta's placeholder for the current Android user inside a database path. */
    private const val USER_PLACEHOLDER = "{user}"

    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    suspend fun read(
        context: Context,
        sessionId: String,
        sourceTemplate: String,
        maxBytes: Long,
        unavailableCode: String,
        unavailableMessage: String,
        block: (SQLiteDatabase) -> String,
    ): ToolExecutionResult {
        val userId = context.dataDir.parentFile?.name?.toIntOrNull()
            ?: return ToolExecutionResult(
                error(unavailableCode, "the current Android user could not be determined"),
                false,
            )
        val source = sourceTemplate.replace(USER_PLACEHOLDER, userId.toString())
        val snapshot = create(context, sessionId, source, maxBytes)
            ?: return ToolExecutionResult(error(unavailableCode, unavailableMessage), false)
        return try {
            val content = runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use(block)
            }.getOrElse { error(unavailableCode, unavailableMessage) }
            ToolExecutionResult(content, JSONObject(content).optBoolean("ok"))
        } finally {
            delete(snapshot)
        }
    }

    /** The database, and then each sidecar; any refusal fails the whole snapshot, as upstream. */
    private suspend fun create(
        context: Context,
        sessionId: String,
        source: String,
        maxBytes: Long,
    ): File? {
        cleanupStaleSnapshots(context)
        val snapshot = runCatching {
            File.createTempFile(PREFIX, ".db", context.cacheDir)
        }.getOrNull() ?: return null
        if (copyIfCopyable(context, sessionId, source, snapshot, maxBytes) != true) {
            delete(snapshot)
            return null
        }
        SIDECAR_SUFFIXES.forEach { suffix ->
            val sidecar = File(snapshot.absolutePath + suffix)
            when (copyIfCopyable(context, sessionId, source + suffix, sidecar, maxBytes)) {
                true -> Unit
                // A missing sidecar is normal: the database may never have had a WAL.
                false -> sidecar.delete()
                null -> {
                    delete(snapshot)
                    return null
                }
            }
        }
        return snapshot
    }

    /**
     * True when copied, false when the source is not there, null when it was refused (a link, or a
     * file past the cap) or the copy failed.
     */
    private suspend fun copyIfCopyable(
        context: Context,
        sessionId: String,
        source: String,
        target: File,
        maxBytes: Long,
    ): Boolean? {
        val size = fileSize(context, sessionId, source) ?: return false
        if (PrivateDatabaseRules.exceedsSizeCap(size, maxBytes)) return null
        // A link would make the copy read something other than the file that was measured.
        if (isSymlink(context, sessionId, source)) return null
        val result = privileged(
            context = context,
            sessionId = sessionId,
            argv = listOf("cp", source, target.absolutePath),
            operation = "snapshot $source",
        )
        return if (result.success) true else null
    }

    private suspend fun fileSize(context: Context, sessionId: String, path: String): Long? {
        val result = privileged(
            context = context,
            sessionId = sessionId,
            argv = listOf("stat", "-c", "%s", path),
            operation = "stat $path",
        )
        if (!result.success) return null
        return result.stdout.trim().lineSequence().firstOrNull()?.toLongOrNull()
    }

    private suspend fun isSymlink(context: Context, sessionId: String, path: String): Boolean {
        val result = privileged(
            context = context,
            sessionId = sessionId,
            argv = listOf("readlink", path),
            operation = "readlink $path",
        )
        // readlink exits non-zero for a path that is not a link, which is the normal case here.
        return result.success && PrivateDatabaseRules.isSymlink(result.stdout)
    }

    private suspend fun privileged(
        context: Context,
        sessionId: String,
        argv: List<String>,
        operation: String,
    ) = PrivilegedCommandRunner.run(
        context = context,
        sessionId = sessionId.ifBlank { "global" },
        argv = argv,
        operation = operation,
        risk = CommandRisk.READ_ONLY,
        timeoutMs = TIMEOUT_MS,
    )

    private fun cleanupStaleSnapshots(context: Context) {
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(PREFIX) }
            ?.forEach(File::delete)
    }

    private fun delete(snapshot: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(snapshot.absolutePath + suffix).delete()
        }
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()
}
