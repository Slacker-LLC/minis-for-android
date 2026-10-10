package com.openminis.app.integrity

import com.openminis.app.runtime.ubuntu.DirectRootRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "is this package one the phone needs" from the device itself. The app has no
 * QUERY_ALL_PACKAGES, so PackageManager cannot see arbitrary packages; the facts come from one fixed,
 * read-only Root script (`dumpsys package`, the HOME resolver and the default input method).
 *
 * It fails closed: if the facts cannot be read, the package counts as core and the operation is
 * refused. Answers are cached for a few seconds, because the default launcher and keyboard can change.
 */
object RootCorePackageResolver : CorePackageResolver {
    private const val TIMEOUT_MS = 8_000L
    private const val CACHE_MS = 5_000L

    private data class Cached(val core: Boolean, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Replaceable for tests: runs the facts script and returns its stdout, or null on failure. */
    @Volatile
    internal var factsRunner: (String) -> String? = { script ->
        runBlocking(Dispatchers.IO) {
            val r = DirectRootRunner.runScript(script, TIMEOUT_MS)
            if (r.success) r.stdout else null
        }
    }

    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    override fun isCore(packageName: String): Boolean {
        if (CorePackageRules.isStaticCore(packageName)) return true
        if (!PACKAGE_NAME.matches(packageName)) return false
        val now = clock()
        cache[packageName]?.takeIf { now - it.atMs < CACHE_MS }?.let { return it.core }
        val out = factsRunner(factsScript(packageName))
        val core = if (out == null) true else CorePackageFactsParser.isCore(packageName, out)
        cache[packageName] = Cached(core, now)
        return core
    }

    internal fun clearCache() = cache.clear()

    private val PACKAGE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    internal fun factsScript(packageName: String): String {
        require(PACKAGE_NAME.matches(packageName)) { "not a package name" }
        return "dumpsys package $packageName 2>/dev/null | grep -E '^ *(flags=|pkgFlags=|sharedUser=)' | head -6; " +
            "echo __HOME__; " +
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1; " +
            "echo __IME__; " +
            "settings get secure default_input_method 2>/dev/null"
    }
}

/** Parses the output of [RootCorePackageResolver.factsScript]; pure. */
internal object CorePackageFactsParser {
    fun parse(packageName: String, output: String): CorePackageRules.Facts {
        val home = output.substringAfter("__HOME__", "").substringBefore("__IME__").trim()
        val ime = output.substringAfter("__IME__", "").trim()
        val head = output.substringBefore("__HOME__")
        val flagLines = head.lines().filter { it.trim().startsWith("flags=") || it.trim().startsWith("pkgFlags=") }
        val persistent = flagLines.any { Regex("""\bPERSISTENT\b""").containsMatchIn(it) }
        val shared = Regex("""sharedUser=SharedUserSetting\{[^}]*\s(android\.uid\.[a-z]+)/""").find(head)?.groupValues?.get(1)
        return CorePackageRules.Facts(
            packageName = packageName,
            persistent = persistent,
            sharedUserId = shared,
            isCurrentHome = home.substringBefore('/') == packageName,
            isDefaultInputMethod = ime.substringBefore('/') == packageName,
        )
    }

    fun isCore(packageName: String, output: String): Boolean =
        CorePackageRules.isCore(parse(packageName, output))
}
