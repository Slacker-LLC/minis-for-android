package com.openminis.app.integrity

/**
 * What the device-integrity protection (Issue #182) refuses to do, in pure Kotlin.
 *
 * The line is one question: can the worst outcome be fixed by a reboot? If so the operation is open.
 * Only operations that can leave the phone unable to boot or unusable are refused, and a refusal is a
 * fixed error ([Denial.message]) with no prompt: the agent reads it and tries another way.
 *
 * This class answers for the cases that can be recognised from a command's words. It is the first
 * line, giving a clear `DEVICE_PROTECTED` error. The second line is the protected view
 * ([ProtectedView]) that the command actually runs in: read-only bind mounts, no writable block
 * nodes, no `CAP_SYS_ADMIN`. The view is what holds when a command hides its target in a variable, a
 * `$(…)` or a script file; this class cannot see through those and does not pretend to.
 *
 * Out of scope on purpose: raw binder transactions (`service call`) skip the front ends checked here.
 * The protection stops a slip, not a deliberate bypass (see `06-CURRENT-GAPS.md`).
 */
class DeviceIntegrityPolicy(
    private val packages: CorePackageResolver = CorePackageResolver.StaticOnly,
) {
    enum class Category(val label: String) {
        PARTITION("partition"),
        SYSTEM_PARTITION("system-partition"),
        DATA_SYSTEM("data-system"),
        CORE_PACKAGE("core-package"),
        DEVICE_STATE("device-state"),
        STORAGE("storage"),
    }

    data class Denial(val category: Category, val target: String) {
        /** The one error text every entry point returns. */
        val message: String get() = "DEVICE_PROTECTED: ${category.label}: $target"
    }

    /** Check one tool with its arguments, e.g. `root.shell` or an `android-root-cli` front end. */
    fun checkArgv(tool: String, args: List<String>): Denial? = Checker().simple(Simple(listOf(tool) + args, emptyList()))

    /** Check a shell script, as given to `android-root-cli exec`. */
    fun checkScript(script: String): Denial? = Checker().script(script, 0)

    /** Check a path that a structured operation will write to (a file push destination, say). */
    fun checkWritePath(path: String): Denial? = Checker().path(path, destructive = false)

    /** Check a path that a structured operation will delete or rename. */
    fun checkDeletePath(path: String): Denial? = Checker().path(path, destructive = true)

    /** Whether the package-changing operation may touch [packageName]. */
    fun checkPackageOperation(operation: String, packageName: String): Denial? =
        if (packages.isCore(packageName)) Denial(Category.CORE_PACKAGE, "$operation $packageName") else null

    // ------------------------------------------------------------------------------------------

    internal data class Simple(val words: List<String>, val redirects: List<String>)

    private inner class Checker {
        var cwd: String = "/"

        fun script(text: String, depth: Int): Denial? {
            if (depth > MAX_DEPTH) return null
            for (command in ShellWords.split(text)) {
                simpleAt(command, depth)?.let { return it }
            }
            return null
        }

        fun simple(command: Simple): Denial? = simpleAt(command, 0)

        private fun simpleAt(command: Simple, depth: Int): Denial? {
            for (target in command.redirects) {
                path(target, destructive = false)?.let { return it }
            }
            var words = command.words
            // Leading VAR=value assignments do not change which command runs.
            while (words.isNotEmpty() && ASSIGNMENT.matches(words.first())) words = words.drop(1)
            if (words.isEmpty()) return null
            var name = baseName(words.first())
            var args = words.drop(1)

            // Unwrap launchers until the command that does the work is reached.
            var guard = 0
            while (guard++ < MAX_DEPTH) {
                when {
                    name in SHELLS || name == "su" -> {
                        val c = args.indexOfFirst { it == "-c" || (it.startsWith("-") && !it.startsWith("--") && it.endsWith("c") && it.length <= 4) }
                        if (c >= 0 && c + 1 < args.size) return script(args[c + 1], depth + 1)
                        return null
                    }
                    name in WRAPPERS -> {
                        val rest = unwrap(name, args) ?: return null
                        if (rest.isEmpty()) return null
                        name = baseName(rest.first())
                        args = rest.drop(1)
                    }
                    else -> break
                }
            }
            return command(name, args, depth)
        }

        private fun command(name: String, args: List<String>, depth: Int): Denial? {
            val operands = args.filter { !it.startsWith("-") || it == "-" }
            return when {
                name == "cd" -> {
                    operands.firstOrNull()?.let { cwd = resolve(it) }
                    null
                }
                name in DESTRUCTIVE -> firstPathDenial(args, destructive = true)
                name in WRITERS -> firstPathDenial(args, destructive = false)
                name == "dd" -> args.firstNotNullOfOrNull { arg ->
                    if (arg.startsWith("of=")) path(arg.removePrefix("of="), destructive = false) else null
                }
                name == "cp" || name == "install" || name == "rsync" || name == "ln" -> copyDenial(args)
                name == "mv" -> firstPathDenial(args, destructive = true)
                name == "tee" -> firstPathDenial(args, destructive = false)
                name == "sed" || name == "perl" -> if (args.any { it == "-i" || it.startsWith("-i") || it == "--in-place" }) {
                    firstPathDenial(args, destructive = false)
                } else {
                    null
                }
                name == "find" -> findDenial(args, depth)
                name == "mount" -> mountDenial(args)
                name == "umount" -> null
                name.startsWith("mkfs") || name in BLOCK_TOOLS -> blockToolDenial(args)
                name == "recovery" -> if (args.any { it.contains("wipe", ignoreCase = true) }) {
                    Denial(Category.DEVICE_STATE, "recovery ${args.joinToString(" ")}".trim())
                } else {
                    null
                }
                name == "am" -> amDenial(args)
                name == "pm" -> pmDenial(args)
                name == "cmd" -> cmdDenial(args)
                name == "settings" -> settingsDenial(args)
                name == "wipe" -> Denial(Category.DEVICE_STATE, "wipe")
                else -> null
            }
        }

        // ---- launchers ----

        /** The command that a launcher runs, or null when its arguments are not a command we follow. */
        private fun unwrap(name: String, args: List<String>): List<String>? {
            var rest = args
            when (name) {
                "env" -> {
                    rest = rest.dropWhile { it.startsWith("-") || ASSIGNMENT.matches(it) }
                }
                "timeout" -> {
                    rest = rest.dropWhile { it.startsWith("-") }
                    rest = rest.drop(1) // the duration
                }
                "nice", "ionice", "chrt", "taskset" -> {
                    rest = rest.dropWhile { it.startsWith("-") || it.toIntOrNull() != null }
                }
                "busybox", "toybox", "toolbox", "command", "exec", "time", "nohup", "setsid", "stdbuf", "xargs", "flock", "sudo", "doas" -> {
                    rest = rest.dropWhile { it.startsWith("-") }
                    if (name == "flock") rest = rest.drop(1)
                }
            }
            return rest
        }

        // ---- per-command rules ----

        private fun firstPathDenial(args: List<String>, destructive: Boolean): Denial? {
            for (arg in args) {
                if (arg.startsWith("-")) continue
                path(arg.substringAfter('=', arg), destructive)?.let { return it }
            }
            return null
        }

        private fun copyDenial(args: List<String>): Denial? {
            val target = args.indexOfFirst { it == "-t" || it.startsWith("--target-directory") }
            if (target >= 0) {
                val dir = if (args[target].contains('=')) args[target].substringAfter('=') else args.getOrNull(target + 1)
                if (dir != null) path(dir, destructive = false)?.let { return it }
            }
            val operands = args.filter { !it.startsWith("-") }
            return operands.lastOrNull()?.let { path(it, destructive = false) }
        }

        private fun findDenial(args: List<String>, depth: Int): Denial? {
            val roots = args.takeWhile { !it.startsWith("-") && it != "(" && it != "!" }
            if (args.any { it == "-delete" || it == "-fdelete" }) {
                for (root in roots) path(root, destructive = true)?.let { return it }
            }
            var i = 0
            while (i < args.size) {
                if (args[i] in setOf("-exec", "-execdir", "-ok", "-okdir")) {
                    val end = args.drop(i + 1).indexOfFirst { it == ";" || it == "+" || it == "\\;" }
                    val inner = if (end >= 0) args.subList(i + 1, i + 1 + end) else args.drop(i + 1)
                    // The words after `-exec` run with the found files; also guard the search roots.
                    if (inner.isNotEmpty()) {
                        val name = baseName(inner.first())
                        val innerArgs = inner.drop(1)
                        if (name in DESTRUCTIVE || name in WRITERS || name == "mv" || name == "cp") {
                            for (root in roots) path(root, destructive = name !in WRITERS)?.let { return it }
                        }
                        command(name, innerArgs.filter { it != "{}" }, depth + 1)?.let { return it }
                    }
                    i += if (end >= 0) end + 2 else args.size
                } else {
                    i++
                }
            }
            return null
        }

        private fun mountDenial(args: List<String>): Denial? {
            val options = args.indexOf("-o").let { if (it >= 0) args.getOrNull(it + 1).orEmpty() else "" }
            val remount = options.contains("remount") || args.any { it.contains("remount") }
            val operands = args.filter { !it.startsWith("-") && it != options }
            for (operand in operands) {
                if (!operand.startsWith("/")) continue
                val normalized = normalize(resolve(operand))
                if (remount && normalized == "/") return Denial(Category.SYSTEM_PARTITION, "remount /")
                path(operand, destructive = false)?.let { return it }
                if (remount && isSystemPartitionRoot(normalized)) return Denial(Category.SYSTEM_PARTITION, "remount $normalized")
            }
            return null
        }

        private fun blockToolDenial(args: List<String>): Denial? {
            for (arg in args) {
                if (!arg.startsWith("/")) continue
                val n = normalize(resolve(arg))
                if (n.startsWith("/dev/block") || n.startsWith("/dev/mem") || n.startsWith("/dev/kmem")) {
                    return Denial(Category.PARTITION, n)
                }
            }
            return null
        }

        private fun amDenial(args: List<String>): Denial? {
            val hit = args.firstOrNull { arg -> FACTORY_RESET_ACTIONS.any { arg.contains(it, ignoreCase = true) } }
            return hit?.let { Denial(Category.DEVICE_STATE, it) }
        }

        private fun pmDenial(args: List<String>): Denial? {
            val verbAt = args.indexOfFirst { !it.startsWith("-") }
            if (verbAt < 0) return null
            return packageVerb(args[verbAt].lowercase(), args.drop(verbAt + 1))
        }

        private fun cmdDenial(args: List<String>): Denial? {
            val serviceAt = args.indexOfFirst { !it.startsWith("-") }
            if (serviceAt < 0) return null
            val after = args.drop(serviceAt + 1)
            return when (args[serviceAt].lowercase()) {
                "package" -> pmDenial(after)
                "user" -> {
                    val verbAt = after.indexOfFirst { !it.startsWith("-") }
                    if (verbAt < 0) null else userVerb(after[verbAt].lowercase(), after.drop(verbAt + 1))
                }
                "recovery" -> if (after.any { it.contains("wipe", ignoreCase = true) }) {
                    Denial(Category.DEVICE_STATE, "cmd recovery wipe")
                } else {
                    null
                }
                else -> null
            }
        }

        private fun packageVerb(verb: String, rest: List<String>): Denial? {
            if (verb == "remove-user") return userVerb(verb, rest)
            if (verb !in PACKAGE_VERBS) return null
            val user = rest.windowed(2).firstOrNull { it[0] == "--user" }?.get(1)
            for (token in rest) {
                val pkg = token.substringBefore('/')
                if (pkg != "android" && !PACKAGE_NAME.matches(pkg)) continue
                if (packages.isCore(pkg)) {
                    return Denial(Category.CORE_PACKAGE, "$verb $pkg" + (user?.let { " (user $it)" } ?: ""))
                }
            }
            return null
        }

        private fun userVerb(verb: String, rest: List<String>): Denial? {
            if (verb != "remove-user" && verb != "remove" && verb != "rm") return null
            val id = rest.lastOrNull { !it.startsWith("-") }
            return if (id == "0") Denial(Category.DEVICE_STATE, "remove user 0") else null
        }

        private fun settingsDenial(args: List<String>): Denial? {
            val verbAt = args.indexOfFirst { it == "put" || it == "delete" }
            if (verbAt < 0) return null
            val after = args.drop(verbAt + 1).filter { !it.startsWith("--") }
            val key = after.getOrNull(1) ?: return null
            if (key !in PROVISION_KEYS) return null
            if (args[verbAt] == "delete") return Denial(Category.DEVICE_STATE, "settings delete $key")
            return if (after.getOrNull(2) == "0") Denial(Category.DEVICE_STATE, "settings put $key 0") else null
        }

        // ---- paths ----

        fun path(raw: String, destructive: Boolean): Denial? {
            if (raw.isEmpty()) return null
            val glob = raw.indexOfFirst { it == '*' || it == '?' || it == '[' }
            if (glob >= 0) return globDenial(raw, glob, destructive)
            val normalized = normalize(resolve(raw))
            zone(normalized)?.let { return it }
            if (destructive && normalized.lowercase() in PROTECTED_NODES) {
                return Denial(Category.DATA_SYSTEM, normalized)
            }
            return null
        }

        /** A glob is refused when anything it could match is protected. */
        private fun globDenial(raw: String, glob: Int, destructive: Boolean): Denial? {
            val literal = raw.substring(0, glob)
            var lit = normalize(resolve(literal))
            if (literal.endsWith("/") && !lit.endsWith("/")) lit += "/"
            val lower = lit.lowercase()
            zone(lit.trimEnd('/').ifEmpty { "/" })?.let { return it.copy(target = raw) }
            if (ZONES.any { it.path.startsWith(lower) }) {
                return Denial(ZONES.first { it.path.startsWith(lower) }.category, raw)
            }
            if (destructive && PROTECTED_NODES.any { it.startsWith(lower) }) return Denial(Category.DATA_SYSTEM, raw)
            return null
        }

        private fun zone(normalized: String): Denial? {
            val lower = normalized.lowercase()
            for (z in ZONES) {
                if (underZone(lower, z)) {
                    if (z.path == "/data/adb" && (lower == MINIS_DIR || lower.startsWith("$MINIS_DIR/"))) return null
                    return Denial(z.category, normalized)
                }
            }
            return null
        }

        private fun underZone(lower: String, z: Zone): Boolean =
            if (z.prefix) lower.startsWith(z.path) else lower == z.path || lower.startsWith(z.path + "/")

        private fun resolve(raw: String): String = if (raw.startsWith("/")) raw else "$cwd/$raw"
    }

    private data class Zone(val path: String, val category: Category, val prefix: Boolean = false)

    companion object {
        private const val MAX_DEPTH = 6
        private const val MINIS_DIR = "/data/adb/minis"
        private val ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")
        private val PACKAGE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

        private val ZONES = listOf(
            Zone("/dev/block", Category.PARTITION),
            Zone("/dev/mem", Category.PARTITION),
            Zone("/dev/kmem", Category.PARTITION),
            Zone("/system", Category.SYSTEM_PARTITION),
            Zone("/system_ext", Category.SYSTEM_PARTITION),
            Zone("/vendor", Category.SYSTEM_PARTITION),
            Zone("/product", Category.SYSTEM_PARTITION),
            Zone("/odm", Category.SYSTEM_PARTITION),
            Zone("/apex", Category.SYSTEM_PARTITION),
            Zone("/data/system", Category.DATA_SYSTEM, prefix = true),
            Zone("/data/misc", Category.DATA_SYSTEM, prefix = true),
            Zone("/data/vendor", Category.DATA_SYSTEM, prefix = true),
            Zone("/data/property", Category.DATA_SYSTEM),
            Zone("/data/apex", Category.DATA_SYSTEM),
            Zone("/data/unencrypted", Category.DATA_SYSTEM),
            Zone("/data/adb", Category.DATA_SYSTEM),
            Zone("/metadata", Category.DATA_SYSTEM),
            Zone("/cache/recovery", Category.DEVICE_STATE),
        )

        /** Top-level data nodes that may be written under but never removed or renamed themselves. */
        private val PROTECTED_NODES = setOf(
            "/", "/data", "/data/data", "/data/user", "/data/user_de", "/data/app", "/data/media",
        )

        private val SYSTEM_PARTITION_ROOTS = setOf("/system", "/system_ext", "/vendor", "/product", "/odm", "/apex", "/")
        private fun isSystemPartitionRoot(path: String) = path.lowercase() in SYSTEM_PARTITION_ROOTS

        private val SHELLS = setOf("sh", "bash", "mksh", "ash", "dash", "zsh", "ksh")
        private val WRAPPERS = setOf(
            "env", "nohup", "setsid", "nice", "ionice", "chrt", "taskset", "timeout", "time", "busybox",
            "toybox", "toolbox", "command", "exec", "stdbuf", "xargs", "flock", "sudo", "doas",
        )

        private val DESTRUCTIVE = setOf("rm", "rmdir", "unlink", "shred")
        private val WRITERS = setOf(
            "truncate", "touch", "mkdir", "chmod", "chown", "chgrp", "chcon", "setfattr", "restorecon",
            "mknod", "mkfifo",
        )
        private val BLOCK_TOOLS = setOf(
            "blkdiscard", "sgdisk", "sfdisk", "fdisk", "parted", "wipefs", "flash_erase", "mke2fs", "make_f2fs",
            "fsck", "e2fsck", "fsck.f2fs", "tune2fs", "resize2fs", "resize.f2fs", "fastboot", "flash_image",
            "erase_image",
        )

        private val FACTORY_RESET_ACTIONS = listOf("FACTORY_RESET", "MASTER_CLEAR", "wipe_data")
        private val PROVISION_KEYS = setOf("device_provisioned", "user_setup_complete")
        private val PACKAGE_VERBS = setOf(
            "uninstall", "disable", "disable-user", "disable-until-used", "clear", "suspend", "hide",
        )

        private fun baseName(word: String): String = word.substringAfterLast('/').lowercase()

        /** Lexical normalisation: `//`, `.`, `..`, and `/proc/<pid>/root` all resolve to the same path. */
        internal fun normalize(path: String): String {
            val parts = ArrayDeque<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(part)
                }
            }
            // /proc/<pid>|self/root/… is the root directory seen through procfs.
            if (parts.size >= 3 && parts[0] == "proc" && parts[2] == "root" &&
                (parts[1] == "self" || parts[1] == "thread-self" || parts[1].all { it.isDigit() })
            ) {
                repeat(3) { parts.removeFirst() }
            }
            return "/" + parts.joinToString("/")
        }
    }
}

