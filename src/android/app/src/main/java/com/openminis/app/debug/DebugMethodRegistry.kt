package com.openminis.app.debug

import android.os.Build
import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Static catalogue of every JSON-RPC method exposed by the Android debug server.
 *
 * Returned by `rpc.discover` so clients can introspect the API at runtime
 * instead of relying on external documentation. Keep this in sync with the
 * dispatch `when` in [DebugRPCHandler.dispatch].
 */
object DebugMethodRegistry {

    data class ParamSpec(
        val name: String,
        val type: String,
        val required: Boolean,
        val default: Any? = null,
        val description: String,
    )

    data class MethodSpec(
        val name: String,
        val description: String,
        val params: List<ParamSpec>,
        val returns: String,
        val example: JSONObject,
    )

    internal fun ex(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    val methods: List<MethodSpec> by lazy {
        buildList {
            addAll(BASE_METHODS)
            if (BuildConfig.DEBUG) addAll(DEBUG_ONLY_METHODS)
        }
    }

    private val DEBUG_ONLY_METHODS: List<MethodSpec> = listOf(
        MethodSpec(
            name = "debug.shizuku.exec",
            description = "DEBUG-only: invoke ShizukuOffloadHandler directly with the given argv (T344). Bypasses the agent loop for e2e harness verification of android-shizuku-cli.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `android-shizuku-cli` (e.g. [\"exec\", \"id\"]). Either this or 'command' is required."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args, e.g. \"exec id\"."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex("args" to JSONArray().apply { put("exec"); put("id") }),
        ),
        MethodSpec(
            name = "debug.modelUse.exec",
            description = "DEBUG-only: invoke ModelUseOffloadHandler directly with the given argv. Parallels debug.shizuku.exec — lets harnesses trigger `minis-model-use run/list/search` without an in-shell prompt.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `minis-model-use` (e.g. [\"run\", \"--model\", \"gpt-5.3-codex\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
                ParamSpec("input", "string", required = false, description = "Raw JSON/text fed to the handler as the --input file contents (staged briefly under the App-owned guest workspace)."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("run"); put("--model"); put("gpt-5.3-codex") },
                "input" to "{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word OK.\"}]}",
            ),
        ),
        MethodSpec(
            name = "debug.sessions.exec",
            description = "DEBUG-only: invoke SessionsOffloadHandler directly with the given argv. Parallels debug.modelUse.exec — lets harnesses trigger `minis-sessions-cli list/search/messages` (incl. --full) without an in-shell prompt.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `minis-sessions-cli` (e.g. [\"messages\", \"--id\", \"<session_id>\", \"--full\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("messages"); put("--id"); put("<session_id>"); put("--full") },
            ),
        ),
        MethodSpec(
            name = "debug.minisConfig.exec",
            description = "DEBUG-only: drive minis-config through the REAL ConfigBridge (same code path as the in-shell CLI), so a harness can exercise every collection, the confirmation gate and the audit log without an in-shell prompt. Subcommands: set, get, topics, topic-help, audit-list.",
            params = listOf(
                ParamSpec("subcommand", "string", required = true, description = "One of: set, get, topics, topic-help, audit-list."),
                ParamSpec("path", "string", required = false, description = "Config path, for get and single-path set (e.g. \"thinkingrules.<inst>:<rule>.label\")."),
                ParamSpec("value_json", "string", required = false, description = "JSON-encoded new value, for single-path set."),
                ParamSpec("items", "[{path,value_json}]", required = false, description = "Multi-path set applied as ONE write batch; alternative to path+value_json."),
                ParamSpec("skipConfirmation", "bool", required = false, description = "set only. Default true (unattended). Pass false to drive the real on-device confirmation sheet."),
                ParamSpec("filter", "string", required = false, description = "get only. Space-separated terms; filters JSON array values."),
                ParamSpec("page", "int", required = false, description = "get only. 1-BASED page number (page=1 and page=0 both mean the first page). Omit together with pageSize for no pagination."),
                ParamSpec("pageSize", "int", required = false, description = "get only. 0 = return everything."),
                ParamSpec("topic", "string", required = false, description = "topic-help only. Topic name from subcommand=topics."),
                ParamSpec("limit", "int", required = false, description = "audit-list only. 1..1000, default 100."),
                ParamSpec("scope", "string", required = false, description = "audit-list only. Restrict the audit trail to one scope."),
            ),
            returns = "get/set -> the bridge's {ok,…} envelope; topics -> {ok, topics:[string]}; topic-help -> {ok, topic, empty, fields:[{path,display_name,description,schema,access,risk,revertable}]} (empty=true means a registered collection with no children yet, e.g. thinkingrules before any rule is authored); audit-list -> {ok, count, capacity, total_used, entries}",
            example = ex(
                "subcommand" to "get",
                "path" to "thinkingrules",
            ),
        ),
    )

    private val BASE_METHODS: List<MethodSpec> = buildList {
        addAll(baseMethodsPart1)
        addAll(baseMethodsPart2)
        addAll(baseMethodsPart3)
    }

    private fun encode(method: MethodSpec): JSONObject {
        val params = JSONArray()
        for (p in method.params) {
            val obj = JSONObject().apply {
                put("name", p.name)
                put("type", p.type)
                put("required", p.required)
                put("description", p.description)
            }
            if (p.default != null) obj.put("default", p.default)
            params.put(obj)
        }
        return JSONObject().apply {
            put("name", method.name)
            put("description", method.description)
            put("params", params)
            put("returns", method.returns)
            put("example", method.example)
        }
    }

    fun discover(): JSONObject {
        val arr = JSONArray()
        for (m in methods) arr.put(encode(m))
        return JSONObject().apply {
            put("platform", "android")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("methodCount", methods.size)
            put("methods", arr)
        }
    }

    val methodNames: List<String>
        get() = methods.map { it.name }
}
