package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * `memory.*` and `soul.*` RPC handlers for the Web Remote frontend.
 * Covers memory file management, global toggle, and SOUL.md read/write.
 */
internal object MemoryRpcMethods {

    private fun memRepo(context: Context): MemoryRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).memoryRepository

    // ── Memory Files ────────────────────────────────────────────────────────

    fun filesList(context: Context): JSONObject {
        val files = memRepo(context).listAllFiles()
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("isGlobal", f.isGlobal)
                put("modifiedDate", f.modifiedDate)
                put("fileSize", f.fileSize)
                put("preview", f.preview)
            })
        }
        return JSONObject().put("files", arr)
    }

    fun filesRead(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name", "").ifEmpty {
            throw RPCException(-32602, "Missing 'name' param")
        }
        if (name.contains("/") || name.contains("..")) {
            throw RPCException(-32602, "Invalid 'name': path traversal not allowed")
        }
        val repo = memRepo(context)
        val content = repo.readFile(name)
        val isGlobal = name == "GLOBAL.md"
        return JSONObject().apply {
            put("name", name)
            put("content", content)
            put("isGlobal", isGlobal)
        }
    }

    fun filesWrite(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name", "").ifEmpty {
            throw RPCException(-32602, "Missing 'name' param")
        }
        if (name.contains("/") || name.contains("..")) {
            throw RPCException(-32602, "Invalid 'name': path traversal not allowed")
        }
        if (!params.has("content")) {
            throw RPCException(-32602, "Missing 'content' param")
        }
        val content = params.optString("content", "")
        val repo = memRepo(context)
        if (name == "GLOBAL.md") {
            repo.saveGlobalMd(content)
        } else {
            repo.saveFile(name, content)
        }
        return JSONObject().put("ok", true)
    }

    fun filesDelete(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name", "").ifEmpty {
            throw RPCException(-32602, "Missing 'name' param")
        }
        if (name.contains("/") || name.contains("..")) {
            throw RPCException(-32602, "Invalid 'name': path traversal not allowed")
        }
        val repo = memRepo(context)
        if (!repo.deleteFile(name)) {
            throw RPCException(-32000, "Cannot delete file: $name")
        }
        return JSONObject().put("ok", true)
    }

    // ── Global Toggle ───────────────────────────────────────────────────────

    fun globalToggle(context: Context): JSONObject {
        val enabled = MemoryGlobalPrefs.isGlobalEnabled(context)
        return JSONObject().put("enabled", enabled)
    }

    fun setGlobalEnabled(context: Context, params: JSONObject): JSONObject {
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", true)
        MemoryGlobalPrefs.setGlobalEnabled(context, enabled)
        return JSONObject().apply {
            put("ok", true)
            put("enabled", enabled)
        }
    }

    // ── Soul ────────────────────────────────────────────────────────────────

    fun soulGet(context: Context): JSONObject {
        SoulStore.ensureExists(context)
        val soulFile = SoulStore.load(context)
        val meta = soulFile?.metadata ?: SoulMetadata.DEFAULT
        val body = soulFile?.body ?: ""
        return JSONObject().apply {
            put("name", meta.name)
            put("style", meta.style)
            put("lang", meta.lang)
            put("body", body)
        }
    }

    fun soulSave(context: Context, params: JSONObject): JSONObject {
        SoulStore.ensureExists(context)
        val current = SoulStore.load(context) ?: SoulFile(SoulMetadata.DEFAULT, "")
        val newMeta = current.metadata.copy(
            name = params.optString("name", "").ifEmpty { current.metadata.name },
            style = if (params.has("style")) params.optString("style", "") else current.metadata.style,
            lang = params.optString("lang", "").ifEmpty { current.metadata.lang },
        )
        val newBody = if (params.has("body")) params.optString("body", "") else current.body
        SoulStore.save(context, SoulFile(newMeta, newBody))
        return JSONObject().put("ok", true)
    }
}