/** Decides whether a package is one the phone needs to boot and be usable. */
interface CorePackageResolver {
    fun isCore(packageName: String): Boolean

    /** Rules that need no device: used on the host and as the floor on a device. */
    object StaticOnly : CorePackageResolver {
        override fun isCore(packageName: String): Boolean = CorePackageRules.isStaticCore(packageName)
    }
}

object CorePackageRules {
    /** Packages whose loss stops boot or the unlock screen on AOSP and the OEMs in use. */
    private val STATIC = setOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.providers.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.shell",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.inputdevices",
        "com.android.keychain",
        "com.android.location.fused",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.providers.media.module",
        "com.android.providers.downloads",
        "com.android.externalstorage",
        "com.android.mtp",
        "com.android.nfc",
        "com.android.bluetooth",
        // Xiaomi / HyperOS; needs a device check (see 06-CURRENT-GAPS.md).
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.miui.home",
        "com.xiaomi.xmsf",
        "com.miui.system",
        // This app.
        "llc.slacker.minis",
    )

    fun isStaticCore(packageName: String): Boolean = packageName in STATIC

    /** Facts about one installed package that the device resolver gathers. */
    data class Facts(
        val packageName: String,
        val persistent: Boolean = false,
        val sharedUserId: String? = null,
        val isCurrentHome: Boolean = false,
        val isDefaultInputMethod: Boolean = false,
    )

    fun isCore(facts: Facts): Boolean =
        isStaticCore(facts.packageName) ||
            facts.persistent ||
            facts.sharedUserId == "android.uid.system" ||
            facts.sharedUserId == "android.uid.phone" ||
            facts.isCurrentHome ||
            facts.isDefaultInputMethod
}

