package io.github.slackerllc.minis.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` (wrapped as
 * `GeneralSecurityException`) on launch when the AndroidKeystore master
 * key can no longer decrypt the Tink keyset blob — observed on Samsung
 * One UI / Android 16 after backup-restore or biometric re-enroll. The
 * exception bubbles to the main thread and the app dies in a relaunch
 * loop because every cold start hits the same lazy init.
 *
 * Strategy (FAIL-CLOSED, no plaintext escape hatch):
 *  1. Try the normal create.
 *  2. On any crypto error: drop ONLY this store's own encrypted XML file
 *     (NOT the shared Tink keyset file and NOT the global master-key
 *     alias — deleting those would cascade-fail every other encrypted
 *     store in the process), then retry once. The user loses this
 *     store's credentials (they need to re-paste their API key /
 *     re-login OAuth) but the app boots.
 *  3. If recreate still fails: return an EMPTY IN-MEMORY store so every
 *     caller reads defaults. This is intentionally NOT a plain-text
 *     SharedPreferences: credential material is never written to disk
 *     unencrypted, and the security-sensitive feature that depends on
 *     the store simply reports "not configured"
 *     and refuses to start. All callers of safeCreate hold credentials
 *     (provider API keys, OAuth tokens, remote-control secrets), so a
 *     plaintext fallback would hand every one of them to anything that
 *     can read app-private files or a device backup.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        runCatching { return build(context, fileName) }
            .onFailure { Log.w(TAG, "first create($fileName) failed: ${it.message}") }

        // Wipe only this store's own encrypted XML. The Tink keyset blob
        // (__androidx_security_crypto_encrypted_prefs__.xml) and the
        // AndroidKeyStore master-key alias are SHARED by every encrypted
        // store; deleting them here would make every other store fail on
        // its next create and cascade the whole app into failure.
        runCatching {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "$fileName.xml").delete()
        }.onFailure { Log.w(TAG, "wipe prefs file failed: ${it.message}") }

        runCatching { return build(context, fileName) }
            .onFailure {
                Log.e(TAG, "rebuild($fileName) after wipe failed: ${it.message}", it)
            }

        // FAIL-CLOSED: never write credentials to a plaintext file.
        // The in-memory store below is empty and read-only from the
        // caller's perspective; anything written to it is lost on the
        // next process start, which is the correct failure mode for
        // "the keystore cannot protect this data right now".
        Log.e(
            TAG,
            "encrypted prefs unavailable for $fileName — returning empty in-memory store " +
                "(credentials lost, NO plaintext written, feature will report unconfigured)",
        )
        return InMemoryPrefs(fileName)
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/**
 * Empty in-memory [SharedPreferences] used only as the fail-closed last
 * resort. Reads return defaults; writes are accepted (so callers do not
 * crash) but never persisted anywhere. A new process starts with a fresh
 * empty store again — the intended "needs re-entry" state.
 */
private class InMemoryPrefs(private val name: String) : SharedPreferences {
    private val TAG = "EncryptedPrefsFactory.InMemory"
    private val lock = Any()
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { LinkedHashMap(values) }
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key] as? String } ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) { values[key] as? MutableSet<String> } ?: defValues
    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { values[key] as? Int } ?: defValue
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { values[key] as? Long } ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { values[key] as? Float } ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean } ?: defValue
    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
        override fun clear(): SharedPreferences.Editor { pending.clear(); removals.addAll(values.keys); return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            synchronized(lock) {
                for (k in removals) values.remove(k)
                for ((k, v) in pending) values[k] = v
            }
            Log.w(TAG, "write to in-memory fallback prefs '$name' discarded (not persisted)")
        }
    }
}
