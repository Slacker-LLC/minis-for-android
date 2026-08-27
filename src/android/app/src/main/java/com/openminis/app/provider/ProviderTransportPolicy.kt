package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Central transport boundary for provider and security-sensitive traffic.
 *
 * The Android manifest must keep platform cleartext enabled because a user may
 * intentionally point an API-key provider at a LAN endpoint such as
 * http://192.168.1.20:11434. That platform opt-in is therefore NOT the security
 * policy. This object is: HTTPS is the default, and HTTP is accepted only when
 * it is the instance's explicitly configured custom base URL in API-key mode.
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
        return resolved
    }

    /**
     * Apply redirect policy to a provider OkHttp builder.
     *
     * HTTPS keeps normal same-scheme redirects while disabling scheme changes.
     * HTTP disables redirects entirely so an approved local origin cannot bounce
     * a credential to another host/path authority.
     */
    fun configureClient(
        builder: OkHttpClient.Builder,
        baseUrl: String,
    ): OkHttpClient.Builder {
        val base = parseHttpUrl(baseUrl, "provider base URL")
        if (base.scheme != "https" && base.scheme != "http") {
            throw Violation("Unsupported provider URL scheme: ${base.scheme}")
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
     * return HTTP only on its exact approved origin. This prevents an upstream
     * response from turning an authenticated provider call into an arbitrary
     * cleartext fetch elsewhere.
     */
    fun requireAllowedSecondaryUrl(baseUrl: String, targetUrl: String): HttpUrl {
        val base = parseHttpUrl(baseUrl, "provider base URL")
        val target = parseHttpUrl(targetUrl, "provider-returned URL")
        if (target.isHttps) return target
        if (target.scheme == "http" && base.scheme == "http" && sameOrigin(base, target)) {
            return target
        }
        throw Violation("Provider-returned cleartext URL is outside the approved provider origin.")
    }

    /** Pure helper used by tests and diagnostics. */
    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun parseHttpUrl(raw: String, label: String): HttpUrl =
        raw.trim().toHttpUrlOrNull()
            ?: throw Violation("Invalid $label: '$raw'")
}
