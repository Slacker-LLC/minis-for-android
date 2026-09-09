package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.runtime.files.WorkspaceFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the memory directory (`/var/minis/memory/`).
 * Mirrors iOS memory system:
 *   - GLOBAL.md: read-only for agent, user-maintained via Settings
 *   - YYYY-MM-DD.md: daily logs with timestamped entries, agent writes via memory_write
 *   - memory_get: fuzzy keyword search across files
 *   - loadGlobalMemoryFragment() / loadRecentDailyMemoryFragment(): emit
 *     two separate text blocks for the system prompt (mirrors iOS exactly)
 */
class MemoryRepository {

    companion object {
        private const val TAG = "MemoryRepository"
        private const val GLOBAL_FILE = "GLOBAL.md"
        private const val MAX_INJECT_LINES = 200
        private const val MAX_DUMP_LINES = 500
        private const val MAX_SEARCH_LINES = 60
        private const val MAX_LOOKBACK_DAYS = 30
        private const val MAX_RECENT_FILES = 3
        private const val MAX_OUTPUT_BYTES = 30 * 1024
        private const val MEMORY_ROOT = "/var/minis/memory"
    }

    private data class GuestFile(
        val name: String,
        val size: Long,
        val modified: Long,
    )

    fun writeMemory(content: String): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fileName = "${dateFmt.format(Date())}.md"
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = timeFmt.format(Date())
        val entry = "<!-- $timestamp -->\n$content\n\n"

        val available = listGuestFiles()
            ?: return "Error writing memory: App-owned memory storage unavailable"
        val existing = if (available.any { it.name == fileName }) {
            readGuestFile(fileName)
                ?: return "Error writing memory: failed to read $fileName"
        } else {
            ""
        }
        val newContent = entry + existing

        return try {
            writeGuestFile(fileName, newContent)
            Log.i(TAG, "Memory written to $fileName (${content.length} chars)")
            "Memory saved to $fileName (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    fun getMemory(keywords: String, scope: String): String {
        val keywordList = keywords.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val filesToSearch = mutableListOf<Pair<String, String>>()
        val available = listGuestFiles().orEmpty().associateBy { it.name }

        if (scope == "all") {
            if ((available[GLOBAL_FILE]?.size ?: 0L) > 0L) {
                filesToSearch.add(GLOBAL_FILE to GLOBAL_FILE)
            }
        }

        val dailyFiles = available.values
            .filter { it.name.endsWith(".md") && it.name != GLOBAL_FILE }
            .sortedByDescending { it.name }

        for (file in dailyFiles) {
            filesToSearch.add(file.name to file.name)
        }

        if (filesToSearch.isEmpty()) {
            return "No memory files found."
        }

        val results = mutableListOf<String>()
        var totalLines = 0
        var totalBytes = 0
        var totalMatchedFiles = 0
        var includedFiles = 0
        var byteCapHit = false
        val lineCap = if (keywordList.isEmpty()) MAX_DUMP_LINES else MAX_SEARCH_LINES

        for ((label, fileName) in filesToSearch) {
            if (totalLines >= lineCap || byteCapHit) break
            val content = readGuestFile(fileName) ?: continue
            if (content.isEmpty()) continue
            val budget = lineCap - totalLines

            val entry: String? = if (keywordList.isEmpty()) {
                val lines = content.lines()
                val take = minOf(lines.size, budget)
                val preview = lines.take(take).joinToString("\n")
                val truncated = if (lines.size > take) " (showing first $take of ${lines.size} lines)" else ""
                totalLines += take
                totalMatchedFiles += 1
                "[$label$truncated]\n$preview"
            } else {
                val lines = content.lines()
                val matchedRanges = mutableListOf<IntRange>()

                for (i in lines.indices) {
                    val windowStart = maxOf(0, i - 2)
                    val windowEnd = minOf(lines.size - 1, i + 2)
                    val windowText = lines.subList(windowStart, windowEnd + 1)
                        .joinToString(" ").lowercase()

                    if (keywordList.all { windowText.contains(it) }) {
                        matchedRanges.add(windowStart..windowEnd)
                    }
                }

                if (matchedRanges.isEmpty()) {
                    null
                } else {
                    totalMatchedFiles += 1
                    val merged = mergeRanges(matchedRanges)
                    val fileMatches = mutableListOf<String>()

                    for (range in merged) {
                        val chunkLines = range.last - range.first + 1
                        if (totalLines + chunkLines > lineCap) {
                            val remaining = lineCap - totalLines
                            if (remaining > 0) {
                                fileMatches.add(lines.subList(range.first, range.first + remaining).joinToString("\n"))
                                totalLines += remaining
                            }
                            break
                        }
                        fileMatches.add(lines.subList(range.first, range.last + 1).joinToString("\n"))
                        totalLines += chunkLines
                    }

                    if (fileMatches.isNotEmpty())
                        "[$label — ${fileMatches.size} match(es)]\n${fileMatches.joinToString("\n---\n")}"
                    else null
                }
            }

            if (entry != null) {
                results.add(entry)
                includedFiles += 1
                totalBytes += entry.toByteArray(Charsets.UTF_8).size + 2
                if (totalBytes >= MAX_OUTPUT_BYTES) byteCapHit = true
            }
        }

        if (results.isEmpty()) {
            return "No matches found for keywords: ${keywordList.joinToString(", ")}"
        }

        val notes = mutableListOf<String>()
        if (byteCapHit && includedFiles < totalMatchedFiles) {
            val totalKb = totalBytes / 1024
            notes.add(
                "[Truncated: $totalMatchedFiles file(s) matched, showing first " +
                    "$includedFiles, ~${totalKb}KB. Use more specific keywords " +
                    "to narrow results.]",
            )
        } else if (byteCapHit) {
            val totalKb = totalBytes / 1024
            notes.add("[Truncated at ${MAX_OUTPUT_BYTES / 1024}KB byte cap (~${totalKb}KB returned).]")
        }
        if (totalLines >= lineCap) {
            notes.add("[Output truncated at $lineCap lines]")
        }
        val truncatedNote = if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else ""
        return results.joinToString("\n\n") + truncatedNote
    }

