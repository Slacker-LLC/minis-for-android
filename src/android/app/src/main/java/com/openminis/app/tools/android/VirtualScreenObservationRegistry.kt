package com.openminis.app.tools.android

import com.openminis.app.util.Sha256
import org.json.JSONArray
import org.json.JSONObject

/** A short-lived target on one virtual display. Coordinates are display-local pixels. */
internal data class VirtualScreenTarget(
    val index: Int,
    val ref: String,
    val label: String,
    val packageName: String,
    val actions: Set<String>,
    val bounds: Bounds?,
    /** Everything the service needs to find this node again; opaque to the app. */
    val locator: JSONObject = JSONObject(),
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }
}

internal sealed interface VirtualScreenRefResolution {
    /** [scanTruncated]: the observation did not read the whole tree, so the service may not rely on uniqueness. */
    data class Found(val target: VirtualScreenTarget, val scanTruncated: Boolean = false) : VirtualScreenRefResolution
    data class Error(val code: String, val message: String) : VirtualScreenRefResolution
}

/** What the model asked to see; mirrors the physical-screen observe options that make sense here. */
internal data class VirtualScreenObserveOptions(
    val maxNodes: Int = 120,
    val textFilter: String? = null,
    val packageFilter: String? = null,
    val includeTree: Boolean = false,
)

/**
 * Generation/ref registry scoped to both chat session and Android display id.
 *
 * A ref is no longer "valid while the whole screen hashes the same". It is a locator the service
 * re-verifies against the live node (path + identity, see [UiRefResolutionPolicy]) in the same call
 * that acts, so a ticking clock or a banner no longer invalidates every ref, and there is no gap
 * between verifying and acting. The registry only enforces lifetime, scope and membership.
 */
internal object VirtualScreenObservationRegistry {
    private const val MAX_OBSERVATIONS = 8

    /** Safe to be long now: staleness is decided per target by the service, not by the clock. */
    private const val TTL_MS = 5 * 60_000L
    private val fence = UiGenerationFence(maxEntries = MAX_OBSERVATIONS, ttlMs = TTL_MS)

