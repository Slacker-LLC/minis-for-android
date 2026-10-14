package com.openminis.app.runtime.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/** SAF proof stays in its granted document domain; it never becomes a guest/host path. */
internal object DocumentEvidence {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    private fun requireGrant(context: Context, uri: Uri) {
        require(uri.scheme == "content" && DocumentsContract.isDocumentUri(context, uri)) { "not_a_saf_document" }
        val resolver = context.contentResolver
        check(resolver.persistedUriPermissions.any { grant ->
            if (!grant.isReadPermission || grant.uri.authority != uri.authority) false
            else if (grant.uri == uri) true
            else if (DocumentsContract.isTreeUri(grant.uri)) {
                val root = DocumentsContract.buildDocumentUriUsingTree(grant.uri, DocumentsContract.getTreeDocumentId(grant.uri))
                val child = DocumentsContract.buildDocumentUriUsingTree(grant.uri, DocumentsContract.getDocumentId(uri))
                DocumentsContract.getDocumentId(root) == DocumentsContract.getDocumentId(child) ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                        DocumentsContract.isChildDocument(resolver, root, child))
            } else false
        }) { "document_read_grant_missing_or_outside_tree" }
    }

    suspend fun fingerprint(context: Context, rawUri: String): JSONObject = withContext(Dispatchers.IO) {
        val uri = Uri.parse(rawUri)
        requireGrant(context, uri)
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            check(it.moveToFirst() && it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) { "document_not_a_regular_file" }
            it.getString(1)
        } ?: error("document_metadata_unavailable")
        val active = kotlin.coroutines.coroutineContext
        fun read(): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            (resolver.openInputStream(uri) ?: error("document_unreadable")).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    active.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    size += n
                    check(size <= MAX_BYTES) { "document_evidence_too_large" }
                    digest.update(buffer, 0, n)
                }
            }
            return size to digest.digest().joinToString("") { "%02x".format(it) }
        }
        val first = read()
        check(read() == first) { "document_changed_during_verification" }
        requireGrant(context, uri)
        JSONObject().put("uri", uri.toString()).put("name", name ?: JSONObject.NULL).put("size", first.first).put("sha256", first.second)
    }
}
