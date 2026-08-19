package com.openminis.app.remote

import android.content.Context
import android.util.Base64
import com.openminis.app.util.EncryptedPrefsFactory
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Single source of truth for Web Remote configuration.
 *
 * Non-secret switches live in ordinary SharedPreferences. Credentials and
 * tunnel tokens live in EncryptedSharedPreferences through the same helper the
 * provider/API-key stack already uses, so Web Remote does not invent a second
 * credential-storage mechanism.
 */
object RemoteAccessPrefs {
    private const val PREFS = "remote_access"
    private const val SECRET_PREFS = "remote_access_secrets"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token" // legacy/plain migration source only
    private const val KEY_LAN_ACCESS = "lan_access"
    private const val KEY_USERNAME = "username"
    private const val KEY_TUNNEL_ENABLED = "cloudflare_tunnel_enabled"
    private const val KEY_TUNNEL_HOSTNAME = "cloudflare_tunnel_hostname"

    private const val SECRET_API_TOKEN = "api_token"
    private const val SECRET_PASSWORD_SALT = "login_password_salt"
    private const val SECRET_PASSWORD_HASH = "login_password_hash"
    private const val SECRET_TUNNEL_TOKEN = "cloudflare_tunnel_token"

    private const val PBKDF2_ITERATIONS = 210_000
    private const val PBKDF2_BITS = 256

    const val DEFAULT_PORT = 8765
    const val DEFAULT_USERNAME = "admin"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)

    fun setPort(context: Context, port: Int) =
        prefs(context).edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()

    /**
     * LAN exposure is opt-in. The default bind address is loopback because a
     * Cloudflare connector on the same phone does not need 0.0.0.0, and a
     * loopback default removes an entire unauthenticated network surface.
     */
    fun lanAccessEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAN_ACCESS, false)

    fun setLanAccessEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_LAN_ACCESS, enabled).apply()

    fun bindHost(context: Context): String = if (lanAccessEnabled(context)) "0.0.0.0" else "127.0.0.1"

    fun username(context: Context): String =
        prefs(context).getString(KEY_USERNAME, DEFAULT_USERNAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_USERNAME

    fun setUsername(context: Context, username: String) {
        val clean = username.trim().take(64)
        require(clean.length >= 3) { "Username must be at least 3 characters" }
        prefs(context).edit().putString(KEY_USERNAME, clean).apply()
    }

    fun hasPassword(context: Context): Boolean {
        val s = secrets(context)
        return !s.getString(SECRET_PASSWORD_SALT, null).isNullOrBlank() &&
            !s.getString(SECRET_PASSWORD_HASH, null).isNullOrBlank()
    }

    fun setPassword(context: Context, password: CharArray) {
        require(password.size >= 10) { "Password must be at least 10 characters" }
        try {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val hash = derivePassword(password, salt)
            secrets(context).edit()
                .putString(SECRET_PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(SECRET_PASSWORD_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .apply()
        } finally {
            password.fill('\u0000')
        }
    }

    fun verifyLogin(context: Context, suppliedUsername: String, suppliedPassword: CharArray): Boolean {
        if (!constantTimeStringEquals(username(context), suppliedUsername.trim())) {
            // Still derive once to make username probing less useful when a
            // password record exists. This is intentionally not a perfect
            // timing oracle defence; the HTTP rate limiter is the outer guard.
            if (hasPassword(context)) {
                val s = secrets(context)
                val salt = runCatching {
                    Base64.decode(s.getString(SECRET_PASSWORD_SALT, ""), Base64.NO_WRAP)
                }.getOrDefault(ByteArray(16))
                runCatching { derivePassword(suppliedPassword, salt) }
            }
            suppliedPassword.fill('\u0000')
            return false
        }

        val s = secrets(context)
        val saltText = s.getString(SECRET_PASSWORD_SALT, null)
        val hashText = s.getString(SECRET_PASSWORD_HASH, null)
        if (saltText.isNullOrBlank() || hashText.isNullOrBlank()) {
            suppliedPassword.fill('\u0000')
            return false
        }
        return try {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            val actual = derivePassword(suppliedPassword, salt)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        } finally {
            suppliedPassword.fill('\u0000')
        }
    }

    /** Emergency/CLI bearer credential. The browser uses a session cookie. */
    fun token(context: Context): String {
        val secret = secrets(context)
        val existing = secret.getString(SECRET_API_TOKEN, null)?.trim().orEmpty()
        if (existing.length >= 32) return existing

        // Migrate the pre-login implementation's plain token once.
        val old = prefs(context).getString(KEY_TOKEN, null)?.trim().orEmpty()
        val chosen = old.takeIf { it.length >= 32 } ?: generateToken()
        secret.edit().putString(SECRET_API_TOKEN, chosen).apply()
        prefs(context).edit().remove(KEY_TOKEN).apply()
        return chosen
    }

    fun regenerateToken(context: Context): String = generateToken().also {
        secrets(context).edit().putString(SECRET_API_TOKEN, it).apply()
    }

    fun cloudflareTunnelEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TUNNEL_ENABLED, false)

    fun setCloudflareTunnelEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_TUNNEL_ENABLED, enabled).apply()

    /** Display-only hostname. The authoritative hostname route lives at Cloudflare. */
    fun cloudflareHostname(context: Context): String =
        prefs(context).getString(KEY_TUNNEL_HOSTNAME, "")?.trim().orEmpty()

    fun setCloudflareHostname(context: Context, hostname: String) {
        val clean = hostname.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').take(253)
        prefs(context).edit().putString(KEY_TUNNEL_HOSTNAME, clean).apply()
    }

    fun cloudflareTunnelToken(context: Context): String? =
        secrets(context).getString(SECRET_TUNNEL_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun hasCloudflareTunnelToken(context: Context): Boolean = cloudflareTunnelToken(context) != null

    fun setCloudflareTunnelToken(context: Context, token: String?) {
        val edit = secrets(context).edit()
        val clean = token?.trim().orEmpty()
        if (clean.isEmpty()) edit.remove(SECRET_TUNNEL_TOKEN) else edit.putString(SECRET_TUNNEL_TOKEN, clean)
        edit.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secrets(context: Context) =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, SECRET_PREFS)

    private fun derivePassword(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeStringEquals(a: String, b: String): Boolean {
        val aa = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aa, bb)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
