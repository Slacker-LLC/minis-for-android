package com.openminis.app.tools.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A short-lived target on one virtual display. Coordinates are display-local pixels. */
internal data class VirtualScreenTarget(
    val index: Int,
    val ref: String,
    val label: String,
    val packageName: String,
    val actions: Set<String>,
    val bounds: Bounds?,
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }
}

internal sealed interface VirtualScreenRefResolution {
    data class Found(val target: VirtualScreenTarget) : VirtualScreenRefResolution
    data class Error(val code: String, val message: String) : VirtualScreenRefResolution
}

/** Generation/ref registry scoped to both chat session and Android display id. */
internal object VirtualScreenObservationRegistry {
    private const val MAX_OBSERVATIONS = 4
    private val fence = UiGenerationFence(maxEntries = MAX_OBSERVATIONS, ttlMs = 30_000L)

    private data class Entry(
        val sessionId: String,
        val displayId: Int,
        val generation: Long,
        val fingerprint: String,
        val truncated: Boolean,
        val targets: Map<String, VirtualScreenTarget>,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    @Synchronized
    fun observe(sessionId: String, displayId: Int, rawDump: String): JSONObject {
        val parsed = JSONObject(rawDump)
        val fingerprint = fingerprint(rawDump)
        val truncated = isTruncated(parsed)
        val generation = fence.nextGeneration()
        val targetRows = parsed.optJSONArray("targets") ?: JSONArray()
        val targets = LinkedHashMap<String, VirtualScreenTarget>()
        val outputTargets = JSONArray()
        for (i in 0 until targetRows.length()) {
            val row = targetRows.optJSONObject(i) ?: continue
            val index = row.optInt("index", 0)
            if (index <= 0) continue
            val ref = "u$index"
            val actionsJson = row.optJSONArray("actions") ?: JSONArray()
            val actions = buildSet {
                for (actionIndex in 0 until actionsJson.length()) {
                    actionsJson.optString(actionIndex).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val target = VirtualScreenTarget(
                index = index,
                ref = ref,
                label = row.optString("label").take(256),
                packageName = row.optString("packageName").take(256),
                actions = actions,
                bounds = parseBounds(row.optString("bounds")),
            )
            targets[ref] = target
            outputTargets.put(JSONObject(row.toString()).put("ref", ref))
        }
        entries[generation] = Entry(sessionId, displayId, generation, fingerprint, truncated, targets)
        while (entries.size > MAX_OBSERVATIONS) entries.remove(entries.keys.first())
        fence.install(
            generation = generation,
            fingerprint = fingerprint,
            refs = targets.keys,
            truncated = truncated,
            sessionId = sessionId,
            displayId = displayId,
        )
        return JSONObject()
            .put("displayId", displayId)
            .put("generation", generation)
            .put("coordinateSpace", "display-local")
            .put("windowSource", parsed.optString("windowSource", "UiAutomation"))
            .put("windows", parsed.optJSONArray("windows") ?: JSONArray())
            .put("targets", outputTargets)
            .put("inputs", parsed.optJSONArray("inputs") ?: JSONArray())
            .put("truncated", truncated)
    }

    @Synchronized
    fun resolve(
        sessionId: String,
        displayId: Int,
        generation: Long,
        ref: String,
        currentDump: String,
    ): VirtualScreenRefResolution {
        val current = runCatching { JSONObject(currentDump) }.getOrElse {
            return VirtualScreenRefResolution.Error("UI_OBSERVATION_UNAVAILABLE", "Could not re-observe the virtual display")
        }
        val entry = entries[generation]
            ?: return VirtualScreenRefResolution.Error("STALE_UI_REF", "Observation generation has expired")
        val verdict = fence.validate(
            generation = generation,
            ref = ref,
            currentFingerprint = fingerprint(currentDump),
            currentTruncated = isTruncated(current),
            sessionId = sessionId,
            displayId = displayId,
        )
        val error = when (verdict) {
            UiGenerationFence.Verdict.VALID -> null
            UiGenerationFence.Verdict.TRUNCATED -> "UI_SNAPSHOT_TRUNCATED" to "A truncated observation cannot authorize ref-based actions"
            UiGenerationFence.Verdict.REF_NOT_FOUND -> "UI_REF_NOT_FOUND" to "The ref was not present in this observation"
            UiGenerationFence.Verdict.STALE -> "STALE_UI_REF" to "The screen changed or the ref belongs to another session/display"
        }
        if (error != null) return VirtualScreenRefResolution.Error(error.first, error.second)
        val target = entry.targets[ref]
            ?: return VirtualScreenRefResolution.Error("UI_REF_NOT_FOUND", "The ref was not present in this observation")
        return VirtualScreenRefResolution.Found(target)
    }

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

    internal fun fingerprint(rawDump: String): String = MessageDigest.getInstance("SHA-256")
        .digest(rawDump.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun isTruncated(json: JSONObject): Boolean {
        if (json.optBoolean("truncated")) return true
        val windows = json.optJSONArray("windows") ?: return false
        return (0 until windows.length()).any { windows.optJSONObject(it)?.optBoolean("truncated") == true }
    }

    private fun parseBounds(raw: String): VirtualScreenTarget.Bounds? {
        val values = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (values.size != 4) return null
        val (left, top, right, bottom) = values
        if (right <= left || bottom <= top) return null
        return VirtualScreenTarget.Bounds(left, top, right, bottom)
    }
}