/**
 * Splits a shell script into simple commands without running anything. Quotes and backslashes are
 * honoured; `;`, `&`, `|`, newlines, parentheses and braces end a command, so what is inside `$( )` or
 * a subshell is checked as a command of its own. Redirection targets are kept apart from the words.
 */
internal object ShellWords {
    fun split(script: String): List<DeviceIntegrityPolicy.Simple> {
        val commands = mutableListOf<DeviceIntegrityPolicy.Simple>()
        var words = mutableListOf<String>()
        var redirects = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var pendingRedirect = false

        fun endWord() {
            if (!inWord) return
            val word = current.toString()
            current.clear()
            inWord = false
            if (pendingRedirect) {
                redirects += word
                pendingRedirect = false
            } else {
                words += word
            }
        }

        fun endCommand() {
            endWord()
            pendingRedirect = false
            if (words.isNotEmpty() || redirects.isNotEmpty()) {
                commands += DeviceIntegrityPolicy.Simple(words, redirects)
            }
            words = mutableListOf()
            redirects = mutableListOf()
        }

        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c == '\\' && i + 1 < script.length -> {
                    current.append(script[i + 1]); inWord = true; i += 2
                }
                c == '\'' -> {
                    val end = script.indexOf('\'', i + 1).let { if (it < 0) script.length else it }
                    current.append(script, i + 1, end); inWord = true; i = end + 1
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < script.length && script[j] != '"') {
                        if (script[j] == '\\' && j + 1 < script.length) j++
                        current.append(script[j]); j++
                    }
                    inWord = true; i = j + 1
                }
                c == ' ' || c == '\t' -> { endWord(); i++ }
                c == '\n' || c == ';' || c == '|' || c == '(' || c == ')' || c == '{' || c == '}' || c == '`' -> {
                    // `$(` starts a command; the `$` is left on the previous word, which is harmless.
                    endCommand(); i++
                }
                c == '&' -> {
                    if (current.endsWith(">") || (i + 1 < script.length && script[i + 1] == '>')) {
                        // `&>file`: treat as a redirect operator.
                        endWord(); pendingRedirect = true
                        i += if (i + 1 < script.length && script[i + 1] == '>') 2 else 1
                        if (i < script.length && script[i] == '>') i++
                    } else {
                        endCommand(); i++
                    }
                }
                c == '>' -> {
                    // `2>/dev/null` and `1>file`: a leading digit is the descriptor, not a word.
                    if (inWord && current.all { it.isDigit() }) { current.clear(); inWord = false } else endWord()
                    var j = i + 1
                    if (j < script.length && (script[j] == '>' || script[j] == '|')) j++
                    if (j < script.length && script[j] == '&') {
                        // `>&2`: duplicates a descriptor, no file.
                        j++
                        while (j < script.length && script[j].isDigit()) j++
                        pendingRedirect = false
                    } else {
                        pendingRedirect = true
                    }
                    i = j
                }
                c == '<' -> { endWord(); pendingRedirect = false; i++ }
                else -> { current.append(c); inWord = true; i++ }
            }
        }
        endCommand()
        return commands
    }
}
