package com.openminis.app.tools.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.openminis.app.BuildConfig
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import com.openminis.app.tools.ExternalMountAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/** Metadata read from a real APK artifact, never inferred from a fixed filename. */
data class ApkArtifact(
    val hostPath: String,
    val linuxPath: String?,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val debuggable: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val signingSha256: List<String>,
    val candidateActivities: List<String>,
    val source: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("artifactPath", linuxPath ?: hostPath)
        put("hostPath", hostPath)
        put("package", packageName)
        put("versionName", versionName ?: JSONObject.NULL)
        put("versionCode", versionCode)
        put("debuggable", debuggable)
        put("sizeBytes", sizeBytes)
        put("modifiedAt", modifiedAt)
        put("signingSha256", JSONArray(signingSha256))
        put("candidateActivities", JSONArray(candidateActivities))
        put("metadataSource", source)
    }
}

/** APK path resolution, Gradle-output discovery, and archive metadata parsing. */
object AndroidApkInspector {
    private const val MAX_DISCOVERY_DIRECTORIES = 4_000
    private const val MAX_DISCOVERY_DEPTH = 10

    suspend fun inspect(
        context: Context,
        sessionId: String,
        artifactPath: String? = null,
        searchRoot: String? = null,
    ): ApkArtifact {
        val explicit = artifactPath?.trim().orEmpty()
        val resolved = if (explicit.isNotEmpty()) {
            resolveArtifact(context, sessionId, explicit)
                ?: throw IllegalArgumentException("APK path is not visible from this session: $explicit")
        } else {
            val root = searchRoot?.trim().orEmpty().takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("artifactPath or searchRoot is required; APK paths are never guessed")
            resolveSearchRoot(context, sessionId, root)
        }
        val file = resolved.file
        if (!file.isFile || !file.name.endsWith(".apk", true)) {
            throw IllegalArgumentException("artifact is not a readable APK file: ${file.absolutePath}")
        }
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("PackageManager could not parse APK metadata: ${file.absolutePath}")
        archive.applicationInfo?.sourceDir = file.absolutePath
        archive.applicationInfo?.publicSourceDir = file.absolutePath
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION") archive.signatures?.toList().orEmpty()
        }
        val signing = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        return ApkArtifact(
            hostPath = file.canonicalPath,
            linuxPath = resolved.linuxPath,
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else {
                @Suppress("DEPRECATION") archive.versionCode.toLong()
            },
            debuggable = ((archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            signingSha256 = signing,
            candidateActivities = archive.activities.orEmpty().filter { it.enabled }.map { it.name }.distinct(),
            source = metadataSource(file),
        )
    }

    /** Newest artifacts first; only real Gradle output directories are considered. */
    fun discover(projectRoot: File): List<File> {
        if (!projectRoot.isDirectory) return emptyList()
        val outputRoots = mutableListOf<File>()
        data class Pending(val file: File, val depth: Int)
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(projectRoot.canonicalFile, 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_DISCOVERY_DIRECTORIES) {
            val (dir, depth) = queue.removeFirst()
            visited += 1
            if (dir.path.replace('\\', '/').endsWith("/build/outputs/apk")) {
                outputRoots += dir
                continue
            }
            if (depth >= MAX_DISCOVERY_DEPTH) continue
            dir.listFiles()?.filter(File::isDirectory)?.forEach { child ->
                if (child.name !in setOf(".git", ".gradle", "node_modules", "build") || child.name == "build") {
                    // A build directory is useful only for its outputs/apk branch.
                    if (child.name == "build") {
                        val apk = File(child, "outputs/apk")
                        if (apk.isDirectory) outputRoots += apk
                    } else queue.add(Pending(child, depth + 1))
                }
            }
        }
        return outputRoots.distinctBy { it.canonicalPath }
            .flatMap { root -> root.walkTopDown().maxDepth(5).filter { it.isFile && it.extension.equals("apk", true) }.toList() }
            .distinctBy { it.canonicalPath }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
    }

    fun stageForInstaller(context: Context, artifact: ApkArtifact): File {
        if (artifact.packageName == BuildConfig.APPLICATION_ID) {
            throw UnsupportedOperationException(
                "UNSUPPORTED: installing OpenMinis over itself kills the current Agent process; use a future Debug Companion",
            )
        }
        val source = File(artifact.hostPath)
        val dir = File(context.externalCacheDir ?: context.cacheDir, "android-deploy").apply { mkdirs() }
        val target = File(dir, "${artifact.packageName}-${artifact.versionCode}-${source.lastModified()}.apk")
        if (!target.isFile || target.length() != source.length()) source.copyTo(target, overwrite = true)
        target.setReadable(true, false)
        return target
    }

    private fun metadataSource(file: File): String {
        val metadata = File(file.parentFile, "output-metadata.json")
        if (!metadata.isFile) return "apk-archive"
        return runCatching {
            val root = JSONObject(metadata.readText())
            val elements = root.optJSONArray("elements") ?: JSONArray()
            val listed = (0 until elements.length()).any { index ->
                elements.optJSONObject(index)?.optString("outputFile") == file.name
            }
            if (listed) "gradle-output-metadata" else "apk-archive"
        }.getOrDefault("apk-archive")
    }

    private data class ResolvedArtifact(
        val file: File,
        val linuxPath: String?,
    )

    private suspend fun resolveArtifact(
        context: Context,
        sessionId: String,
        path: String,
    ): ResolvedArtifact? {
        if (!path.startsWith('/')) return null
        if (ExternalMountAccess.isPath(path)) {
            return stageExternalArtifact(context, path)
        }
        if (path == "/var/minis" || path.startsWith("/var/minis/") ||
            path == "/workspace" || path.startsWith("/workspace/")) {
            return stageGuestArtifact(context, sessionId, path)
        }
        return null
    }

    private suspend fun resolveSearchRoot(
        context: Context,
        sessionId: String,
        root: String,
    ): ResolvedArtifact {
        if (!root.startsWith('/')) {
            throw IllegalArgumentException("searchRoot must be an absolute Linux path: $root")
        }
        if (ExternalMountAccess.isPath(root)) {
            val file = ExternalMountAccess.walk(
                rootPath = root,
                recursive = true,
                maxEntries = MAX_DISCOVERY_DIRECTORIES,
            ).asSequence()
                .filter { it.type == "file" && it.name.endsWith(".apk", true) }
                .filter { it.path.substringBeforeLast('/').endsWith("/build/outputs/apk") }
                .maxWithOrNull(compareBy<ExternalMountAccess.Entry> { it.modified }.thenBy { it.path })
                ?: throw IllegalArgumentException("no APK under real Gradle build/outputs/apk directories below $root")
            return stageExternalArtifact(context, file.path)
        }
        if (root != "/var/minis" && !root.startsWith("/var/minis/") &&
            root != "/workspace" && !root.startsWith("/workspace/")) {
            throw IllegalArgumentException("searchRoot is outside the guest persistent layout: $root")
        }
        val guestPath = discoverGuest(sessionId, root).firstOrNull()
            ?: throw IllegalArgumentException("no APK under real Gradle build/outputs/apk directories below $root")
        return stageGuestArtifact(context, sessionId, guestPath)
    }

    private suspend fun stageGuestArtifact(
        context: Context,
        sessionId: String,
        linuxPath: String,
    ): ResolvedArtifact {
        val info = WorkspaceFileClient.info(sessionId, linuxPath)
        if (info.optString("type") != "file") {
            throw IllegalArgumentException("APK artifact is not a regular file: $linuxPath")
        }
        val size = info.optLong("size", -1L)
        if (size < 0L || size > WorkspaceFileClient.MAX_FILE_BYTES) {
            throw IllegalArgumentException("APK artifact is too large or has no size: $linuxPath")
        }
        val modified = info.optLong("modified", 0L)
        val name = linuxPath.substringAfterLast('/').ifBlank { "artifact.apk" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$linuxPath\u0000$size\u0000$modified".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(context.cacheDir, "android-deploy/guest/$digest-$name")
        target.parentFile?.mkdirs()
        if (!target.isFile || target.length() != size) {
            withContext(Dispatchers.IO) {
                FileOutputStream(target).use { output ->
                    var offset = 0L
                    while (true) {
                        val chunk = WorkspaceFileClient.readChunk(sessionId, linuxPath, offset)
                        output.write(chunk.bytes)
                        offset += chunk.bytes.size
                        if (chunk.eof) break
                        if (chunk.bytes.isEmpty()) {
                            throw IllegalStateException("workspace.file read made no progress: $linuxPath")
                        }
                    }
                    output.fd.sync()
                }
            }
        }
        if (!target.isFile || target.length() != size) {
            throw IllegalStateException("staged APK size mismatch: $linuxPath")
        }
        target.setReadable(true, false)
        return ResolvedArtifact(target, linuxPath)
    }

    private suspend fun stageExternalArtifact(
        context: Context,
        linuxPath: String,
    ): ResolvedArtifact {
        val info = ExternalMountAccess.info(linuxPath)
        if (info.optString("type") != "file") {
            throw IllegalArgumentException("APK artifact is not a regular file: $linuxPath")
        }
        val size = info.optLong("size", -1L)
        if (size < 0L || size > WorkspaceFileClient.MAX_FILE_BYTES) {
            throw IllegalArgumentException("APK artifact is too large or has no size: $linuxPath")
        }
        val modified = info.optLong("modified", 0L)
        val name = linuxPath.substringAfterLast('/').ifBlank { "artifact.apk" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$linuxPath\u0000$size\u0000$modified".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(context.cacheDir, "android-deploy/external/$digest-$name")
        if (!target.isFile || target.length() != size) {
            WorkspaceFileClient.readToFile(null, linuxPath, target, WorkspaceFileClient.MAX_FILE_BYTES)
        }
        if (!target.isFile || target.length() != size) {
            throw IllegalStateException("staged external APK size mismatch: $linuxPath")
        }
        target.setReadable(true, false)
        return ResolvedArtifact(target, linuxPath)
    }

    private suspend fun discoverGuest(sessionId: String, root: String): List<String> {
        data class Pending(val path: String, val depth: Int)
        val outputRoots = mutableListOf<String>()
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root.trimEnd('/').ifEmpty { "/" }, 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_DISCOVERY_DIRECTORIES) {
            val (directory, depth) = queue.removeFirst()
            visited += 1
            if (directory.trimEnd('/').endsWith("/build/outputs/apk")) {
                outputRoots += directory
                continue
            }
            if (depth >= MAX_DISCOVERY_DEPTH) continue
            for (entry in listGuestEntries(sessionId, directory)) {
                if (entry.optString("type") != "dir") continue
                val child = "$directory/${entry.optString("name")}".replace("//", "/")
                queue.add(Pending(child, depth + 1))
            }
        }
        return outputRoots
            .flatMap { walkGuestApks(sessionId, it, 5) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private suspend fun walkGuestApks(
        sessionId: String,
        root: String,
        maxDepth: Int,
    ): List<Pair<String, Long>> {
        data class Pending(val path: String, val depth: Int)
        val found = mutableListOf<Pair<String, Long>>()
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root, 0))
        while (queue.isNotEmpty()) {
            val (directory, depth) = queue.removeFirst()
            for (entry in listGuestEntries(sessionId, directory)) {
                val name = entry.optString("name")
                val child = "$directory/$name".replace("//", "/")
                when {
                    entry.optString("type") == "file" && name.endsWith(".apk", true) ->
                        found += child to entry.optLong("modified", 0L)
                    entry.optString("type") == "dir" && depth < maxDepth ->
                        queue.add(Pending(child, depth + 1))
                }
            }
        }
        return found
    }

    private suspend fun listGuestEntries(sessionId: String, path: String): List<JSONObject> {
        val entries = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val page = WorkspaceFileClient.list(sessionId, path, 500, offset)
            val array = page.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(entries::add)
            }
            val next = page.optInt("next_offset", -1)
            if (next < 0) return entries
            offset = next
        }
    }

}
