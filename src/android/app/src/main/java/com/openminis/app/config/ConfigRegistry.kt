package com.openminis.app.config

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.runtime.guest.GuestCommandBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for every configurable setting in the app.
 *
 * Add a new setting in three steps:
 *   1. Pick a dot-path id (`appearance.theme`, `browser.uaProfile`, …).
 *   2. Construct a [ConfigField] (or a [ConfigCollection] for dynamic
 *      children) and register it in `ConfigBuiltins`.
 *   3. Done — the offload bridge, confirmation gate, audit log, revert
 *      flow, and topic-help output all derive from this registry.
 *
 * Mirrors iOS `ConfigRegistry`. The Android side is plain mutable maps
 * (no actor isolation needed — handler threads are the writers and the
 * registry is initialized once at boot before any reads).
 */
class ConfigRegistry private constructor() {
    private val fields = LinkedHashMap<String, ConfigField>()
    private val collections = LinkedHashMap<String, ConfigCollection>()
    private val initialized = AtomicBoolean(false)

    fun register(field: ConfigField) {
        fields[field.path] = field
    }

    fun register(collection: ConfigCollection) {
        collections[collection.basePath] = collection
    }

    /**
     * Look up a field by path. Handles both flat fields and collection
     * children (`<base>.<id>.<sub>`). Returns null for unknown paths.
     */
    fun resolveField(path: String): ConfigField? {
        fields[path]?.let { return it }
        // Ids normally have no dot: `<base>.<id>.<sub>`, where <sub> may itself be dotted.
        val plain = path.split('.', limit = 3)
        if (plain.size == 3) {
            collections[plain[0]]?.fields(forId = plain[1])?.firstOrNull { it.path == path }?.let { return it }
        }
        // An id that contains dots (`inst/mimo-v2.5`): the field is what follows the last dot.
        val parts = splitChildPath(path) ?: return null
        val coll = collections[parts.first] ?: return null
        return coll.fields(forId = parts.second).firstOrNull { it.path == path }
    }

    fun collection(basePath: String): ConfigCollection? = collections[basePath]

    fun allVisibleFieldPaths(): List<String> =
        fields.values
            .filter { it.access != ConfigAccess.HIDDEN }
            .map { it.path }
            .sorted()

    fun topics(): List<String> {
        val set = LinkedHashSet<String>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            val head = f.path.substringBefore('.', missingDelimiterValue = "")
            if (head.isNotEmpty()) set.add(head)
        }
        for (c in collections.values) set.add(c.basePath)
        return set.sorted()
    }

    fun fields(topic: String): List<ConfigField> {
        val out = ArrayList<ConfigField>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            if (f.path == topic || f.path.startsWith("$topic.")) out.add(agentVisibleField(f))
        }
        val coll = collections[topic]
        if (coll != null) {
            val firstId = coll.childIds().firstOrNull()
            if (firstId != null) {
                out.addAll(
                    coll.fields(forId = firstId)
                        .filter { it.access != ConfigAccess.HIDDEN }
                        .map(::agentVisibleField),
                )
            }
        }
        return out.sortedBy { it.path }
    }

    private fun agentVisibleField(field: ConfigField): ConfigField =
        if (field.path == "soul.body") {
            object : ConfigField by field {
                override val description: String =
                    "Personality / voice instructions injected as a block in the system prompt. " +
                        "No character or word-count cap is applied. Prompt-injection patterns " +
                        "(for example, requests to ignore previous instructions) are rejected; " +
                        "keep this field to genuine character / tone guidance."
            }
        } else {
            field
        }

    companion object {
        /**
         * `<collection>.<id>.<field>` as (collection, id). The collection is up to the FIRST dot and
         * the field after the LAST, so an id may itself contain dots (`inst/mimo-v2.5`); splitting
         * into three at the first two dots cut such ids in half. Null when there are fewer than two dots.
         */
        internal fun splitChildPath(path: String): Pair<String, String>? {
            val first = path.indexOf('.')
            val last = path.lastIndexOf('.')
            if (first <= 0 || last <= first || last == path.length - 1) return null
            return path.substring(0, first) to path.substring(first + 1, last)
        }

        private const val TAG = "ConfigRegistry"

        /** An empty, unpublished registry for tests. */
        internal fun newForTest(): ConfigRegistry = ConfigRegistry()

        @Volatile private var INSTANCE: ConfigRegistry? = null

        fun get(): ConfigRegistry =
            INSTANCE ?: error("ConfigRegistry not initialized; call init() from Application.onCreate")

        fun init(
            context: Context,
            providerRepository: ProviderRepository,
            envVarRepository: EnvVarRepository,
            chatRepository: ChatRepository,
        ): ConfigRegistry {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val r = ConfigRegistry()
                if (r.initialized.compareAndSet(false, true)) {
                    ConfigBuiltins.registerInto(
                        r, context, providerRepository, envVarRepository, chatRepository,
                    )
                }
                // Publish first so a guest request can never observe a partial registry.
                INSTANCE = r
                runCatching { GuestCommandBridge.start(context.applicationContext) }
                    .onFailure { Log.w(TAG, "direct guest command bridge start failed: ${it.message}") }
                return r
            }
        }
    }
}
