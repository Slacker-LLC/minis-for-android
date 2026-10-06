package com.openminis.app.mcp.client

import android.content.Context
import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.oauth.MCPOAuthStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What a server config means at connection time: `$$VAR` references resolved from the App's
 * environment variables, and the OAuth access token for a server signed in through Settings.
 * The resolved values exist only for the connection; nothing here writes them back to the config
 * or into logs.
 */
internal object MCPConnectionConfig {
    private const val TAG = "MCPConnectionConfig"

    /** `$$NAME` or `$${NAME}`, the form the Settings picker inserts and the prompt documents. */
    private val REFERENCE = Regex("""\$\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?""")

    /** Refresh a token this long before it expires, so a call does not start with one about to lapse. */
    private const val REFRESH_MARGIN_MS = 60_000L

    /**
     * [config] with every `$$VAR` in its url, header values, env values, command and args replaced by
     * the App variable's value. A reference to a variable that is not set fails the connection with
     * its name rather than sending the literal text (or an empty credential) to the server.
     */
    fun expand(config: MCPServerConfig, variables: Map<String, String>): MCPServerConfig {
        fun resolve(value: String): String = REFERENCE.replace(value) { match ->
            val name = match.groupValues[1]
            variables[name] ?: throw MCPTransportException(
                "MCP server ${config.id}: environment variable $name is not set (referenced as \$\$$name)",
            )
        }
        return config.copy(
            url = config.url?.let(::resolve),
            headers = config.headers.mapValues { (_, v) -> resolve(v) },
            command = config.command?.let(::resolve),
            args = config.args.map(::resolve),
            env = config.env.mapValues { (_, v) -> resolve(v) },
        )
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The access token to send for [config], refreshed (and stored) first when it is about to expire.
     * Null when the server was never signed in. When the token has expired and cannot be refreshed, the connection
     * fails with a message telling the user to sign in again instead of sending a dead token.
     */
    fun accessToken(context: Context, config: MCPServerConfig, now: Long = System.currentTimeMillis()): String? {
        if (config.isStdio) return null
        return resolveToken(
            config = config,
            stored = MCPOAuthStore.tokens(context, config.id),
            clientSecret = MCPOAuthStore.clientSecret(context, config.id),
            now = now,
            save = { MCPOAuthStore.setTokens(context, config.id, it) },
        )
    }

    /** [accessToken] with the token store passed in. */
    internal fun resolveToken(
        config: MCPServerConfig,
        stored: MCPOAuthStore.StoredTokens?,
        clientSecret: String?,
        now: Long,
        save: (MCPOAuthStore.StoredTokens) -> Unit,
    ): String? {
        if (stored == null) return null
        if (stored.expiresAtMs <= 0L || now < stored.expiresAtMs - REFRESH_MARGIN_MS) return stored.accessToken
        val oauth = config.oauth
        val refreshToken = stored.refreshToken
        if (oauth == null || !oauth.isConfigured || refreshToken.isNullOrBlank()) {
            throw MCPTransportException("MCP server ${config.id}: OAuth sign-in expired; sign in again in Settings")
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", oauth.clientId)
        clientSecret?.let { form.add("client_secret", it) }
        com.openminis.app.mcp.oauth.McpPkce.canonicalResourceUri(config.url)?.let { form.add("resource", it) }
        val request = Request.Builder().url(oauth.tokenEndpoint).post(form.build()).build()
        val refreshed = runCatching {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(body)
                val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: error("no access_token")
                val expiresIn = json.optLong("expires_in", 0L)
                MCPOAuthStore.StoredTokens(
                    accessToken = access,
                    // Servers may rotate the refresh token or keep the old one valid.
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
                    expiresAtMs = if (expiresIn > 0) now + expiresIn * 1000 else 0L,
                )
            }
        }.getOrElse { error ->
            AppLogger.warning(TAG, "token refresh for '${config.id}' failed: ${error.message}")
            throw MCPTransportException("MCP server ${config.id}: OAuth token refresh failed; sign in again in Settings")
        }
        save(refreshed)
        AppLogger.info(TAG, "refreshed OAuth token for '${config.id}'")
        return refreshed.accessToken
    }
}
