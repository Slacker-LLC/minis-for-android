package com.openminis.app.tools

import android.content.Context
import android.provider.Settings
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import org.json.JSONObject

/** Structured settings access: Android API first, privileged Root fallback for writes. */
object AndroidSettingsOps {
    private val keyPattern = Regex("^[A-Za-z0-9_.-]{1,128}$")
    private val integerPattern = Regex("^-?\\d+$")

    fun get(context: Context, namespace: String, key: String): ToolExecutionResult {
        validate(namespace, key)?.let { return it }
        return try {
            val value = readCurrent(context, namespace, key)
            ToolExecutionResult(
                JSONObject().put("namespace", namespace).put("key", key).put("value", value ?: JSONObject.NULL).put("source", "android_api").toString(2),
                true,
            )
        } catch (t: Throwable) {
            ToolExecutionResult("Error: settings_get failed: ${t.message}", false)
        }
    }

    suspend fun set(
        context: Context,
        sessionId: String,
        namespace: String,
        key: String,
        value: String?,
        delete: Boolean,
    ): ToolExecutionResult {
        validate(namespace, key)?.let { return it }
        if (!delete && value == null) return ToolExecutionResult("Error: value is required for set", false)
        if (value != null && value.length > 4_096) return ToolExecutionResult("Error: value exceeds 4096 characters", false)
        // [T-android-settings-type-guard] Settings are strings and the platform
        // does not type-check them: writing `not-a-number` over
        // `system/screen_brightness` answered `updated: true` and the device then
        // reported its brightness as that literal string (measured on the Xiaomi
        // 24129PN74C; the value was restored by hand). The only type signal a
        // setting carries is its current value, so that is what this uses — a key
        // holding an integer accepts only integers, an unset key accepts anything,
        // and raw semantics stay available through linux_shell.
        if (!delete) {
            val current = readCurrent(context, namespace, key)
            if (current != null && isInteger(current) && !isInteger(value!!)) {
                return ToolExecutionResult(
                    "Error: $namespace/$key currently holds the integer \"$current\"; " +
                        "\"$value\" is not an integer, and the platform would accept it without checking. " +
                        "Pass an integer value.",
                    false,
                )
            }
        }

        // System settings can be granted to an ordinary app. Try that narrow
        // path first; secure/global writes fall through to the privileged Root runner.
        val wrote = runCatching {
            when (namespace) {
                "system" -> Settings.System.putString(context.contentResolver, key, if (delete) null else value)
                "secure" -> Settings.Secure.putString(context.contentResolver, key, if (delete) null else value)
                "global" -> Settings.Global.putString(context.contentResolver, key, if (delete) null else value)
                else -> false
            }
        }.getOrDefault(false)
        if (wrote) {
            return ToolExecutionResult(
                writeResult(context, namespace, key, value, delete, "android_api"),
                true,
            )
        }

        val args = if (delete) listOf("delete", namespace, key) else listOf("put", namespace, key, value!!)
        val response = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId,
            argv = listOf("settings") + args,
            operation = "写入 Android setting $namespace/$key",
            risk = CommandRisk.MUTATING,
            timeoutMs = 30_000L,
            rootOnly = true,
        )
        if (!response.success) {
            return ToolExecutionResult(
                "Error: ${response.unavailableReason ?: response.stderr.ifBlank { "privileged settings command failed" }}",
                false,
            )
        }
        return ToolExecutionResult(
            if (response.exitCode == 0) writeResult(context, namespace, key, value, delete, "root_direct")
            else "Error: settings exit=${response.exitCode} ${response.stderr}",
            response.exitCode == 0,
        )
    }

    private fun readCurrent(context: Context, namespace: String, key: String): String? = when (namespace) {
        "system" -> Settings.System.getString(context.contentResolver, key)
        "secure" -> Settings.Secure.getString(context.contentResolver, key)
        "global" -> Settings.Global.getString(context.contentResolver, key)
        else -> null // guarded by validate()
    }

    internal fun isInteger(text: String): Boolean = integerPattern.matches(text.trim())

    /**
     * Result of a write, including what the device actually holds afterwards: the
     * platform may normalise, clamp or ignore a value, and reporting only
     * `updated: true` hid that.
     */
    private fun writeResult(
        context: Context,
        namespace: String,
        key: String,
        value: String?,
        delete: Boolean,
        source: String,
    ): String {
        val after = readCurrent(context, namespace, key)
        val verified = if (delete) after == null else after == value
        return JSONObject()
            .put("namespace", namespace)
            .put("key", key)
            .put("source", source)
            .put("updated", true)
            .put("valueAfter", after ?: JSONObject.NULL)
            .put("verified", verified)
            .toString()
    }

    private fun validate(namespace: String, key: String): ToolExecutionResult? = when {
        namespace !in setOf("system", "secure", "global") -> ToolExecutionResult("Error: namespace must be system, secure, global", false)
        !keyPattern.matches(key) -> ToolExecutionResult("Error: invalid settings key", false)
        else -> null
    }
}

class AndroidSettingsGetHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.settings.get",
        description = "Read an Android system/secure/global setting via ordinary Android API.",
        parameters = mapOf(
            "namespace" to AgentToolParam("string", "system/secure/global", listOf("system", "secure", "global")),
            "key" to AgentToolParam("string", "Settings key"),
        ),
        required = listOf("namespace", "key"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSettingsOps.get(context, a.optString("namespace"), a.optString("key"))
    }
}

class AndroidSettingsSetHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.settings.set",
        description = "Set or delete an Android setting. Uses the privileged Root runner only if normal Android API write is unavailable.",
        parameters = mapOf(
            "namespace" to AgentToolParam("string", "system/secure/global", listOf("system", "secure", "global")),
            "key" to AgentToolParam("string", "Settings key"),
            "value" to AgentToolParam("string", "Value for set"),
            "delete" to AgentToolParam("boolean", "Delete the key instead of setting a value (default false)"),
        ),
        required = listOf("namespace", "key"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSettingsOps.set(
            context,
            sessionId,
            a.optString("namespace"),
            a.optString("key"),
            if (a.has("value")) a.optString("value") else null,
            a.optBoolean("delete"),
        )
    }
}
