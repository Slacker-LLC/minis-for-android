package com.openminis.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** File references are projections of existing, verified task evidence, not a second artifact store. */
internal object TaskArtifacts {
    data class Reference(
        val taskId: String,
        val referenceIndex: Int,
        val goal: String,
        val sessionId: String?,
        val path: String,
        val expectedSha256: String,
        val expectedSize: Long?,
        val verifiedAt: Long,
        val inferredSource: Boolean,
        val documentUri: String? = null,
        val name: String = path.substringAfterLast('/'),
    ) {
        val key: String get() = "$taskId:$referenceIndex"
    }
    data class Page(val references: List<Reference>, val nextOffset: Int?)
    enum class State { VERIFIED, CHANGED, UNAVAILABLE }
    data class Verification(val state: State, val size: Long? = null, val sha256: String? = null)

    /** Task pagination preserves old completed evidence even after the active Goal is cleared. */
    suspend fun page(context: Context, sessionId: String? = null, offset: Int = 0): Page = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).botTaskDao()
        var cursor = offset.coerceAtLeast(0)
        val pageSize = 10
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val tasks = dao.completedEvidence(sessionId, pageSize, cursor)
            val references = tasks.flatMap { task ->
                val proof = runCatching { JSONObject(task.completionEvidence!!) }.getOrNull()
                    ?.takeIf { it.optString("task_id") == task.id } ?: return@flatMap emptyList()
                val inferred = !proof.has("session_id")
                // A completed task cannot be rebound. Older evidence retains its completed owner.
                val source = (if (inferred) task.currentOwnerSessionId else proof.optString("session_id"))
                    ?.takeIf(UbuntuPaths::isSafeSessionId)
                val refs = proof.optJSONArray("references") ?: return@flatMap emptyList()
                val deliveries = proof.optJSONArray("deliveries") ?: org.json.JSONArray()
                ((0 until minOf(refs.length(), 16)) +
                    (0 until minOf(deliveries.length(), 16)).map { refs.length() + it }).mapNotNull { index ->
                    val ref = (if (index < refs.length()) refs.optJSONObject(index) else deliveries.optJSONObject(index - refs.length()))
                        ?.takeIf { it.optString("type") in setOf("file", "document") } ?: return@mapNotNull null
                    val document = ref.optString("uri").takeIf { ref.optString("type") == "document" }
                    val path = document ?: ref.optString("path")
                    val sha = ref.optString("sha256").lowercase()
                    if (path.isBlank() || !sha.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
                    Reference(task.id, index, task.goal, source, path, sha,
                        if (ref.has("size")) ref.optLong("size").takeIf { it >= 0L } else null,
                        ref.optLong("delivered_at", proof.optLong("verified_at")), inferred, document,
                        ref.optString("name").takeIf { it.isNotBlank() && !ref.isNull("name") } ?: path.substringAfterLast('/'))
                }
            }
            cursor += tasks.size
            val next = cursor.takeIf { tasks.size == pageSize }
            if (references.isNotEmpty() || next == null) return@withContext Page(references, next)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Current availability does not rewrite historical task completion or trust the current page's SID. */
    suspend fun verify(context: Context, reference: Reference): Verification = withContext(Dispatchers.IO) {
        val sid = reference.sessionId
        val shared = reference.path.startsWith("/shared/") || reference.path.startsWith("/var/minis/shared/")
        if (reference.documentUri == null && !shared &&
            (sid == null || AppDatabase.getInstance(context).chatDao().getSession(sid) == null)) return@withContext Verification(State.UNAVAILABLE)
        try {
            val actual = reference.documentUri?.let { com.openminis.app.runtime.files.DocumentEvidence.fingerprint(context, it) }
                ?: WorkspaceFileClient.fingerprint(sid, reference.path)
            val size = actual.getLong("size")
            val sha = actual.getString("sha256")
            val matches = sha == reference.expectedSha256 && (reference.expectedSize == null || reference.expectedSize == size)
            Verification(if (matches) State.VERIFIED else State.CHANGED, size, sha)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Verification(State.UNAVAILABLE)
        }
    }

    /** Explicit global sharing uses the existing shared backing and records the checked copy. */
    suspend fun share(context: Context, reference: Reference) = withContext(Dispatchers.IO) {
        check(verify(context, reference).state == State.VERIFIED && reference.documentUri == null) { "artifact_changed_or_unavailable" }
        val folder = "/shared/tasks/${reference.taskId}"
        val safeName = reference.name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(120).ifBlank { "artifact" }
        val path = "$folder/${java.util.UUID.randomUUID()}-$safeName"
        WorkspaceFileClient.mkdir(null, folder)
        com.openminis.app.tools.internal.FileMutationQueue.withKey(com.openminis.app.tools.internal.FileMutationQueue.keyFor("", path)) {
            var recorded = false
            try {
            WorkspaceFileClient.copy(reference.sessionId, reference.path, path)
            val actual = WorkspaceFileClient.fingerprint(null, path)
            check(actual.getString("sha256") == reference.expectedSha256 &&
                (reference.expectedSize == null || actual.getLong("size") == reference.expectedSize)) { "shared_copy_does_not_match_artifact" }
            kotlin.coroutines.coroutineContext.ensureActive()
            withContext(kotlinx.coroutines.NonCancellable) {
                appendDelivery(context, reference, actual.put("type", "file").put("name", reference.name)
                    .put("scope", "global_shared"))
                recorded = true
            }
            } catch (error: Exception) {
                if (!recorded) withContext(kotlinx.coroutines.NonCancellable) { runCatching { WorkspaceFileClient.delete(null, path) } }
                throw error
            }
        }
    }

    private suspend fun appendDelivery(context: Context, reference: Reference, delivery: JSONObject) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val task = checkNotNull(db.botTaskDao().get(reference.taskId)) { "task_not_found" }
            val saved = checkNotNull(task.completionEvidence) { "artifact_proof_missing" }
            val evidence = JSONObject(saved)
            val references = evidence.getJSONArray("references")
            val deliveries = evidence.optJSONArray("deliveries") ?: org.json.JSONArray()
            val source = if (reference.referenceIndex < references.length()) references.getJSONObject(reference.referenceIndex)
                else deliveries.getJSONObject(reference.referenceIndex - references.length())
            check(source.getString("sha256") == reference.expectedSha256 &&
                (source.optString("uri").ifBlank { source.optString("path") }) == reference.path) { "artifact_reference_changed" }
            check(deliveries.length() < 16) { "task_delivery_reference_limit" }
            deliveries.put(delivery.put("delivered_at", System.currentTimeMillis()).put("source_reference_index", reference.referenceIndex))
            evidence.put("deliveries", deliveries)
            check(db.botTaskDao().appendDelivery(task.id, saved, evidence.toString()) == 1) { "completed_task_changed" }
        }
    }

    /** User-selected export, checked byte-for-byte before its durable delivery reference is saved. */
    suspend fun export(context: Context, reference: Reference, uri: android.net.Uri) = withContext(Dispatchers.IO) {
        check(verify(context, reference).state == State.VERIFIED) { "artifact_changed_or_unavailable" }
        context.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val temporary = java.io.File.createTempFile("minis-delivery-", ".tmp", context.cacheDir)
        try {
            if (reference.documentUri != null) {
                (context.contentResolver.openInputStream(android.net.Uri.parse(reference.documentUri)) ?: error("document_unreadable"))
                    .use { input -> temporary.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            size += n; check(size <= 2L * 1024 * 1024 * 1024) { "artifact_too_large" }
                            out.write(buffer, 0, n)
                        }
                    } }
            } else WorkspaceFileClient.readToFile(reference.sessionId, reference.path, temporary, 2L * 1024 * 1024 * 1024)
            (context.contentResolver.openOutputStream(uri, "wt") ?: error("delivery_not_writable"))
                .use { out -> temporary.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                } }
            val proof = com.openminis.app.runtime.files.DocumentEvidence.fingerprint(context, uri.toString())
            check(proof.getString("sha256") == reference.expectedSha256 &&
                (reference.expectedSize == null || proof.getLong("size") == reference.expectedSize)) { "delivery_does_not_match_artifact" }
            kotlin.coroutines.coroutineContext.ensureActive()
            withContext(kotlinx.coroutines.NonCancellable) { appendDelivery(context, reference, proof.put("type", "document")) }
        } finally { temporary.delete() }
    }
}
