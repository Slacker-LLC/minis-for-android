package com.openminis.app.provider.quota

import android.content.Context
import com.openminis.app.auth.OAuthManager
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.provider.ProviderTransportPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Remaining balance / usage per provider instance, read from the service's own endpoint on demand and cached for a few
 * minutes. Nothing is fetched in the background: the provider page and the chat ask when they are on screen.
 */
object ProviderQuotaRepository {
    sealed interface State {
        data object Unsupported : State
        data object Loading : State
        /** No balance API is documented for this service: the page links to its console instead. */
        data class Console(val url: String) : State
        data class Ready(val quota: ProviderQuota) : State
        data class Failed(val message: String, val last: ProviderQuota?) : State
    }

    private const val TTL_MS = 5L * 60 * 1000
    private val states = MutableStateFlow<Map<String, State>>(emptyMap())
    val all: StateFlow<Map<String, State>> = states.asStateFlow()

    private val client: OkHttpClient by lazy {
        ProviderTransportPolicy.protectedHttpsBuilder(OkHttpClient.Builder())
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    fun stateOf(instanceId: String): State? = states.value[instanceId]

    /** Whether [instance] has something to ask. */
    fun supported(context: Context, instance: ProviderInstance): Boolean =
        ProviderQuotaApi.detect(instance, manualBearer = hasManualBearer(context, instance)) != null

    private fun hasManualBearer(context: Context, instance: ProviderInstance): Boolean =
        runCatching { !OAuthManager.forInstance(context, instance)?.loadManualBearerToken().isNullOrEmpty() }.getOrDefault(false)

    /** Reads the quota unless a fresh one is cached (or [force]). Never throws; the outcome lands in [all]. */
    suspend fun refresh(context: Context, providers: ProviderRepository, instance: ProviderInstance, force: Boolean = false) {
        val kind = ProviderQuotaApi.detect(instance, hasManualBearer(context, instance))
        if (kind == null) {
            put(instance.id, State.Unsupported)
            return
        }
        if (kind == QuotaKind.CONSOLE) {
            put(instance.id, ProviderQuotaApi.consoleUrl(instance)?.let { State.Console(it) } ?: State.Unsupported)
            return
        }
        val previous = (states.value[instance.id] as? State.Ready)?.quota
        if (!force && previous != null && System.currentTimeMillis() - previous.fetchedAtMs < TTL_MS) return
        put(instance.id, State.Loading)
        val result = withContext(Dispatchers.IO) { fetch(context, providers, instance, kind) }
        put(
            instance.id,
            result.fold(
                onSuccess = { State.Ready(it) },
                onFailure = {
                    // A relay that offers no balance endpoint is simply not one we can read: nothing to show, no error.
                    if (it is NotOffered) State.Unsupported else State.Failed(it.message ?: it::class.java.simpleName, previous)
                },
            ),
        )
    }

    private class NotOffered : Exception("the service does not offer a balance endpoint")

    /** The kinds whose credential is the service's own sign-in token, refreshed by the app's OAuth manager. */
    private val SIGN_IN_KINDS = setOf(QuotaKind.CHATGPT, QuotaKind.CLAUDE, QuotaKind.KIMI_CODE)

    /** Which of a relay's two layouts answered last time, so the next read is one request. */
    private val relayLayout = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private suspend fun fetch(context: Context, providers: ProviderRepository, instance: ProviderInstance, kind: QuotaKind): Result<ProviderQuota> =
        runCatching {
            var key = providers.loadApiKey(instance.id)
            if (kind in SIGN_IN_KINDS) {
                key = OAuthManager.forInstance(context, instance)?.validAccessToken() ?: key
            }
            if (key.isNullOrBlank()) error("no API key")
            val accountId = if (kind == QuotaKind.CHATGPT) OpenAIOAuthManager(context, instance.id).accountId else null
            val now = System.currentTimeMillis()
            if (kind == QuotaKind.RELAY) return@runCatching fetchRelay(instance, key, now)
            var response = client.newCall(ProviderQuotaApi.request(kind, instance, key, accountId)).execute()
            var code = response.code
            var body = response.body?.string().orEmpty()
            response.close()
            // OpenRouter's /credits answers only management keys; an ordinary key has its own /key.
            if (kind == QuotaKind.OPENROUTER && (code == 401 || code == 403)) {
                response = client.newCall(ProviderQuotaApi.openRouterKeyRequest(key)).execute()
                code = response.code
                body = response.body?.string().orEmpty()
                response.close()
                if (code in 200..299) {
                    return@runCatching ProviderQuotaApi.parse(kind, body, now) ?: error("this key has no spending limit to show")
                }
            }
            if (code == 401 || code == 403) error("the service refused the key (HTTP $code)")
            if (code !in 200..299) error("HTTP $code")
            ProviderQuotaApi.parse(kind, body, now) ?: error("unexpected response")
        }

    /** Sub2API's `/v1/usage`, else the OpenAI-style billing pair; both on the origin the chat requests already use. */
    private fun fetchRelay(instance: ProviderInstance, key: String, now: Long): ProviderQuota {
        val origin = ProviderQuotaApi.relayOrigin(instance) ?: throw NotOffered()
        fun get(request: okhttp3.Request): Pair<Int, String> =
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        val preferNewApi = relayLayout[instance.id] == false
        if (!preferNewApi) {
            val (code, body) = get(ProviderQuotaApi.relaySub2ApiRequest(origin, key))
            if (code in 200..299) ProviderQuotaApi.parseSub2Api(body, now)?.let { relayLayout[instance.id] = true; return it }
            if (code == 401 || code == 403) error("the service refused the key (HTTP $code)")
        }
        val (subscription, usage) = ProviderQuotaApi.relayNewApiRequests(origin, key)
        val (subCode, subBody) = get(subscription)
        if (subCode in 200..299) {
            val (usageCode, usageBody) = get(usage)
            if (usageCode in 200..299) {
                ProviderQuotaApi.parseNewApi(subBody, usageBody, now)?.let { relayLayout[instance.id] = false; return it }
            }
        }
        if (subCode == 401 || subCode == 403) error("the service refused the key (HTTP $subCode)")
        throw NotOffered()
    }

    private fun put(id: String, state: State) {
        states.value = states.value + (id to state)
    }
}
