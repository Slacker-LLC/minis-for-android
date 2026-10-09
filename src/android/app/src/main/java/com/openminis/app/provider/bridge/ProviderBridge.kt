package com.openminis.app.provider.bridge

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * The providers added in the app (their API keys) made usable by the coding agents in the sandbox - pi, Claude Code,
 * Codex - without logging in again: the keys go to the environment variables each of them reads
 * (pi: `docs/providers.md` lists them; Claude Code: `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL`; Codex: `OPENAI_API_KEY`).
 *
 * Switched on by the one-tap authorize switch and only then. API-key providers only: a ChatGPT / Claude sign-in token is
 * rotated by whoever refreshes it, so two programs holding it would invalidate each other.
 *
 * Two carriers, one source: a file in the sandbox home (`~/.minis/agent-env.sh`, sourced by a managed block in `.profile` and
 * `.bashrc`, for the Terminal and the agent's terminals) and [currentEnv] (injected into the agent's own shells).
 */
object ProviderBridge {
    private const val PREFS = "minis_provider_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val DIR = ".minis"
    private const val ENV_FILE = "agent-env.sh"
    internal const val BLOCK_BEGIN = "# >>> minis provider env (managed by Minis; switch off one-tap authorize to remove) >>>"
    internal const val BLOCK_END = "# <<< minis provider env <<<"
    internal const val BLOCK_BODY = "[ -f \"\$HOME/.minis/agent-env.sh\" ] && . \"\$HOME/.minis/agent-env.sh\""

    @Volatile private var env: Map<String, String> = emptyMap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** The variables for the agent's own shells; empty while the bridge is off. */
    fun currentEnv(): Map<String, String> = env

    /** Values to keep out of anything the model reads (Privacy Mode). */
    fun secrets(): Collection<String> = env.values

    fun enable(context: Context, providers: ProviderRepository) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
        scope.launch { sync(context, providers) }
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        env = emptyMap()
        scope.launch { runCatching { UbuntuPaths.initialize(context); removeFiles(UbuntuPaths.hostHome) } }
    }

    /** Keeps the files and [currentEnv] in step with the providers while the bridge is on. Call once at startup. */
    fun attach(context: Context, providers: ProviderRepository) {
        val app = context.applicationContext
        val request = { requestSync(app, providers) }
        scope.launch { providers.config.collect { request() } }
        // A key saved or removed does not change the config, only the encrypted store.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("apikey_") == true) request()
        }
        providers.encryptedPrefs.registerOnSharedPreferenceChangeListener(listener)
        keepAlive = listener
    }

    @Volatile private var keepAlive: Any? = null

    private fun requestSync(context: Context, providers: ProviderRepository) {
        if (!isEnabled(context)) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(600)
            sync(context, providers)
        }
    }

    private fun sync(context: Context, providers: ProviderRepository) {
        val next = envFor(providers.config.value.instances) { id -> providers.loadApiKey(id) }
        env = next
        runCatching {
            UbuntuPaths.initialize(context)
            writeFiles(UbuntuPaths.hostHome, next)
        }
    }

    /**
     * The variables for [instances]: for each service the first enabled API-key provider that has a key. An official endpoint
     * wins over a relay for OpenAI; a relay (custom base) exports its base URL together with its key so the two always belong
     * together. Providers signed in with OAuth are left out.
     */
    internal fun envFor(instances: List<ProviderInstance>, keyOf: (String) -> String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun usable(i: ProviderInstance): String? =
            i.takeIf { it.isEnabled && it.credentialType == ProviderCredential.apiKey }
                ?.let { keyOf(it.id) }?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c == '\n' || c == '\r' || c == '\u0000' } }

        val openAiRelay = ArrayList<Pair<ProviderInstance, String>>()
        for (instance in instances) {
            val key = usable(instance) ?: continue
            val host = instance.customBaseURL?.toHttpUrlOrNull()?.host
            when (instance.providerType) {
                ProviderType.anthropic -> if ("ANTHROPIC_API_KEY" !in out) {
                    out["ANTHROPIC_API_KEY"] = key
                    anthropicBase(instance.customBaseURL)?.let { out["ANTHROPIC_BASE_URL"] = it }
                }
                ProviderType.gemini -> out.putIfAbsent("GEMINI_API_KEY", key)
                ProviderType.openRouter -> out.putIfAbsent("OPENROUTER_API_KEY", key)
                ProviderType.xAI -> out.putIfAbsent("XAI_API_KEY", key)
                ProviderType.openAI, ProviderType.openAIResponses -> when {
                    host == "api.deepseek.com" -> out.putIfAbsent("DEEPSEEK_API_KEY", key)
                    host == "api.moonshot.cn" || host == "api.moonshot.ai" -> out.putIfAbsent("MOONSHOT_API_KEY", key)
                    instance.customBaseURL == null -> out.putIfAbsent("OPENAI_API_KEY", key)
                    else -> openAiRelay.add(instance to key)
                }
                else -> Unit
            }
        }
        if ("OPENAI_API_KEY" !in out) {
            openAiRelay.firstOrNull { (instance, _) -> instance.effectiveBaseURL?.startsWith("https://") == true }?.let { (instance, key) ->
                out["OPENAI_API_KEY"] = key
                out["OPENAI_BASE_URL"] = instance.effectiveBaseURL!!
            }
        }
        return out
    }

    /** Claude Code wants the origin the `/v1/messages` path hangs off, not the `/v1` the app appends itself. */
    internal fun anthropicBase(customBase: String?): String? {
        val base = customBase?.trim()?.trimEnd('/')?.removeSuffix("/v1")?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return base.takeIf { it.startsWith("https://") }
    }

    internal fun renderEnvFile(env: Map<String, String>): String = buildString {
        append("# Managed by Minis (one-tap authorize). Rewritten whenever a provider or its key changes.\n")
        for ((name, value) in env) append("export ").append(name).append('=').append(shellQuote(value)).append('\n')
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** [existing] with the managed block replaced by [body], or removed when [body] is null; text outside it is untouched. */
    internal fun withManagedBlock(existing: String, body: String?): String {
        val begin = existing.indexOf(BLOCK_BEGIN)
        val end = existing.indexOf(BLOCK_END)
        val without = if (begin >= 0 && end > begin) {
            existing.removeRange(begin, end + BLOCK_END.length).let { text ->
                // Drop the blank line the block leaves behind, but only the one it added.
                text.replace("\n\n\n", "\n\n").trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n" }
            }
        } else existing
        if (body == null) return without
        val block = "$BLOCK_BEGIN\n$body\n$BLOCK_END\n"
        return if (without.isEmpty()) block else without.trimEnd('\n') + "\n\n" + block
    }

    private fun writeFiles(homePath: String, env: Map<String, String>) {
        val home = File(homePath)
        val dir = File(home, DIR).apply { mkdirs() }
        val file = File(dir, ENV_FILE)
        file.writeText(renderEnvFile(env))
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        for (name in listOf(".profile", ".bashrc")) {
            val target = File(home, name)
            val current = if (target.isFile) target.readText() else ""
            val updated = withManagedBlock(current, BLOCK_BODY)
            if (updated != current) target.writeText(updated)
        }
    }

    private fun removeFiles(homePath: String) {
        val home = File(homePath)
        File(File(home, DIR), ENV_FILE).delete()
        for (name in listOf(".profile", ".bashrc")) {
            val target = File(home, name)
            if (!target.isFile) continue
            val current = target.readText()
            val updated = withManagedBlock(current, null)
            if (updated != current) target.writeText(updated)
        }
    }
}
