package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * `skills.*` RPC handlers for the Web Remote frontend.
 * Read-only listing plus toggle/delete — creation and import stay on-device.
 */
internal object SkillRpcMethods {

    private fun repo(context: Context): SkillRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).skillRepository

    fun list(context: Context): JSONObject {
        val skills = repo(context).skills.value
        val arr = JSONArray()
        for (s in skills) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("description", s.description)
                put("version", s.version)
                put("importSource", s.importSource.value)
                put("isEnabled", s.isEnabled)
                put("installedAt", s.installedAt)
                put("updatedAt", s.updatedAt)
                put("useCount", s.useCount)
            })
        }
        return JSONObject().put("skills", arr)
    }

    fun get(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        val r = repo(context)
        val skill = r.skills.value.find { it.id == skillId }
            ?: throw RPCException(-32602, "Skill not found: $skillId")
        return JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("version", skill.version)
            put("importSource", skill.importSource.value)
            put("isEnabled", skill.isEnabled)
            put("installedAt", skill.installedAt)
            put("updatedAt", skill.updatedAt)
            put("useCount", skill.useCount)
            put("body", skill.body)
        }
    }

    fun toggle(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", true)
        val r = repo(context)
        if (r.skills.value.none { it.id == skillId }) {
            throw RPCException(-32602, "Skill not found: $skillId")
        }
        r.setEnabled(skillId, enabled)
        return JSONObject().put("ok", true)
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        val r = repo(context)
        if (r.skills.value.none { it.id == skillId }) {
            throw RPCException(-32602, "Skill not found: $skillId")
        }
        r.delete(skillId)
        return JSONObject().put("ok", true)
    }
}
