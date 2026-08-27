package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress

/**
 * Central transport boundary for provider and security-sensitive traffic.
 *
 * The Android manifest must keep platform cleartext enabled because a user may
 * intentionally point an API-key provider at a LAN endpoint such as
 * http://192.168.1.20:11434. That platform opt-in is therefore NOT the security
 * policy. This object is: HTTPS is the default, and HTTP is accepted only when
 * it is an explicitly configured API-key endpoint on a local/private network.
 * OAuth/account flows never get that exception.
 *
 * Redirect handling is deliberately conservative:
 * - HTTPS clients may follow same-scheme redirects, but scheme redirects are
 *   disabled so HTTPS -> HTTP can never downgrade credentials.
 * - Approved HTTP clients do not auto-follow redirects at all. A redirect can
 *   therefore never move a cleartext credential outside the approved origin.
 */
object ProviderTransportPolicy {

    class Violation(message: String) : IllegalArgumentException(message)

    /** True only for an explicit http:// URL. Invalid/blank strings are false. */
    fun isCleartextHttp(rawUrl: String?): Boolean =
        rawUrl?.trim()?.toHttpUrlOrNull()?.scheme == "http"

    /**
     * True when [rawUrl] is an HTTP URL whose host is plausibly local/private.
     * No DNS lookup is performed for hostnames: single-label names, .local and
     * .home.arpa are treated as local; numeric addresses are checked against
     * loopback/private/link-local/CGNAT/IPv6-ULA ranges.
     */
    fun isAllowedCleartextEndpoint(rawUrl: String?): Boolean {
        val url = rawUrl?.trim()?.toHttpUrlOrNull() ?: return false
        return url.scheme == "http" && isLocalOrPrivateHost(url.host)
    }

    /**
     * Validate the resolved provider base before a provider object is created.
     * [effectiveBaseUrl] may include an auto-appended /v1; origin comparison is
     * therefore scheme/host/port based rather than string based.
     */
    fun requireAllowedInstanceBase(
        instance: ProviderInstance,
        effectiveBaseUrl: String?,
    ): HttpUrl? {
        if (effectiveBaseUrl == null) return null
        val resolved = parseHttpUrl(effectiveBaseUrl, "provider base URL")
        if (resolved.isHttps) return resolved
        if (resolved.scheme != "http") {
            throw Violation("Provider endpoints must use HTTPS (or an explicitly configured HTTP LAN endpoint).")
        }

        if (instance.credentialType != ProviderCredential.apiKey) {
            throw Violation("OAuth/provider-account endpoints must use HTTPS; cleartext HTTP is not allowed.")
        }

        val configured = instance.customBaseURL
            ?.trim()
            ?.toHttpUrlOrNull()
            ?: throw Violation("Cleartext HTTP is allowed only for an explicitly configured custom provider endpoint.")

        if (configured.scheme != "http" || !sameOrigin(configured, resolved)) {
            throw Violation("Cleartext HTTP is allowed only for the configured provider origin.")
        }
        if (!isLocalOrPrivateHost(configured.host)) {
            throw Violation("Cleartext HTTP is allowed only for local/private provider hosts; use HTTPS for public hosts.")
        }
        return resolved
    }

    /**
     * Apply redirect policy to a provider OkHttp builder.
     *
     * HTTPS keeps normal same-scheme redirects while disabling scheme changes.
     * HTTP is accepted only for a local/private base and disables redirects
     * entirely so an approved origin cannot bounce a credential elsewhere.
     */
    fun configureClient(
        builder: OkHttpClient.Builder,
        baseUrl: String,
    ): OkHttpClient.Builder {
        val base = parseHttpUrl(baseUrl, "provider base URL")
        if (base.scheme != "https" && base.scheme != "http") {
            throw Violation("Unsupported provider URL scheme: ${base.scheme}")
        }
        if (base.scheme == "http" && !isLocalOrPrivateHost(base.host)) {
            throw Violation("Cleartext HTTP is allowed only for local/private provider hosts.")
        }
        builder.followSslRedirects(false)
        builder.followRedirects(base.isHttps)
        return builder
    }

    /** Build a default OkHttp client carrying the provider redirect policy. */
    fun clientForBase(baseUrl: String): OkHttpClient =
        configureClient(OkHttpClient.Builder(), baseUrl).build()

    /**
     * Configure a client for flows that must never send cleartext: OAuth token
     * exchange/refresh, update metadata/downloads, and other credential-bearing
     * cloud calls. The interceptor blocks a direct http:// request; disabling
     * SSL redirects blocks HTTPS -> HTTP follow-ups. HTTPS -> HTTPS redirects
     * remain available for normal CDN/API routing.
     */
    fun protectedHttpsBuilder(
        builder: OkHttpClient.Builder = OkHttpClient.Builder(),
    ): OkHttpClient.Builder = builder
        .followRedirects(true)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            if (!chain.request().url.isHttps) {
                throw IOException("Cleartext HTTP is blocked for this security-sensitive network flow")
            }
            chain.proceed(chain.request())
        }

    fun protectedHttpsClient(): OkHttpClient = protectedHttpsBuilder().build()

    /** Require an arbitrary security-sensitive URL to be HTTPS before use. */
    fun requireHttps(rawUrl: String, label: String = "URL"): HttpUrl {
        val url = parseHttpUrl(rawUrl, label)
        if (!url.isHttps) throw Violation("$label must use HTTPS.")
        return url
    }

    /**
     * Validate a URL returned by a provider response (for example an image URL)
     * before issuing a second request to it.
     *
     * A secure provider may return another HTTPS URL. A cleartext provider may
     * return HTTP only on its exact approved local/private origin. This prevents
     * an upstream response from turning a provider call into an arbitrary
     * cleartext fetch elsewhere.
     */
    fun requireAllowedSecondaryUrl(baseUrl: String, targetUrl: String): HttpUrl {
        val base = parseHttpUrl(baseUrl, "provider base URL")
        val target = parseHttpUrl(targetUrl, "provider-returned URL")
        if (target.isHttps) return target
        if (
            target.scheme == "http" &&
            base.scheme == "http" &&
            isLocalOrPrivateHost(base.host) &&
            sameOrigin(base, target)
        ) {
            return target
        }
        throw Violation("Provider-returned cleartext URL is outside the approved provider origin.")
    }

    /** Pure helper used by tests and diagnostics. */
    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun isLocalOrPrivateHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (!host.contains('.') && !host.contains(':')) return true
        if (host.endsWith(".local") || host.endsWith(".home.arpa")) return true

        parseIpv4(host)?.let { octets ->
            val a = octets[0]
            val b = octets[1]
            return when {
                a == 10 -> true
                a == 127 -> true
                a == 169 && b == 254 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                // RFC 6598 shared address space, commonly used by Tailscale/
                // carrier/private overlays for on-device local services.
                a == 100 && b in 64..127 -> true
                else -> false
            }
        }

        // A ':' means this is an IPv6 literal, not a DNS hostname, so parsing it
        // cannot trigger a DNS lookup. Java classifies loopback/link/site-local;
        // ULA fc00::/7 is checked explicitly because isSiteLocalAddress does not
        // cover the modern ULA range.
        if (host.contains(':')) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
                return true
            }
            val bytes = address.address
            if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[i] = value
        }
        return out
    }

    private fun parseHttpUrl(raw: String, label: String): HttpUrl =
        raw.trim().toHttpUrlOrNull()
            ?: throw Violation("Invalid $label: '$raw'")
}