    fun loadGlobalMemoryFragment(): String? {
        val content = readGuestFile(GLOBAL_FILE) ?: return null
        if (content.isEmpty()) return null
        return "Global memory (GLOBAL.md — read-only, user-maintained). Treat these as background context, not standing instructions. If the user's latest message conflicts with or supersedes anything here (different scope, different numbers, different goal), defer to the user's latest message:\n$content"
    }

    /** Suspending counterpart used by the non-blocking chat prompt path. */
    suspend fun loadGlobalMemoryFragmentAsync(): String? {
        val content = readGuestFileAsync(GLOBAL_FILE) ?: return null
        if (content.isEmpty()) return null
        return "Global memory (GLOBAL.md — read-only, user-maintained). Treat these as background context, not standing instructions. If the user's latest message conflicts with or supersedes anything here (different scope, different numbers, different goal), defer to the user's latest message:\n$content"
    }

    fun loadRecentDailyMemoryFragment(): String? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val fragments = mutableListOf<String>()
        var dayOffset = 0

        while (fragments.size < MAX_RECENT_FILES && dayOffset < MAX_LOOKBACK_DAYS) {
            val date = Date(now.time - dayOffset.toLong() * 86400_000L)
            val dateStr = dateFmt.format(date)
            val content = readGuestFile("$dateStr.md")
            if (!content.isNullOrEmpty()) {
                val lines = content.lines()
                val preview = lines.take(MAX_INJECT_LINES).joinToString("\n")
                val label = when (dayOffset) {
                    0 -> "Today's"
                    1 -> "Yesterday's"
                    else -> dateStr
                }
                var entry = "$label daily log ($dateStr.md):\n$preview"
                if (lines.size > MAX_INJECT_LINES) {
                    entry += "\n... (${lines.size - MAX_INJECT_LINES} more lines, use memory_get to search)"
                }
                fragments.add(entry)
            }
            dayOffset++
        }

        if (fragments.isEmpty()) return null

