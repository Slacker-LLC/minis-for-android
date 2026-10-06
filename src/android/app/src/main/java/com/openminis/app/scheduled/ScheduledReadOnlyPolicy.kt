package com.openminis.app.scheduled

import com.openminis.app.util.shellQuote
import org.json.JSONObject
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.Locale

/**
 * Pure READ_ONLY scheduled-run policy. This deliberately parses a small shell
 * subset rather than trying to emulate a shell: anything outside the subset is
 * denied. Runtime code separately resolves permitted redirect paths against
 * the session's app-owned directory before executing them.
 */
object ScheduledReadOnlyPolicy {
    const val TEMP_DIRECTORY = "/var/minis/offloads"
    const val GUEST_TEMP_ALIAS = "/tmp"
    const val MAX_SHELL_COMMAND_CHARS = 8_192
    const val MAX_SHELL_SEGMENTS = 128
    private const val MAX_DENIAL_SUMMARY_CHARS = 200

    private val readOnlyCommands = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "sort", "uniq", "cut", "tr",
        "date", "pwd", "whoami", "hostname", "uname", "uptime", "df", "du", "ps",
        "stat", "file", "echo",
    )
    private val readOnlyGitCommands = setOf(
        "status", "log", "diff", "show", "branch", "ls-files", "rev-parse",
    )
    private val readOnlyGitBranchOptions = setOf(
        "-a", "-r", "-v", "-vv", "--all", "--remotes", "--verbose", "--list", "--no-color", "--color",
    )
    private val deniedCodeExecutionTools = setOf("linux.python.run", "linux.pip.install")
    private val fileReadTools = setOf(
        "linux.file.read", "linux.file.image.read", "linux.file.list", "linux.file.search",
        "linux.file.grep", "linux.file.head_tail", "linux.file.info",
    )
    private val deniedFindPredicates = setOf(
        "-exec", "-execdir", "-delete", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls",
    )
    private val deniedGitHelperOptions = setOf("--help", "-h", "--show-signature")
    private val shellSyntaxConfusables = setOf('；', '｜', '＆', '＇', '｀', '＄', '＜', '＞', '＼')

    data class SegmentDecision(
        val segment: String,
        val allowed: Boolean,
        val reason: String? = null,
    )

    data class ShellDecision(
        val allowed: Boolean,
        val rejectedSegment: String? = null,
        val segments: List<SegmentDecision> = emptyList(),
        /** Literal output-redirection targets, used by the runtime symlink check. */
        val redirectPaths: List<String> = emptyList(),
        val reason: String? = null,
    )

    private data class ParsedSegment(val tokens: List<String>, val source: String)
    private data class SegmentEvaluation(
        val decision: SegmentDecision,
        val redirectPaths: List<String>,
    )

    /** Parse and authorize a bounded subset of shell syntax. No command is run. */
    fun evaluateShell(command: String): ShellDecision {
        if (command.isBlank()) return denied(command, "empty command")
        if (command.length > MAX_SHELL_COMMAND_CHARS) return denied(command, "command too long")
        if (command.any { it in shellSyntaxConfusables }) return denied(command, "confusable shell punctuation")
        if (command.any { it == '\u0000' || (it.isISOControl() && it != '\n') }) {
            return denied(command, "control character")
        }

        val parsed = parseShellSubset(command) ?: return denied(command, "unsupported shell syntax")
        if (parsed.size > MAX_SHELL_SEGMENTS) return denied(command, "too many command segments")
        if (parsed.size > 1 && parsed.any { it.tokens.firstOrNull() == "git" }) {
            return denied(command, "git cannot be combined with shell operators in READ_ONLY")
        }
        val evaluated = parsed.map(::evaluateSegment)
        val rejected = evaluated.firstOrNull { !it.decision.allowed }
        return ShellDecision(
            allowed = rejected == null,
            rejectedSegment = rejected?.decision?.segment,
            segments = evaluated.map { it.decision },
            redirectPaths = evaluated.flatMap { it.redirectPaths },
            reason = rejected?.decision?.reason,
        )
    }

    /** True only for a literal descendant of the app-owned session temp directory. */
    fun isTemporaryPath(rawPath: String): Boolean {
        val normalized = normalizedAbsolutePath(rawPath) ?: return false
        return temporaryRoots().any { root -> normalized.startsWith("$root/") }
    }

    /** Convert either guest spelling to the canonical app-owned path for runtime resolution. */
    fun canonicalTemporaryPath(rawPath: String): String? {
        val normalized = normalizedAbsolutePath(rawPath) ?: return null
        if (normalized.startsWith("$TEMP_DIRECTORY/")) return normalized
        if (normalized.startsWith("$GUEST_TEMP_ALIAS/")) {
            return TEMP_DIRECTORY + normalized.removePrefix(GUEST_TEMP_ALIAS)
        }
        return null
    }

    /**
     * Return the stable denial code for a mutating file tool in READ_ONLY.
     * A null result means the tool is either read-only or its writes stay under
     * the per-session temporary directory.
     */
    fun fileWriteDenial(toolName: String, args: JSONObject?): String? {
        val name = toolName.lowercase(Locale.ROOT)
        if (name == "memory_write") return "file_write_denied_readonly_tier: $name"
        if (!name.startsWith("linux.file.") || name in fileReadTools) return null

        val paths = when (name) {
            "linux.file.copy" -> listOfNotNull(args?.stringValue("destination", "dest", "to", "destination_path"))
            "linux.file.move" -> listOfNotNull(
                args?.stringValue("source", "src", "from", "source_path"),
                args?.stringValue("destination", "dest", "to", "destination_path"),
            )
            else -> listOfNotNull(args?.stringValue("path", "target", "file", "destination"))
        }
        val expectedPathCount = when (name) {
            "linux.file.copy" -> 1
            "linux.file.move" -> 2
            else -> 1
        }
        if (paths.size != expectedPathCount || paths.any { !isTemporaryPath(it) }) {
            return "file_write_denied_readonly_tier: $name"
        }
        return null
    }

    /** A denial summary contains paths only; it never copies a file's body into run history. */
    fun fileWriteSummary(toolName: String, args: JSONObject?): String {
        val name = toolName.lowercase(Locale.ROOT)
        val paths = when (name) {
            "linux.file.copy" -> listOfNotNull(args?.stringValue("destination", "dest", "to", "destination_path"))
            "linux.file.move" -> listOfNotNull(
                args?.stringValue("source", "src", "from", "source_path"),
                args?.stringValue("destination", "dest", "to", "destination_path"),
            )
            else -> listOfNotNull(args?.stringValue("path", "target", "file", "destination"))
        }
        return sanitizeSummary(
            if (paths.isEmpty()) name else "$name: ${paths.joinToString(" -> ") { it.take(120) }}",
        ).take(MAX_DENIAL_SUMMARY_CHARS)
    }

    fun shellDenial(command: String): String? {
        val decision = evaluateShell(command)
        if (decision.allowed) return null
        val summary = sanitizeSummary(decision.rejectedSegment ?: command).ifBlank { "<empty>" }
        return "shell_denied_readonly_tier: $summary"
    }

    fun mcpDenial(toolName: String, isMcpTool: Boolean): String? {
        if (!isMcpTool && !toolName.startsWith("mcp.", ignoreCase = true)) return null
        return "mcp_denied_readonly_tier: ${sanitizeSummary(toolName).take(120)}"
    }

    fun codeExecutionDenial(toolName: String): String? =
        toolName.lowercase(Locale.ROOT)
            .takeIf { it in deniedCodeExecutionTools }
            ?.let { "shell_denied_readonly_tier: $it" }

    /** Run an allowed git read with repository-provided executable helpers disabled. */
    fun hardenGitInvocation(command: String): String {
        val segment = parseShellSubset(command)?.singleOrNull() ?: return command
        if (segment.tokens.firstOrNull() != "git") return command

        val gitArgs = mutableListOf<String>()
        val redirects = mutableListOf<Pair<String, String>>()
        var index = 1
        while (index < segment.tokens.size) {
            val token = segment.tokens[index]
            if (token == ">" || token == ">>") {
                val path = segment.tokens.getOrNull(index + 1) ?: return command
                redirects.add(token to path)
                index += 2
            } else {
                gitArgs += token
                index++
            }
        }
        val subcommand = gitArgs.firstOrNull() ?: return command
        val safeArgs = buildList {
            add("git")
            addAll(listOf("-c", "core.fsmonitor=false", "-c", "core.pager=cat"))
            addAll(listOf(
                "-c", "pager.status=false", "-c", "pager.log=false", "-c", "pager.diff=false",
                "-c", "pager.show=false", "-c", "pager.branch=false", "-c", "pager.ls-files=false",
            ))
            addAll(listOf("-c", "diff.external=", "-c", "core.hooksPath=/dev/null"))
            add(subcommand)
            if (subcommand in setOf("diff", "show", "log")) add("--no-textconv")
            addAll(gitArgs.drop(1))
        }
        val invocation = safeArgs.joinToString(" ", transform = ::shellQuote)
        val redirection = redirects.joinToString("") { (operator, path) -> " $operator ${shellQuote(path)}" }
        return "GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_COUNT=0 " +
            "GIT_PAGER=cat GIT_EXTERNAL_DIFF= GIT_OPTIONAL_LOCKS=0 $invocation$redirection"
    }

    fun sanitizeSummary(value: String): String = value
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DENIAL_SUMMARY_CHARS)

    private fun JSONObject.stringValue(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key) as? String ?: continue
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun evaluateSegment(parsed: ParsedSegment): SegmentEvaluation {
        val commandTokens = mutableListOf<String>()
        val redirectPaths = mutableListOf<String>()
        var index = 0
        while (index < parsed.tokens.size) {
            val token = parsed.tokens[index]
            if (token == ">" || token == ">>") {
                val target = parsed.tokens.getOrNull(index + 1)
                    ?: return segmentDenied(parsed, "redirection target missing")
                if (!isTemporaryPath(target)) {
                    return segmentDenied(parsed, "redirection outside session temp directory")
                }
                redirectPaths += target
                index += 2
            } else {
                if (token == "<" || token == "<<" || token == "<<<") {
                    return segmentDenied(parsed, "input redirection and here-docs are denied")
                }
                commandTokens += token
                index++
            }
        }
        if (commandTokens.isEmpty()) return segmentDenied(parsed, "empty command segment")

        val command = commandTokens.first()
        val args = commandTokens.drop(1)
        val reason = when {
            command == "git" -> gitDenial(args)
            command !in readOnlyCommands && command != "find" && command != "sed" -> "command is not in the read-only allowlist"
            command == "find" && args.any { it in deniedFindPredicates || it.startsWith("-exec=") || it.startsWith("-ok=") } ->
                "find write/exec predicate is denied"
            command == "sed" && "--sandbox" !in args -> "sed must use --sandbox"
            command == "sed" && args.any { it == "--in-place" || it.startsWith("--in-place=") || it.startsWith("-i") } ->
                "sed in-place editing is denied"
            command == "date" && args.any { it == "-s" || it == "--set" || it.startsWith("--set=") } ->
                "date mutation option is denied"
            command == "sort" && args.any { it == "-o" || it.startsWith("-o") || it == "--output" || it.startsWith("--output=") } ->
                "sort output-file option is denied"
            command == "sort" && args.any { it == "--compress-program" || it.startsWith("--compress-program=") } ->
                "sort compression helper is denied"
            command == "sort" && args.any {
                it == "-T" || it.startsWith("-T") || it == "--temporary-directory" || it.startsWith("--temp")
            } -> "sort temporary-directory option is denied"
            command == "uniq" && uniqPositionals(args) > 1 -> "uniq's second file operand is an output file"
            else -> null
        }
        return if (reason == null) {
            SegmentEvaluation(SegmentDecision(sanitizeSummary(parsed.source), allowed = true), redirectPaths)
        } else {
            SegmentEvaluation(SegmentDecision(sanitizeSummary(parsed.source), allowed = false, reason), emptyList())
        }
    }

    /** File operands of `uniq [option]... [input [output]]`; the value after -f/-s/-w is not one. */
    private fun uniqPositionals(args: List<String>): Int {
        var count = 0
        var skipValue = false
        var optionsEnded = false
        for (arg in args) {
            when {
                skipValue -> skipValue = false
                !optionsEnded && arg == "--" -> optionsEnded = true
                !optionsEnded && (arg == "-f" || arg == "-s" || arg == "-w") -> skipValue = true
                !optionsEnded && arg.startsWith("-") && arg != "-" -> Unit
                else -> count++
            }
        }
        return count
    }

    private fun gitDenial(args: List<String>): String? {
        val subcommand = args.firstOrNull() ?: return "git subcommand is missing"
        if (subcommand !in readOnlyGitCommands) return "git subcommand is not read-only"
        if (args.drop(1).any { it in deniedGitHelperOptions || it.startsWith("--help") }) {
            return "git help or signature helper is denied"
        }
        if (subcommand == "branch" && args.drop(1).any { it !in readOnlyGitBranchOptions }) {
            return "git branch permits listing options only"
        }
        if (args.drop(1).any { it == "--ext-diff" || it == "--textconv" }) return "external git diff helpers are denied"
        if (args.drop(1).any { it == "-o" || it == "--output" || it.startsWith("--output=") }) {
            return "git output-file option is denied"
        }
        return null
    }

    private fun segmentDenied(parsed: ParsedSegment, reason: String): SegmentEvaluation =
        SegmentEvaluation(SegmentDecision(sanitizeSummary(parsed.source), allowed = false, reason), emptyList())

    private fun denied(command: String, reason: String): ShellDecision {
        val summary = sanitizeSummary(command).ifBlank { "<empty>" }
        return ShellDecision(
            allowed = false,
            rejectedSegment = summary,
            segments = listOf(SegmentDecision(summary, allowed = false, reason)),
            reason = reason,
        )
    }

    /** Tokenize only quotes, whitespace, command separators and output redirects. */
    private fun parseShellSubset(command: String): List<ParsedSegment>? {
        val segments = mutableListOf<ParsedSegment>()
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        val raw = StringBuilder()
        var quote: Char? = null
        var wordStarted = false

        fun flushWord() {
            if (wordStarted) {
                tokens += word.toString()
                word.setLength(0)
                wordStarted = false
            }
        }

        fun finishSegment(): Boolean {
            flushWord()
            if (tokens.isEmpty()) return false
            segments += ParsedSegment(tokens.toList(), raw.toString())
            tokens.clear()
            raw.setLength(0)
            return true
        }

        var index = 0
        while (index < command.length) {
            val ch = command[index]
            raw.append(ch)
            if (ch == '\\' || ch == '`' || ch == '$') return null
            if (quote != null) {
                when (ch) {
                    quote -> quote = null
                    else -> word.append(ch)
                }
                wordStarted = true
                index++
                continue
            }
            when {
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    wordStarted = true
                }
                ch == ';' || ch == '\n' -> {
                    if (!finishSegment()) return null
                }
                ch == '|' -> {
                    if (command.getOrNull(index + 1) == '|') {
                        raw.append('|')
                        index++
                    }
                    if (!finishSegment()) return null
                }
                ch == '&' -> {
                    if (command.getOrNull(index + 1) != '&') return null
                    raw.append('&')
                    index++
                    if (!finishSegment()) return null
                }
                ch == '<' -> return null
                ch == '>' -> {
                    flushWord()
                    if (command.getOrNull(index + 1) == '>') {
                        raw.append('>')
                        index++
                        tokens += ">>"
                    } else {
                        tokens += ">"
                    }
                }
                ch.isWhitespace() -> flushWord()
                else -> {
                    word.append(ch)
                    wordStarted = true
                }
            }
            index++
        }
        if (quote != null) return null
        if (!finishSegment()) return null
        return segments
    }

    private fun normalizedAbsolutePath(rawPath: String): String? {
        if (rawPath.isBlank() || rawPath.length > 4_096) return null
        if (rawPath.any { it.isISOControl() || it == '$' || it == '`' || it == '\\' }) return null
        if (!rawPath.startsWith('/')) return null
        return try {
            Paths.get(rawPath).normalize().toString().ifBlank { "/" }
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun temporaryRoots(): List<String> = listOf(TEMP_DIRECTORY, GUEST_TEMP_ALIAS)
}
