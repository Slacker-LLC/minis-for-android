package com.openminis.app.provider.rules

import android.content.Context
import android.system.Os
import android.util.Log
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ProviderTransportPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loads the bounded, declarative catalog. Remote data can only change known metadata fields. */
object ModelRulesProvider {
    private const val TAG = "ModelRules"
    const val DEFAULT_REMOTE_URL =
        "https://raw.githubusercontent.com/Slacker-LLC/minis-for-android/main/src/android/app/src/main/assets/model-rules.json"
    private val ttlMillis = TimeUnit.HOURS.toMillis(24)
    private val refreshInFlight = AtomicBoolean(false)
    private val client = ProviderTransportPolicy.protectedHttpsBuilder(OkHttpClient.Builder())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var current: ModelRulesDocument = ModelRulesDocument.EMPTY
    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false
    @Volatile private var remoteUrlOverride: String? = null

    /** Must be called at app start; disk/asset reads are bounded, remote refresh is always asynchronous. */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            appContext = app
            val cacheFile = File(app.filesDir, "model-rules-cache.json")
            val cached = readCache(cacheFile)
            if (cached != null) {
                current = cached
            } else {
                current = readAsset(app) ?: ModelRulesDocument.EMPTY.also {
                    Log.e(TAG, "Bundled model-rules.json is invalid; using an empty ruleset")
                }
            }
            initialized = true
            if (cached == null || System.currentTimeMillis() - cacheFile.lastModified() >= ttlMillis) {
                refreshAsync()
            }
        }
    }

    fun staticModels(providerKey: String): List<LLMModel> =
        current.staticModels[providerKey].orEmpty().map(current::applyCapabilities)

    fun staticModel(providerKey: String, modelId: String): LLMModel? =
        current.staticModels[providerKey].orEmpty().firstOrNull { it.id == modelId }?.let(current::applyCapabilities)

    fun staticModelOrFallback(providerKey: String, modelId: String, displayName: String, provider: String): LLMModel =
        staticModel(providerKey, modelId) ?: LLMModel(modelId, displayName, provider)

    fun allStaticModels(): List<LLMModel> =
        current.staticModels.values.flatten().map(current::applyCapabilities)

    internal fun pickerFilter(providerKey: String): PickerFilter? = current.pickerFilters[providerKey]

    internal fun capabilitiesFor(modelId: String): ModelRuleSet = current.capabilitiesFor(modelId)

    fun applyCapabilities(model: LLMModel): LLMModel = current.applyCapabilities(model)

    /** Exposed for settings to supply a future remote URL; invalid schemes are rejected at fetch time. */
    fun setRemoteUrlOverrideForSettings(url: String?) {
        remoteUrlOverride = url?.trim()?.takeIf(String::isNotEmpty)
        if (initialized) refreshAsync()
    }

    internal fun isAllowedRemoteUrl(url: String): Boolean =
        runCatching { ProviderTransportPolicy.requireHttps(url, "Model rules URL") }.isSuccess

    internal fun currentDocumentForTests(): ModelRulesDocument = current

    internal fun installForTests(document: ModelRulesDocument) {
        current = document
    }

    private fun refreshAsync() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        Thread {
            try {
                refreshRemote()
            } finally {
                refreshInFlight.set(false)
            }
        }.apply {
            name = "model-rules-refresh"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun refreshRemote() {
        val url = remoteUrlOverride ?: DEFAULT_REMOTE_URL
        val httpsUrl = runCatching { ProviderTransportPolicy.requireHttps(url, "Model rules URL") }
            .getOrElse {
                Log.w(TAG, "Remote rules URL rejected: HTTPS is required")
                return
            }
        try {
            val request = Request.Builder().url(httpsUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Remote rules fetch failed: HTTP ${response.code}")
                    return
                }
                val body = response.body ?: return
                if (body.contentLength() > MODEL_RULES_MAX_BYTES) {
                    Log.w(TAG, "Remote rules rejected: file exceeds ${MODEL_RULES_MAX_BYTES} bytes")
                    return
                }
                val bytes = readBounded(body.byteStream()) ?: run {
                    Log.w(TAG, "Remote rules rejected: file exceeds ${MODEL_RULES_MAX_BYTES} bytes")
                    return
                }
                val text = bytes.toString(Charsets.UTF_8)
                val parsed = ModelRulesParser.parse(text) { Log.w(TAG, it) }
                if (parsed == null) {
                    Log.w(TAG, "Remote model rules rejected; retaining the active catalog")
                    return
                }
                val context = appContext ?: return
                if (!writeCacheAtomically(File(context.filesDir, "model-rules-cache.json"), bytes)) {
                    Log.w(TAG, "Remote model rules validated but cache write failed; retaining the active catalog")
                    return
                }
                current = parsed
                Log.i(TAG, "Updated model rules: ${parsed.rules.size} rules, ${parsed.staticModels.values.sumOf { it.size }} static models")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Remote model rules unavailable; retaining the active catalog: ${error.message}")
        }
    }

    private fun readCache(file: File): ModelRulesDocument? {
        if (!file.isFile || file.length() > MODEL_RULES_MAX_BYTES) return null
        return runCatching {
            val bytes = file.inputStream().use { readBounded(it) ?: return null }
            ModelRulesParser.parse(bytes.toString(Charsets.UTF_8)) { Log.w(TAG, it) }
        }.getOrNull()
    }

    private fun readAsset(context: Context): ModelRulesDocument? = runCatching {
        val bytes = context.assets.open("model-rules.json").use { readBounded(it) ?: return null }
        ModelRulesParser.parse(bytes.toString(Charsets.UTF_8)) { Log.w(TAG, it) }
    }.getOrNull()

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MODEL_RULES_MAX_BYTES) return null
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun writeCacheAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, "${target.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Os.rename(temp.absolutePath, target.absolutePath)
        true
    }.getOrElse { error ->
        Log.w(TAG, "Atomic model-rules cache write failed: ${error.message}")
        false
    }
}