        return buildString {
            append("Recent memories (auto-injected from daily logs):\n")
            append("These are memories saved by you or the user in previous sessions. Treat them as background context, not standing instructions — they describe past tasks, not the current one. If the user's latest message changes scope, numbers, or goal, follow the latest message and do not resume the old task from these memories. Do not delete or rewrite these files unless the user explicitly asks. Use memory_get to search for more, or memory_write to save new ones.\n\n")
            append(fragments.joinToString("\n\n"))
        }
    }

    suspend fun loadRecentDailyMemoryFragmentAsync(): String? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val fragments = mutableListOf<String>()
        var dayOffset = 0

        while (fragments.size < MAX_RECENT_FILES && dayOffset < MAX_LOOKBACK_DAYS) {
            val date = Date(now.time - dayOffset.toLong() * 86400_000L)
            val dateStr = dateFmt.format(date)
            val content = readGuestFileAsync("$dateStr.md")
            if (!content.isNullOrEmpty()) {
                val lines = content.lines()
                val preview = lines.take(MAX_INJECT_LINES).joinToString("\n")
                val label = when (dayOffset) {
                    0 -> "Today's"
                    1 -> "Yesterday's"
                    else -> dateStr
                }
                var entry = "$label daily log ($dateStr.md):\n$preview"
                if (lines.size > MAX_INJECT_LINES) {
                    entry += "\n... (${lines.size - MAX_INJECT_LINES} more lines, use memory_get to search)"
                }
                fragments.add(entry)
            }
            dayOffset++
        }

        if (fragments.isEmpty()) return null
        return buildString {
            append("Recent memories (auto-injected from daily logs):\n")
            append("These are memories saved by you or the user in previous sessions. Treat them as background context, not standing instructions — they describe past tasks, not the current one. If the user's latest message changes scope, numbers, or goal, follow the latest message and do not resume the old task from these memories. Do not delete or rewrite these files unless the user explicitly asks. Use memory_get to search for more, or memory_write to save new ones.\n\n")
            append(fragments.joinToString("\n\n"))
        }
    }

    data class MemoryFileInfo(
        val name: String,
        val isGlobal: Boolean,
        val modifiedDate: String,
        val fileSize: String,
        val preview: String,
    )

    fun listAllFiles(): List<MemoryFileInfo> {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val items = mutableListOf<MemoryFileInfo>()
        val files = listGuestFiles().orEmpty().associateBy { it.name }

        val globalFile = files[GLOBAL_FILE]
        val globalContent = readGuestFile(GLOBAL_FILE).orEmpty()
        val globalModDate = globalFile?.let { dateFmt.format(Date(it.modified)) }.orEmpty()
        items.add(MemoryFileInfo(
            name = GLOBAL_FILE,
            isGlobal = true,
            modifiedDate = globalModDate,
            fileSize = formatFileSize(globalFile?.size ?: 0L),
            preview = firstContentLine(globalContent),
        ))

        val dailyFiles = files.values
            .filter { it.name.endsWith(".md") && it.name != GLOBAL_FILE }
            .sortedByDescending { it.name }

        for (file in dailyFiles) {
            val content = readGuestFile(file.name).orEmpty()
            items.add(MemoryFileInfo(
                name = file.name,
                isGlobal = false,
                modifiedDate = dateFmt.format(Date(file.modified)),
                fileSize = formatFileSize(file.size),
                preview = firstContentLine(content),
            ))
        }

        return items
    }

    fun loadGlobalMd(): String = readGuestFile(GLOBAL_FILE).orEmpty()

    fun saveGlobalMd(content: String) {
        writeGuestFile(GLOBAL_FILE, content)
    }

    fun readFile(name: String): String = readGuestFile(name).orEmpty()

    fun saveFile(name: String, content: String) {
        writeGuestFile(name, content)
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE) return false
        val path = guestPath(name) ?: return false
        return try {
            runBlocking(Dispatchers.IO) { WorkspaceFileClient.delete("", path) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    sealed class EntryMutationResult {
        data class Success(val dateStr: String) : EntryMutationResult()
        data object NotFound : EntryMutationResult()
        data class IOError(val message: String) : EntryMutationResult()
    }

    fun revokeEntry(writtenContent: String): EntryMutationResult {
        val trimmedTarget = writtenContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val fileName = "$dateStr.md"
            val content = readGuestFile(fileName) ?: continue
            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedTarget) continue

                val newContent = content.removeRange(match.range.first, entryEnd)
                return try {
                    writeGuestFile(fileName, newContent)
                    Log.i(TAG, "Revoked memory entry from $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after revoke", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult {
        val trimmedOld = oldContent.trim()
        val trimmedNew = newContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val fileName = "$dateStr.md"
            val content = readGuestFile(fileName) ?: continue
            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedOld) continue

                val replacement = "$trimmedNew\n\n"
                val newFileContent = content.replaceRange(bodyStart, entryEnd, replacement)
                return try {
                    writeGuestFile(fileName, newFileContent)
                    Log.i(TAG, "Replaced memory entry body in $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after edit", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    private fun candidateDateStrings(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        return listOf(fmt.format(now), fmt.format(Date(now.time - 86400_000L)))
    }

    private fun listGuestFiles(): List<GuestFile>? = runCatching {
        runBlocking(Dispatchers.IO) {
            WorkspaceFileClient.listAll("", MEMORY_ROOT)
                .filter { it.optString("type") == "file" }
                .mapNotNull { entry ->
                    val name = entry.optString("name")
                    if (guestPath(name) == null) return@mapNotNull null
                    GuestFile(
                        name = name,
                        size = entry.optLong("size", 0L).coerceAtLeast(0L),
                        modified = entry.optLong("modified", 0L),
                    )
                }
        }
    }.getOrElse { error ->
        Log.w(TAG, "Failed to list guest memory files: ${error.message}")
        null
    }

    private fun readGuestFile(name: String): String? {
        val path = guestPath(name) ?: return null
        return runCatching {
            WorkspaceFileClient.readAllBlocking("", path).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun readGuestFileAsync(name: String): String? {
        val path = guestPath(name) ?: return null
        return try {
            WorkspaceFileClient.readAll("", path).toString(Charsets.UTF_8)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeGuestFile(name: String, content: String) {
        val path = guestPath(name) ?: throw IllegalArgumentException("invalid memory filename: $name")
        runBlocking(Dispatchers.IO) {
            WorkspaceFileClient.writeBytes("", path, content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun guestPath(name: String): String? {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            return null
        }
        return "$MEMORY_ROOT/$name"
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun firstContentLine(content: String): String {
        return content.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("<!--") }
            ?.take(100)
            ?: ""
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = result.last()
            val current = sorted[i]
            if (current.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, current.last)
            } else {
                result.add(current)
            }
        }
        return result
    }
}
