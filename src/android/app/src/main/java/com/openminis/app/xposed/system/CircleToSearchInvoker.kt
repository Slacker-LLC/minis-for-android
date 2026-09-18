package com.openminis.app.xposed.system

import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModuleTargets
import java.lang.reflect.Method

/**
 * [T-eta-xposed-groups] Triggering the SYSTEM's own contextual search ("circle to search") from a
 * gesture the OEM handed to its own screen-search service.
 *
 * Ported from Eta `hook/system/CircleToSearchInvoker.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta reaches the system service through its binder instead of replaying
 * the OEM OCR/recognition chain, because the point is to hand the gesture to the search the user
 * already has rather than to a second implementation of it.
 *
 * Availability is checked before anything is triggered - the Google app installed AND exposing the
 * contextual-search entry - so a ROM without that entry keeps its own behaviour instead of ending
 * up with a gesture that does nothing. Every reflection result is cached after the first success,
 * and a failure is a `false`, never an exception into somebody else's process.
 */
object CircleToSearchInvoker {

    @Volatile
    private var getServiceMethod: Method? = null

    @Volatile
    private var asInterfaceMethod: Method? = null

    @Volatile
    private var startContextualSearchMethod: StartContextualSearchMethod? = null

    fun isAvailable(
        context: Context,
        logger: HookLogger,
        source: String,
        fallbackMessage: String,
    ): Boolean {
        if (!HookSupport.isPackageInstalled(context, ModuleTargets.GOOGLE_SEARCH_PACKAGE)) {
            logger.warnThrottled("${source}_cts_google_missing") {
                "$source: the Google app is not installed, $fallbackMessage"
            }
            return false
        }
        val intent = Intent(ModuleTargets.CONTEXTUAL_SEARCH_ACTION)
            .setPackage(ModuleTargets.GOOGLE_SEARCH_PACKAGE)
        if (!HookSupport.resolvesActivity(context, intent)) {
            logger.warnThrottled("${source}_cts_entry_missing") {
                "$source: the Google app exposes no contextual-search entry, $fallbackMessage"
            }
            return false
        }
        val binder = getContextualSearchBinder() ?: run {
            logger.warnThrottled("${source}_cts_service_missing") {
                "$source: the contextual_search service is unavailable, $fallbackMessage"
            }
            return false
        }
        return binder.isBinderAlive
    }

    fun trigger(
        logger: HookLogger,
        source: String,
        entryPoint: Int = ModuleTargets.CIRCLE_TO_SEARCH_ENTRYPOINT,
    ): Boolean {
        val binder = getContextualSearchBinder() ?: return false
        return runCatching {
            val asInterface = resolveAsInterfaceMethod() ?: return@runCatching false
            val startContextualSearch = resolveStartContextualSearchMethod() ?: return@runCatching false
            val service = asInterface.invoke(null, binder) ?: return@runCatching false
            if (startContextualSearch.hasConfigParameter) {
                startContextualSearch.method.invoke(service, entryPoint, null)
            } else {
                startContextualSearch.method.invoke(service, entryPoint)
            }
            logger.debug { "$source: contextual search was triggered" }
            true
        }.getOrElse { failure ->
            logger.errorThrottled("${source}_cts_trigger_failed") {
                "$source: triggering contextual search failed " +
                    "(${failure.javaClass.simpleName}: ${failure.message})"
            }
            false
        }
    }

    private fun getContextualSearchBinder(): IBinder? = runCatching {
        resolveGetServiceMethod()?.invoke(null, ModuleTargets.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
    }.getOrNull()

    private fun resolveGetServiceMethod(): Method? {
        getServiceMethod?.let { return it }
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { getServiceMethod = it }
    }

    private fun resolveAsInterfaceMethod(): Method? {
        asInterfaceMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { asInterfaceMethod = it }
    }

    /**
     * The service method exists in two shapes across releases: with and without a config object.
     * Both are tried once and the working one is remembered.
     */
    private fun resolveStartContextualSearchMethod(): StartContextualSearchMethod? {
        startContextualSearchMethod?.let { return it }
        val serviceClass = runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager")
        }.getOrNull() ?: return null

        val withConfig = runCatching {
            val configClass = Class.forName("android.app.contextualsearch.ContextualSearchConfig")
            serviceClass.getDeclaredMethod(
                "startContextualSearch",
                Integer.TYPE,
                configClass,
            ).apply { isAccessible = true }
        }.getOrNull()
        if (withConfig != null) {
            return StartContextualSearchMethod(withConfig, hasConfigParameter = true)
                .also { startContextualSearchMethod = it }
        }

        return runCatching {
            serviceClass.getDeclaredMethod("startContextualSearch", Integer.TYPE)
                .apply { isAccessible = true }
        }.getOrNull()?.let { method ->
            StartContextualSearchMethod(method, hasConfigParameter = false)
        }?.also { startContextualSearchMethod = it }
    }

    private data class StartContextualSearchMethod(
        val method: Method,
        val hasConfigParameter: Boolean,
    )
}
