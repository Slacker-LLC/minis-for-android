package com.openminis.app.xposed.aimemory

import android.content.ContentProvider
import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import android.os.Bundle
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] A read-only window into the ColorOS memory app's own database.
 *
 * Ported from Eta `hook/aimemory/ColorOsMemoryHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The memory database belongs to another app and is not readable from
 * outside; the module is already inside that process, so that app's own `DataShareProvider.call`
 * is the seam - and only this module's method name is answered, everything else falls through to
 * the provider's own code. Callers must be root (the app asks through the structured
 * `root.shell` path this project already allows), the query is read-only, and the whole exchange
 * is size-capped by the protocol.
 */
object ColorOsMemoryHooks {

    private const val GROUP = "ColorOsMemory"
    private const val ROOT_UID = 0

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "coloros-memory.provider-call",
                        description = "ColorOS memory DataShareProvider.call",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            val providerClass = HookSupport.findClassOrNull(
                classLoader,
                ColorOsMemoryBridgeProtocol.PROVIDER_CLASS,
            )
            if (providerClass == null) {
                missing(
                    "coloros-memory.provider-call",
                    "ColorOS memory DataShareProvider.call",
                    "ColorOsMemory: no DataShareProvider on this build, the memory bridge is skipped",
                )
                return@install
            }
            val callMethod = HookSupport.findMethod(
                providerClass,
                "call",
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
            if (callMethod == null) {
                missing(
                    "coloros-memory.provider-call",
                    "ColorOS memory DataShareProvider.call",
                    "ColorOsMemory: DataShareProvider.call(String,String,Bundle) not found",
                )
                return@install
            }
            intercept(
                "coloros-memory.provider-call",
                callMethod,
                "ColorOS memory read-only query bridge",
            ) { chain ->
                val method = chain.getArg(0) as? String
                if (method != ColorOsMemoryBridgeProtocol.METHOD) {
                    return@intercept chain.proceed()
                }
                handleBridgeCall(
                    provider = chain.getThisObject() as? ContentProvider,
                    encodedRequest = chain.getArg(1) as? String,
                )
            }
        }.report
    }

    private fun handleBridgeCall(provider: ContentProvider?, encodedRequest: String?): Bundle {
        if (Binder.getCallingUid() != ROOT_UID) {
            return response(
                error("COLOROS_MEMORY_HOOK_CALLER_REJECTED", "system memory query caller is not root"),
            )
        }
        val request = encodedRequest?.let(ColorOsMemoryBridgeProtocol::decodeRequest)
            ?: return response(
                error("COLOROS_MEMORY_HOOK_REQUEST_INVALID", "system memory query is invalid"),
            )
        val context = provider?.context
            ?: return response(
                error("COLOROS_MEMORY_HOOK_CONTEXT_UNAVAILABLE", "ColorOS memory context unavailable"),
            )
        val databaseFile = context.getDatabasePath(ColorOsMemoryBridgeProtocol.DATABASE_NAME)
        if (!databaseFile.isFile) {
            return response(
                error("COLOROS_MEMORY_DATABASE_MISSING", "ColorOS memory database is missing"),
            )
        }
        val content = runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { database ->
                ColorOsMemoryDatabaseQuery.execute(database, request.operation, request.args)
            }
        }.getOrElse {
            error("COLOROS_MEMORY_HOOK_QUERY_FAILED", "in-process ColorOS memory query failed")
        }
        return response(content)
    }

    private fun response(content: String): Bundle {
        val encoded = runCatching { ColorOsMemoryBridgeProtocol.encodeResponse(content) }
            .getOrElse {
                ColorOsMemoryBridgeProtocol.encodeResponse(
                    error("COLOROS_MEMORY_HOOK_RESULT_TOO_LARGE", "system memory result is too large"),
                )
            }
        return Bundle().apply {
            putString(ColorOsMemoryBridgeProtocol.RESULT_KEY, encoded)
        }
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