    private data class Entry(
        val sessionId: String,
        val displayId: Int,
        val generation: Long,
        val fingerprint: String,
        val scanTruncated: Boolean,
        val targets: Map<String, VirtualScreenTarget>,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    /** Test and compatibility entry point: [rawDump] is the service's JSON as a string. */
    @Synchronized
    fun observe(
        sessionId: String,
        displayId: Int,
        rawDump: String,
        options: VirtualScreenObserveOptions = VirtualScreenObserveOptions(),
    ): JSONObject = observe(sessionId, displayId, JSONObject(rawDump), options)

    @Synchronized
    fun observe(
        sessionId: String,
        displayId: Int,
        parsed: JSONObject,
        options: VirtualScreenObserveOptions = VirtualScreenObserveOptions(),
    ): JSONObject {
        val fingerprint = fingerprint(parsed)
        val scanTruncated = isScanTruncated(parsed)
        val generation = fence.nextGeneration()
        val targetRows = parsed.optJSONArray("targets") ?: JSONArray()
        val textNeedle = options.textFilter?.trim().orEmpty()
        val packageNeedle = options.packageFilter?.trim().orEmpty()
        val cap = options.maxNodes.coerceIn(1, 500)

        val targets = LinkedHashMap<String, VirtualScreenTarget>()
        val outputTargets = JSONArray()
        val refByPath = HashMap<String, String>()
        var outputTruncated = parsed.optBoolean("outputTruncated", false)
        for (i in 0 until targetRows.length()) {
            val row = targetRows.optJSONObject(i) ?: continue
            val index = row.optInt("index", 0)
            if (index <= 0) continue
            val label = row.optString("label")
            val packageName = row.optString("packageName")
            if (textNeedle.isNotEmpty() && !label.contains(textNeedle, ignoreCase = true) &&
                !row.optString("viewId").contains(textNeedle, ignoreCase = true)
            ) continue
            if (packageNeedle.isNotEmpty() && !packageName.contains(packageNeedle, ignoreCase = true)) continue
            if (targets.size >= cap) {
                outputTruncated = true
                break
            }
            val ref = "u$index"
            val actionsJson = row.optJSONArray("actions") ?: JSONArray()
            val actions = buildSet {
                for (actionIndex in 0 until actionsJson.length()) {
                    actionsJson.optString(actionIndex).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            targets[ref] = VirtualScreenTarget(
                index = index,
                ref = ref,
                label = label.take(256),
                packageName = packageName.take(256),
                actions = actions,
                bounds = parseBounds(row.optString("bounds")),
                locator = locatorOf(row),
            )
            refByPath[row.optString("path")] = ref
            // What the model sees: enough to choose, nothing it cannot use. The locator stays here.
            outputTargets.put(
                JSONObject().put("ref", ref).put("label", label.take(256))
                    .put("actions", JSONArray(actions.toList())).put("bounds", row.optString("bounds"))
                    .also { out ->
                        // An icon button has no label; its resource id is the next best name.
                        if (label.isBlank()) row.optString("viewId").substringAfterLast('/').takeIf { it.isNotBlank() }?.let { out.put("id", it) }
                    },
            )
        }

        entries[generation] = Entry(sessionId, displayId, generation, fingerprint, scanTruncated, targets)
        while (entries.size > MAX_OBSERVATIONS) entries.remove(entries.keys.first())
        fence.install(
            generation = generation,
            fingerprint = fingerprint,
            refs = targets.keys,
            truncated = scanTruncated,
            sessionId = sessionId,
            displayId = displayId,
        )

        val windows = parsed.optJSONArray("windows") ?: JSONArray()
        val windowSummary = JSONArray()
        for (i in 0 until windows.length()) {
            val window = windows.optJSONObject(i) ?: continue
            windowSummary.put(
                JSONObject().put("package", window.optString("package")).put("title", window.optString("title"))
                    .put("type", window.optInt("type", -1)).put("bounds", window.optString("bounds"))
                    .also { if (options.includeTree && window.has("root")) it.put("root", window.get("root")) },
            )
        }

        val outputInputs = JSONArray()
        val inputRows = parsed.optJSONArray("inputs") ?: JSONArray()
        for (i in 0 until inputRows.length()) {
            val row = inputRows.optJSONObject(i) ?: continue
            val ref = refByPath[row.optString("path")] ?: continue
            outputInputs.put(
                JSONObject().put("ref", ref).put("text", row.optString("text")).put("hint", row.optString("hint"))
                    .put("bounds", row.optString("bounds")),
            )
        }

        val outputTexts = JSONArray()
        val textRows = parsed.optJSONArray("texts") ?: JSONArray()
        for (i in 0 until textRows.length()) {
            val row = textRows.optJSONObject(i) ?: continue
            if (textNeedle.isNotEmpty() && !row.optString("text").contains(textNeedle, ignoreCase = true)) continue
            if (outputTexts.length() >= cap) {
                outputTruncated = true
                break
            }
            outputTexts.put(row)
        }

        return JSONObject()
            .put("displayId", displayId)
            .put("generation", generation)
            .put("coordinateSpace", "display-local")
            .put("display", parsed.optJSONObject("display") ?: JSONObject())
            .put("windows", windowSummary)
            .put("targets", outputTargets)
            .put("inputs", outputInputs)
            .put("texts", outputTexts)
            // truncated = the service stopped READING (refs may be less certain); outputTruncated = the
            // model was simply not told about every node. Only the first one is a safety signal.
            .put("truncated", scanTruncated)
            .put("outputTruncated", outputTruncated)
            .put("scanNodes", parsed.optInt("scanNodes", 0))
            .also { out ->
                val warnings = parsed.optJSONArray("layoutWarnings")
                if (warnings != null && warnings.length() > 0) out.put("layoutWarnings", warnings)
            }
    }

    /** Lifetime, scope and membership only. Whether the node is still there is the service's call. */
    @Synchronized
    fun locate(sessionId: String, displayId: Int, generation: Long, ref: String): VirtualScreenRefResolution {
        val entry = entries[generation]
            ?: return VirtualScreenRefResolution.Error("STALE_UI_REF", "Observation generation has expired")
        return when (fence.lookup(generation, ref, sessionId, displayId)) {
            UiGenerationFence.Freshness.FRESH -> {
                val target = entry.targets[ref]
                    ?: return VirtualScreenRefResolution.Error("UI_REF_NOT_FOUND", "The ref was not present in this observation")
                VirtualScreenRefResolution.Found(target, entry.scanTruncated)
            }
            UiGenerationFence.Freshness.REF_NOT_FOUND ->
                VirtualScreenRefResolution.Error("UI_REF_NOT_FOUND", "The ref was not present in this observation")
            else -> VirtualScreenRefResolution.Error("STALE_UI_REF", "The observation expired or belongs to another session/display")
        }
    }

    /** Fingerprint of what the model was shown for [generation]; evidence that an action changed the screen. */
    @Synchronized
    fun observedFingerprint(generation: Long): String? = entries[generation]?.fingerprint

    @Synchronized
    fun clearSession(sessionId: String) {
        entries.entries.removeAll { it.value.sessionId == sessionId }
    }

    @Synchronized
    fun clearDisplay(displayId: Int) {
        entries.entries.removeAll { it.value.displayId == displayId }
        fence.clearDisplay(displayId)
    }

    @Synchronized
    internal fun clearForTests() {
        entries.clear()
        fence.clear()
    }

    internal fun fingerprint(rawDump: String): String = digest(rawDump)

    internal fun fingerprint(parsed: JSONObject): String = digest(parsed.toString())

    private fun digest(value: String): String = Sha256.hex(value)

    private fun isScanTruncated(json: JSONObject): Boolean {
        if (json.optBoolean("truncated")) return true
        val windows = json.optJSONArray("windows") ?: return false
        return (0 until windows.length()).any { windows.optJSONObject(it)?.optBoolean("truncated") == true }
    }

    private fun locatorOf(row: JSONObject): JSONObject = JSONObject().apply {
        for (key in LOCATOR_KEYS) if (row.has(key)) put(key, row.get(key))
    }

    private val LOCATOR_KEYS = listOf("path", "windowId", "packageName", "className", "viewId", "text", "desc", "uid", "password", "bounds")

    private fun parseBounds(raw: String): VirtualScreenTarget.Bounds? {
        val values = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (values.size != 4) return null
        val (left, top, right, bottom) = values
        if (right <= left || bottom <= top) return null
        return VirtualScreenTarget.Bounds(left, top, right, bottom)
    }
}
