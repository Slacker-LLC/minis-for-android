package com.openminis.app.tools

import org.json.JSONObject

/**
 * [T-eta-xposed-groups] Where the chat apps keep their image caches, and how a file becomes a row.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta runs one shell pipeline - find, sort, head - so the same filter tokens
 * are kept here as arguments, because this project's privileged surface takes argv: the two
 * directories are the apps' own caches, the size ceiling is the one the image encoder uses, the
 * sorting happens in Kotlin instead of in a pipeline, and a row is only accepted when its path
 * really is under the directory that was searched.
 */
object ChatImagePolicy {

    const val QQ_DIRECTORY =
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
    const val WECHAT_DIRECTORY = "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg"

    /** The same ceiling the model image encoder enforces. */
    const val MAX_FILE_BYTES = 12L * 1024 * 1024

    /** How many files one scan may contribute before the caller's own limit is applied. */
    const val MAX_CANDIDATES = 120

    /** QQ keeps originals, re-encoded images and thumbnails in three sibling trees. */
    val QQ_PATH_FILTER: List<String> = listOf(
        "(", "-path", "*/chatimg/*", "-o",
        "-path", "*/chatraw/*", "-o",
        "-path", "*/chatthumb/*", ")",
    )

    /** WeChat's cache keeps its images under image/. */
    val WECHAT_PATH_FILTER: List<String> = listOf("-path", "*/image/*")

    /**
     * The scan Eta's pipeline stands for, minus the sort and the head. Toybox find takes the
     * grouping parentheses as arguments, so no shell is involved.
     */
    fun findArgv(directory: String, pathFilter: List<String>): List<String> = buildList {
        add("find")
        add(directory)
        add("-type")
        add("f")
        addAll(pathFilter)
        add("-size")
        add("-${MAX_FILE_BYTES}c")
        add("-printf")
        add("%T@|%s|%p\n")
    }

    /** One `mtime|size|path` line; anything else, or any path outside [directory], is null. */
    fun row(line: String, directory: String, kind: (String) -> String): JSONObject? {
        val fields = line.split('|', limit = 3)
        if (fields.size != 3) return null
        val modifiedAt = fields[0].toDoubleOrNull()?.toLong() ?: return null
        val size = fields[1].toLongOrNull() ?: return null
        val path = fields[2]
        if (!path.startsWith(directory + "/")) return null
        return JSONObject()
            .put("path", path)
            .put("name", path.substringAfterLast('/'))
            .put("kind", kind(path))
            .put("modified_at_epoch_seconds", modifiedAt)
            .put("size_bytes", size)
    }

    fun qqKind(path: String): String = when {
        "/chatraw/" in path -> "original"
        "/chatimg/" in path -> "image"
        "/chatthumb/" in path -> "thumbnail"
        else -> "other"
    }

    fun wechatKind(path: String): String = "image"
}
