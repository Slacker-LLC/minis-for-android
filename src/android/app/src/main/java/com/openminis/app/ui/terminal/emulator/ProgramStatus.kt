package com.openminis.app.ui.terminal.emulator

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * OSC 7501, the Program Status Protocol (https://www.superlogical.com/rex/docs/build/program-status,
 * revision 0.3). A program tells the terminal whether it is idle, working, blocked on the user,
 * done or failed; the terminal keeps one record per id and decides how to show them.
 *
 * Everything in a report is untrusted. A report that breaks a limit, fails base64/UTF-8, or carries
 * a control character in its free text is discarded whole; nothing from it touches a stored record.
 */
enum class ProgramState { IDLE, WORKING, DONE, BLOCKED, ERROR }

enum class BlockedKind { PERMISSION, QUESTION, AUTH }

data class StatusRecord(
    val id: String,
    val state: ProgramState,
    val kind: BlockedKind?,
    val progress: Int?,
    val app: String?,
    val title: String?,
    val msg: String?,
)

/** One parsed report: either a record to store or a `clear`. */
sealed interface ProgramStatusReport {
    data class Update(val record: StatusRecord) : ProgramStatusReport
    data class Clear(val id: String?) : ProgramStatusReport
}

object ProgramStatusParser {
    const val MAX_SEQUENCE_BYTES = 4096
    private const val MAX_KEY = 16
    private const val MAX_MSG_ENCODED = 2732
    private const val MAX_MSG_DECODED = 2048
    private const val MAX_TITLE_ENCODED = 256
    private const val MAX_TITLE_DECODED = 192
    private const val MAX_APP = 32
    private const val MAX_ID = 128
    private const val MAX_SEGMENT = 32
    private const val MAX_DEPTH = 8

    // "OSC 7501 ;" + body + the longest terminator (ESC \).
    private const val FRAMING_BYTES = 2 + 4 + 1 + 2

    private val keyRegex = Regex("[a-z]+")
    private val valueRegex = Regex("[A-Za-z0-9_.,+/=-]*")
    private val segmentRegex = Regex("[A-Za-z0-9_.+-]{1,$MAX_SEGMENT}")
    private val appRegex = Regex("[A-Za-z0-9_.+-]{1,$MAX_APP}")

    /** True for the feature-detection query body. */
    fun isQuery(body: String): Boolean = body.trim() == "?"

    /** Returns null when the report is ignored or discarded; the caller must then change nothing. */
    fun parse(body: String): ProgramStatusReport? {
        if (body.length + FRAMING_BYTES > MAX_SEQUENCE_BYTES) return null

        val pairs = LinkedHashMap<String, String>()
        for (raw in body.split(':')) {
            val eq = raw.indexOf('=')
            if (eq < 0) continue
            val key = raw.substring(0, eq).trim()
            val value = raw.substring(eq + 1).trim()
            if (key.isEmpty() || !keyRegex.matches(key)) continue
            if (key.length > MAX_KEY) return null
            if (!valueRegex.matches(value)) {
                // A bad id must not fall back to the root record and overwrite or clear it.
                if (key == "id") return null
                continue
            }
            pairs[key] = value
        }

        val state = pairs["state"] ?: return null
        val id = pairs["id"]?.let { it.takeIf(::validId) ?: return null }

        if (state == "clear") return ProgramStatusReport.Clear(id)
        val programState = when (state) {
            "idle" -> ProgramState.IDLE
            "working" -> ProgramState.WORKING
            "done" -> ProgramState.DONE
            "blocked" -> ProgramState.BLOCKED
            "error" -> ProgramState.ERROR
            else -> return null
        }

        val app = pairs["app"]?.let { value ->
            if (value.length > MAX_APP) return null
            value.takeIf { appRegex.matches(it) }
        }
        val msg = pairs["msg"]?.let { decodeText(it, MAX_MSG_ENCODED, MAX_MSG_DECODED) ?: return null }
        val title = pairs["title"]?.let { decodeText(it, MAX_TITLE_ENCODED, MAX_TITLE_DECODED) ?: return null }

        val kind = if (programState == ProgramState.BLOCKED) {
            when (pairs["kind"]) {
                "permission" -> BlockedKind.PERMISSION
                "question" -> BlockedKind.QUESTION
                "auth" -> BlockedKind.AUTH
                else -> null
            }
        } else null
        val progress = if (programState == ProgramState.WORKING || programState == ProgramState.BLOCKED) {
            pairs["progress"]?.takeIf { it.length in 1..3 && it.all { c -> c in '0'..'9' } }
                ?.toInt()?.takeIf { it in 0..100 }
        } else null

        return ProgramStatusReport.Update(
            StatusRecord(id ?: ROOT_ID, programState, kind, progress, app, title, msg),
        )
    }

    const val ROOT_ID = ""

    private fun validId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_ID) return false
        val segments = id.split('/')
        return segments.size <= MAX_DEPTH && segments.all { segmentRegex.matches(it) }
    }

    /** Standard base64 of UTF-8 with optional padding; null on any violation (the report is discarded). */
    private fun decodeText(encoded: String, maxEncoded: Int, maxDecoded: Int): String? {
        if (encoded.length > maxEncoded) return null
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size > maxDecoded) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (text.any { it.code <= 0x1F || it.code in 0x7F..0x9F }) return null
        return text
    }
}

/** The terminal's set of records, one per id. Not thread-safe; the emulator owns it. */
class ProgramStatusStore(private val maxRecords: Int = MAX_RECORDS) {
    // Insertion order is recency order: an update removes and re-inserts its id.
    private val records = LinkedHashMap<String, StatusRecord>()

    val snapshot: List<StatusRecord> get() = records.values.toList()

    /** Applies [report]; returns whether the stored set changed. */
    fun apply(report: ProgramStatusReport): Boolean = when (report) {
        is ProgramStatusReport.Update -> {
            records.remove(report.record.id)
            while (records.size >= maxRecords) records.remove(records.keys.first())
            records[report.record.id] = report.record
            true
        }
        is ProgramStatusReport.Clear -> {
            val id = report.id
            if (id == null) {
                val had = records.isNotEmpty()
                records.clear()
                had
            } else {
                records.keys.removeAll { it == id || it.startsWith("$id/") }
            }
        }
    }

    /** Process exit or a new shell prompt: working, blocked and idle go; done and error stay. */
    fun dropTransient(): Boolean = records.values.removeAll {
        it.state == ProgramState.WORKING || it.state == ProgramState.BLOCKED || it.state == ProgramState.IDLE
    }

    /** RIS. */
    fun reset(): Boolean = apply(ProgramStatusReport.Clear(null))

    /** A record without `app` takes it from its nearest ancestor that has one. */
    fun effectiveApp(record: StatusRecord): String? {
        record.app?.let { return it }
        var id = record.id
        while (id.isNotEmpty()) {
            id = id.substringBeforeLast('/', missingDelimiterValue = ProgramStatusParser.ROOT_ID)
            records[id]?.app?.let { return it }
        }
        return null
    }

    companion object {
        /** The spec requires at least 64; the cap exists so a hostile program cannot grow memory. */
        const val MAX_RECORDS = 256
    }
}

/** Invisible and direction-override characters must not reach UI outside the terminal grid. */
fun sanitizeStatusText(text: String): String = text.filterNot {
    when (Character.getType(it).toByte()) {
        Character.FORMAT, Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
}
