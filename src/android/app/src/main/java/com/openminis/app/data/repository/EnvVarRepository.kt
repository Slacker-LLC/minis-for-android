package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository(private val context: Context) {

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    /**
     * True when the metadata file exists but could not be read. The list is then empty only
     * because loading failed, so writing a new list would overwrite the real one: edits are refused.
     */
    @Volatile
    private var metadataUnreadable = false

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: self-healing wrapper.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
    }

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE)

    init {
        loadMetadata()
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    /**
     * A value is stored exactly as given or not at all. Unicode text, tabs and newlines (a PEM, a
     * path with CJK characters) are fine: the shell receives them single-quoted. What cannot travel
     * is NUL and the other control characters, which can break shell env injection; those are
     * refused rather than silently dropped.
     */
    fun isValidValue(value: String): Boolean =
        value.none { ch ->
            Character.isISOControl(ch) && ch != '\t' && ch != '\n'
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    // -- CRUD --

    fun add(key: String, value: String, note: String = ""): Boolean = synchronized(this) {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false
        if (!isValidValue(value) || metadataUnreadable) return false

        val before = _entries.value
        val entry = EnvVarEntry(key = normalizedKey, note = note.trim())
        _entries.value = before + entry
        if (!encryptedPrefs.edit().putString(normalizedKey, value).commit() || !saveMetadata()) {
            // Neither half may survive alone: a value without its metadata is invisible, metadata
            // without its value is an empty variable.
            _entries.value = before
            encryptedPrefs.edit().remove(normalizedKey).commit()
            return false
        }
        Log.i(TAG, "Added env var: $normalizedKey")
        return true
    }

    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean = synchronized(this) {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (!isValidValue(newValue) || metadataUnreadable) return false

        val current = _entries.value.find { it.id == id } ?: return false

        // Check for duplicate (excluding self)
        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        val before = _entries.value
        val previousValue = encryptedPrefs.getString(current.key, null)
        _entries.value = before.map {
            if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it
        }
        val editor = encryptedPrefs.edit()
        if (current.key != normalizedKey) editor.remove(current.key)
        editor.putString(normalizedKey, newValue)
        if (!editor.commit() || !saveMetadata()) {
            _entries.value = before
            val restore = encryptedPrefs.edit()
            if (current.key != normalizedKey) restore.remove(normalizedKey)
            if (previousValue != null) restore.putString(current.key, previousValue) else restore.remove(current.key)
            restore.commit()
            return false
        }
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    fun delete(id: String): Boolean = synchronized(this) {
        val entry = _entries.value.find { it.id == id } ?: return false
        if (metadataUnreadable) return false
        val before = _entries.value
        val previousValue = encryptedPrefs.getString(entry.key, null)
        _entries.value = before.filter { it.id != id }
        if (!saveMetadata()) {
            _entries.value = before
            return false
        }
        encryptedPrefs.edit().remove(entry.key).commit()
        Log.i(TAG, "Deleted env var: ${entry.key}")
        return true
    }

    fun getValue(key: String): String? = encryptedPrefs.getString(key, null)

    /** False when the saved list could not be read; the editor shows that instead of an empty list. */
    val isReadable: Boolean get() = !metadataUnreadable

    /**
     * Returns all env vars as a Map<String, String> for sandbox injection.
     * Reads directly from storage to be thread-safe.
     */
    fun allAsDict(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            val value = encryptedPrefs.getString(entry.key, null)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    // -- Metadata Persistence --

    private fun saveMetadata(): Boolean {
        return try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            // Write beside the file and rename, so an interrupted write leaves the old list.
            val temp = File(metadataFile.parentFile, METADATA_FILE + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(metadataFile)) {
                temp.delete()
                throw java.io.IOException("cannot replace $METADATA_FILE")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
            false
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) return
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                entries.add(EnvVarEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                ))
            }
            _entries.value = entries
        } catch (e: Exception) {
            metadataUnreadable = true
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}
