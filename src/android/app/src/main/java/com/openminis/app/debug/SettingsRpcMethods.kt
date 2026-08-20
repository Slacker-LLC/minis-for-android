package com.openminis.app.debug

import android.content.Context
import com.openminis.app.remote.RemotePermissionPolicy
import org.json.JSONObject

/**
 * `settings.permissionPreset.*` / `settings.sandbox.get` — Web Remote
 * permission presets (DeepSeek Harness style single selector).
 */
internal object SettingsRpcMethods {

    fun permissionPresetGet(context: Context, params: JSONObject): JSONObject {
        val preset = RemotePermissionPolicy.preset(context)
        return JSONObject().apply {
            put("preset", preset)
            put("label", labelOf(preset))
            put("danger", preset == RemotePermissionPolicy.PRESET_DANGER_FULL)
        }
    }

    fun permissionPresetSet(context: Context, params: JSONObject): JSONObject {
        val preset = params.optString("preset", "").ifEmpty {
            throw RPCException(-32602, "Missing 'preset' param")
        }
        if (!RemotePermissionPolicy.setPreset(context, preset)) {
            throw RPCException(-32602, "preset must be workspace-write or danger-full-access")
        }
        return JSONObject().apply {
            put("ok", true)
            put("preset", preset)
        }
    }

    fun sandboxGet(context: Context, params: JSONObject): JSONObject {
        val info = RemotePermissionPolicy.sandboxInfo(context)
        return JSONObject().apply {
            for ((k, v) in info) put(k, v)
        }
    }

    private fun labelOf(preset: String): String = when (preset) {
        RemotePermissionPolicy.PRESET_WORKSPACE_WRITE -> "Workspace Write"
        RemotePermissionPolicy.PRESET_DANGER_FULL -> "Danger Full Access"
        else -> preset
    }
}
