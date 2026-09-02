package com.openminis.app.providers

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * Exposes the broker-owned global Minis scopes to the system Files app.
 *
 * Document IDs are virtual guest paths (`memory/foo.txt`), never host paths.
 * Every metadata and mutation operation goes through `workspace.file`, and
 * file descriptors are backed by `StorageManager` proxy callbacks so the App
 * never opens `/data/adb/minis` directly.
 */
class MinisDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "llc.slacker.minis.documents"
        private const val TAG = "MinisDocumentsProvider"
        private const val ROOT_ID = "minis-root"
        private const val ROOT_DOC_ID = ""
        private const val MAX_DOCUMENT_BYTES = WorkspaceFileClient.MAX_FILE_BYTES
        private val TOP_LEVEL = listOf("memory", "skills", "shared")
        private val READ_ONLY_TOP = setOf("memory", "skills")

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )

        internal fun isSafeDocumentId(documentId: String): Boolean {
            if (documentId.isEmpty()) return false
            val parts = documentId.split('/')
            return parts.firstOrNull()?.let(TOP_LEVEL::contains) == true && parts.all(::isSafeDocumentName)
        }

        private fun isSafeDocumentName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= 255 &&
                !name.startsWith('.') &&
                name != "." &&
                name != ".." &&
                name.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 0x20 }
    }

    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    override fun onCreate(): Boolean {
        val thread = HandlerThread("minis-documents-provider").also { it.start() }
        callbackThread = thread
        callbackHandler = Handler(thread.looper)
        return true
    }

    override fun shutdown() {
        callbackThread?.quitSafely()
        callbackThread = null
        callbackHandler = null
        super.shutdown()
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_TITLE, "Minis")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
            .add(Root.COLUMN_ICON, 0)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (documentId.isEmpty()) {
            addDocumentRow(cursor, ROOT_DOC_ID, "Minis", "dir", 0L, 0L)
            return cursor
        }
        val info = broker { WorkspaceFileClient.info(null, guestPath(documentId)) }
        addDocumentRow(
            cursor = cursor,
            documentId = documentId,
            displayName = safeName(documentId.substringAfterLast('/')),
            type = info.optString("type"),
            size = info.optLong("size", 0L),
            modified = info.optLong("modified", 0L),
        )
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (parentDocumentId.isEmpty()) {
            TOP_LEVEL.forEach { name ->
                runCatching {
                    broker { WorkspaceFileClient.info(null, "/$name") }
                }.onSuccess { info ->
                    if (info.optString("type") == "dir") {
                        addDocumentRow(cursor, name, name, "dir", 0L, info.optLong("modified", 0L))
                    }
                }
            }
            return cursor
        }

        val parentPath = guestPath(parentDocumentId)
        val parentInfo = broker { WorkspaceFileClient.info(null, parentPath) }
        if (parentInfo.optString("type") != "dir") {
            throw FileNotFoundException("Not a directory: $parentDocumentId")
        }
        val listing = broker { WorkspaceFileClient.list(null, parentPath, 500, 0) }
        val entries = listing.optJSONArray("entries") ?: return cursor
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val name = entry.optString("name")
            if (runCatching { safeName(name) }.isFailure) continue
            val documentId = "$parentDocumentId/$name"
            addDocumentRow(
                cursor = cursor,
                documentId = documentId,
                displayName = name,
                type = entry.optString("type"),
                size = entry.optLong("size", 0L),
                modified = entry.optLong("modified", 0L),
            )
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val path = guestPath(documentId)
        val info = broker { WorkspaceFileClient.info(null, path) }
        if (info.optString("type") != "file") {
            throw FileNotFoundException("Not a regular file: $documentId")
        }
        val modeFlags = ParcelFileDescriptor.parseMode(mode)
        val writable = mode.contains('w') || mode.contains('+')
        if (writable && isReadOnly(documentId)) {
            throw UnsupportedOperationException("${topLevel(documentId)} is read-only")
        }
        val callback = BrokerFileCallback(
            documentId = documentId,
            path = path,
            modeFlags = modeFlags,
            initialSize = info.optLong("size", 0L),
            writable = writable,
        )
        val handler = callbackHandler ?: throw IllegalStateException("Provider is not initialized")
        val storage = providerContext().getSystemService(StorageManager::class.java)
            ?: throw IllegalStateException("StorageManager unavailable")
        return storage.openProxyFileDescriptor(modeFlags, callback, handler)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        if (parentDocumentId.isEmpty()) {
            throw UnsupportedOperationException("top-level provider root is immutable")
        }
        if (isReadOnly(parentDocumentId)) {
            throw UnsupportedOperationException("${topLevel(parentDocumentId)} is read-only")
        }
        val name = safeName(displayName)
        val parentPath = guestPath(parentDocumentId)
        val parentInfo = broker { WorkspaceFileClient.info(null, parentPath) }
        if (parentInfo.optString("type") != "dir") {
            throw FileNotFoundException("Not a directory: $parentDocumentId")
        }
        val documentId = "$parentDocumentId/$name"
        val path = guestPath(documentId)
        if (mimeType == Document.MIME_TYPE_DIR) {
            broker { WorkspaceFileClient.mkdir(null, path) }
        } else {
            broker { WorkspaceFileClient.writeBytes(null, path, ByteArray(0)) }
        }
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        if (isReadOnly(documentId)) {
            throw UnsupportedOperationException("${topLevel(documentId)} is read-only")
        }
        broker { WorkspaceFileClient.delete(null, guestPath(documentId)) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (isReadOnly(documentId)) {
            throw UnsupportedOperationException("${topLevel(documentId)} is read-only")
        }
        val name = safeName(displayName)
        val parent = documentId.substringBeforeLast('/', "")
        val renamed = if (parent.isEmpty()) name else "$parent/$name"
        broker {
            WorkspaceFileClient.move(
                sessionId = null,
                source = guestPath(documentId),
                destination = guestPath(renamed),
            )
        }
        return renamed
    }

    private inner class BrokerFileCallback(
        private val documentId: String,
        private val path: String,
        private val modeFlags: Int,
        private val initialSize: Long,
        private val writable: Boolean,
    ) : ProxyFileDescriptorCallback() {
        private val lock = Any()
        private val localFile: File? = if (writable) {
            File.createTempFile("minis-document-", ".tmp", providerContext().cacheDir)
        } else {
            null
        }
        private val localAccess: RandomAccessFile? = localFile?.let { file ->
            try {
                if (initialSize > MAX_DOCUMENT_BYTES) {
                    throw IllegalArgumentException("document exceeds broker limit: $documentId")
                }
                if (!isTruncating(modeFlags)) {
                    WorkspaceFileClient.readToFileBlocking(null, path, file, MAX_DOCUMENT_BYTES)
                }
                RandomAccessFile(file, "rw")
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
        private var committed = false

        override fun onGetSize(): Long = synchronized(lock) {
            localAccess?.length() ?: initialSize
        }

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (size <= 0) return 0
            if (localAccess != null) {
                return synchronized(lock) {
                    localAccess.seek(offset)
                    localAccess.read(data, 0, size).coerceAtLeast(0)
                }
            }
            val chunk = broker {
                WorkspaceFileClient.readChunk(
                    sessionId = null,
                    path = path,
                    offset = offset,
                    length = size.coerceAtMost(WorkspaceFileClient.MAX_READ_CHUNK),
                )
            }
            val count = minOf(chunk.bytes.size, size)
            chunk.bytes.copyInto(data, destinationOffset = 0, endIndex = count)
            return count
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            if (localAccess == null || !writable || size < 0 || data.size < size) {
                throw errno(OsConstants.EBADF, "document is not writable")
            }
            val writeOffset = synchronized(lock) {
                if (isAppending(modeFlags)) localAccess.length() else offset
            }
            if (writeOffset < 0 || writeOffset > MAX_DOCUMENT_BYTES ||
                size.toLong() > MAX_DOCUMENT_BYTES - writeOffset
            ) {
                throw errno(OsConstants.EFBIG, "document exceeds broker limit")
            }
            synchronized(lock) {
                localAccess.seek(writeOffset)
                localAccess.write(data, 0, size)
            }
            return size
        }

        override fun onFsync() {
            if (writable) commit()
        }

        override fun onRelease() {
            try {
                if (writable) commit()
            } catch (error: Throwable) {
                Log.e(TAG, "failed to commit document $documentId", error)
            } finally {
                runCatching { localAccess?.close() }
                localFile?.delete()
            }
        }

        private fun commit() {
            synchronized(lock) {
                if (committed || localFile == null) return
                localAccess?.fd?.sync()
                localFile.inputStream().use { input ->
                    broker {
                        WorkspaceFileClient.writeStream(
                            sessionId = null,
                            path = path,
                            input = input,
                            maxBytes = MAX_DOCUMENT_BYTES,
                        )
                    }
                }
                committed = true
            }
        }
    }

    private fun addDocumentRow(
        cursor: MatrixCursor,
        documentId: String,
        displayName: String,
        type: String,
        size: Long,
        modified: Long,
    ) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, displayName)
            .add(
                Document.COLUMN_MIME_TYPE,
                if (type == "dir") Document.MIME_TYPE_DIR else mimeFor(displayName),
            )
            .add(Document.COLUMN_LAST_MODIFIED, modified)
            .add(Document.COLUMN_FLAGS, flagsFor(documentId, type == "dir"))
            .add(Document.COLUMN_SIZE, if (type == "file") size else 0L)
    }

    private fun flagsFor(documentId: String, isDirectory: Boolean): Int {
        if (documentId.isEmpty() || isReadOnly(documentId)) {
            return if (isDirectory) Document.FLAG_DIR_PREFERS_LAST_MODIFIED else 0
        }
        if (documentId in TOP_LEVEL) {
            return if (isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0
        }
        var flags = if (isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        return flags
    }

    private fun guestPath(documentId: String): String {
        if (documentId.isEmpty()) throw FileNotFoundException("provider root has no guest path")
        val parts = documentId.split('/')
        if (!isSafeDocumentId(documentId)) {
            throw FileNotFoundException("Invalid document id: $documentId")
        }
        return "/$documentId"
    }

    private fun safeName(name: String): String {
        if (!isSafeDocumentName(name)) {
            throw FileNotFoundException("Invalid document name")
        }
        return name
    }

    private fun topLevel(documentId: String): String = documentId.substringBefore('/')

    private fun isReadOnly(documentId: String): Boolean = topLevel(documentId) in READ_ONLY_TOP

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun isTruncating(modeFlags: Int): Boolean =
        modeFlags and ParcelFileDescriptor.MODE_TRUNCATE != 0

    private fun isAppending(modeFlags: Int): Boolean =
        modeFlags and ParcelFileDescriptor.MODE_APPEND != 0

    private fun providerContext(): Context =
        context ?: throw IllegalStateException("Provider has no context")

    private fun <T> broker(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { block() }

    private fun errno(code: Int, message: String): ErrnoException =
        ErrnoException("MinisDocumentsProvider: $message", code)
}
