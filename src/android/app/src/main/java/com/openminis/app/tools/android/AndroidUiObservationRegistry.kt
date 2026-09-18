package com.openminis.app.tools.android

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.AccessibilityIdentityFreshnessPolicy
import com.openminis.app.accessibility.AccessibilityNodeIdentity
import com.openminis.app.accessibility.MinisAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque

/** Compact observation filters exposed by `android_ui observe`. */
data class UiObserveOptions(
    val interactiveOnly: Boolean = true,
    val maxDepth: Int = 12,
    val maxNodes: Int = 120,
    val textFilter: String? = null,
    val resourceIdFilter: String? = null,
    val packageFilter: String? = null,
)

data class UiLocator(
    val generation: Long,
    val ref: String,
    val rootIndex: Int,
    val childPath: List<Int>,
    val windowId: Int,
    /** System unique id when the platform exposes one; empty on API < 33. */
    val uniqueId: String,
    val packageName: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val password: Boolean,
    val bounds: Rect,
)

/** One observed anchor for scroll evidence: semantic identity plus current screen position. */
data class UiAnchorSample(val key: String, val x: Int, val y: Int)

/**
 * Content displacement along [axis] for anchors that are still on screen with the
 * same identity. Anchors that disappeared, appeared only afterwards, or whose
 * identity is not unique on either side are dropped instead of guessed.
 */
fun anchorContentDeltas(before: List<UiAnchorSample>, after: List<UiAnchorSample>, axis: ScrollAxis): List<Int> {
    val earlierByKey = before.groupBy { it.key }
    val laterByKey = after.groupBy { it.key }
    return earlierByKey.mapNotNull { (key, earlier) ->
        val later = laterByKey[key] ?: return@mapNotNull null
        if (earlier.size != 1 || later.size != 1) return@mapNotNull null
        val from = earlier.single()
        val to = later.single()
        when (axis) {
            ScrollAxis.VERTICAL -> to.y - from.y
            ScrollAxis.HORIZONTAL -> to.x - from.x
        }
    }
}

sealed class UiRefResolution {
    data class Found(val service: MinisAccessibilityService, val node: AccessibilityNodeInfo, val locator: UiLocator) : UiRefResolution()
    data class Error(val code: String, val message: String) : UiRefResolution()
}

/**
 * Short-lived semantic refs bound to a complete UI fingerprint. No
 * AccessibilityNodeInfo survives an observation call.
 */
object AndroidUiObservationRegistry {
    private const val MAX_OBSERVATIONS = 4
    private const val OBSERVATION_TTL_MS = 30_000L
    private const val FINGERPRINT_NODE_LIMIT = 2_000
    private const val MAX_EVIDENCE_ANCHORS = 64

    /** A window that could receive touch has no resolvable package. */
    const val ERROR_WINDOW_UNKNOWN = "UI_WINDOW_UNKNOWN"

    /** The window list itself could not be read, so the window policy was never evaluated. */
    const val ERROR_WINDOW_LIST_UNAVAILABLE = "UI_WINDOW_LIST_UNAVAILABLE"

    /** The fingerprint scan stopped early, so no ref can be proven unchanged. */
    const val ERROR_SNAPSHOT_TRUNCATED = "UI_SNAPSHOT_TRUNCATED"

    private val generationFence = UiGenerationFence(
        maxEntries = MAX_OBSERVATIONS,
        ttlMs = OBSERVATION_TTL_MS,
    )

    private data class Observation(
        val generation: Long,
        val createdAt: Long,
        val fingerprint: String,
        val snapshotTruncated: Boolean,
        val locators: Map<String, UiLocator>,
    )

    private data class Fingerprint(val hash: String, val truncated: Boolean)

    private val observations = LinkedHashMap<Long, Observation>()

    /**
     * The refusal shared by observation, ref resolution and capture: the observed
     * window set must be reconcilable with the surface that receives touch. Callers
     * must refuse instead of reporting a partial view.
     */
    fun windowSetRefusal(windowSet: MinisAccessibilityService.VisibleWindowSet): Pair<String, String>? = when {
        windowSet.blockedUnknownWindows > 0 -> ERROR_WINDOW_UNKNOWN to
            "${windowSet.blockedUnknownWindows} touchable window(s) have no resolvable package; " +
            "the observed screen would not match the surface that receives touch"

        !windowSet.complete -> ERROR_WINDOW_LIST_UNAVAILABLE to
            "the window list could not be read, so the window policy was never evaluated; " +
            "the observed screen would not match the surface that receives touch"

        else -> null
    }

    @Synchronized
    fun observe(
        service: MinisAccessibilityService,
        roots: List<AccessibilityNodeInfo>,
        options: UiObserveOptions,
    ): JSONObject {
        val generation = generationFence.nextGeneration()
        val scanned = fingerprint(roots)
        val nodes = JSONArray()
        val locators = LinkedHashMap<String, UiLocator>()
        var refCounter = 0
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, rootIndex: Int, path: List<Int>, depth: Int, parentRef: String?) {
            if (node == null || depth > options.maxDepth || nodes.length() >= options.maxNodes) {
                if (node != null && (depth > options.maxDepth || nodes.length() >= options.maxNodes)) truncated = true
                return
            }
            val packageName = node.packageName?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val resourceId = node.viewIdResourceName.orEmpty()
            val className = node.className?.toString().orEmpty()
            val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable ||
                node.isCheckable || node.isFocusable || node.actionList.isNotEmpty()
            val textNeedle = options.textFilter?.trim()?.lowercase().orEmpty()
            val idNeedle = options.resourceIdFilter?.trim()?.lowercase().orEmpty()
            val packageNeedle = options.packageFilter?.trim()?.lowercase().orEmpty()
            val matches = (!options.interactiveOnly || interactive) &&
                (textNeedle.isEmpty() || text.lowercase().contains(textNeedle) || description.lowercase().contains(textNeedle)) &&
                (idNeedle.isEmpty() || resourceId.lowercase().contains(idNeedle)) &&
                (packageNeedle.isEmpty() || packageName.lowercase().contains(packageNeedle))
            var emittedRef: String? = null
            if (matches && node.isVisibleToUser && nodes.length() < options.maxNodes) {
                refCounter += 1
                val ref = "u$refCounter"
                emittedRef = ref
                val bounds = Rect().also(node::getBoundsInScreen)
                val locator = UiLocator(
                    generation = generation,
                    ref = ref,
                    rootIndex = rootIndex,
                    childPath = path,
                    windowId = node.windowId,
                    uniqueId = uniqueIdOf(node),
                    packageName = packageName,
                    className = className,
                    text = text,
                    contentDescription = description,
                    resourceId = resourceId,
                    password = node.isPassword,
                    bounds = Rect(bounds),
                )
                locators[ref] = locator
                nodes.put(nodeJson(node, locator, depth, parentRef))
            }
            val nextParent = emittedRef ?: parentRef
            for (index in 0 until node.childCount) {
                if (nodes.length() >= options.maxNodes) {
                    truncated = true
                    break
                }
                walk(node.getChild(index), rootIndex, path + index, depth + 1, nextParent)
            }
        }

        roots.forEachIndexed { index, root -> walk(root, index, emptyList(), 0, null) }
        observations[generation] = Observation(
            generation = generation,
            createdAt = System.currentTimeMillis(),
            fingerprint = scanned.hash,
            snapshotTruncated = scanned.truncated,
            locators = locators,
        )
        generationFence.install(generation, scanned.hash, locators.keys, truncated = scanned.truncated)
        trimLocked()
        val foreground = service.foregroundWindow()
        return JSONObject().apply {
            put("generation", generation)
            put("package", foreground.packageName ?: "")
            put("activity", foreground.className ?: "")
            put("window", foreground.className ?: "")
            put("windowVisibility", foreground.visibility.name)
            put("nodeCount", nodes.length())
            put("truncated", truncated)
            put("snapshotTruncated", scanned.truncated)
            put("refActionsAllowed", !scanned.truncated)
            put("nodes", nodes)
            put("expiresInMs", OBSERVATION_TTL_MS)
        }
    }

    @Synchronized
    fun resolve(generation: Long, ref: String): UiRefResolution {
        val observation = observations[generation]
            ?: return UiRefResolution.Error("STALE_UI_REF", "generation $generation is no longer retained; run android_ui observe again")
        val locator = observation.locators[ref]
            ?: return UiRefResolution.Error("UI_REF_NOT_FOUND", "ref $ref does not belong to generation $generation")
        val service = MinisAccessibilityService.getInstance()
            ?: return UiRefResolution.Error("ACCESSIBILITY_NOT_CONNECTED", "MinisAccessibilityService is not connected")
        val windowSet = service.visibleWindowSet()
        windowSetRefusal(windowSet)?.let { (code, message) -> return UiRefResolution.Error(code, message) }
        val roots = windowSet.roots
        val scanned = fingerprint(roots)
        when (generationFence.validate(generation, ref, scanned.hash, scanned.truncated)) {
            UiGenerationFence.Verdict.STALE -> return UiRefResolution.Error(
                "STALE_UI_REF", "the window changed or generation $generation expired; run android_ui observe again",
            )
            UiGenerationFence.Verdict.REF_NOT_FOUND -> return UiRefResolution.Error(
                "UI_REF_NOT_FOUND", "ref $ref does not belong to generation $generation",
            )
            UiGenerationFence.Verdict.TRUNCATED -> return UiRefResolution.Error(
                ERROR_SNAPSHOT_TRUNCATED,
                "the observed window is too large to fingerprint completely, so ref $ref cannot be proven " +
                    "unchanged; run android_ui observe again",
            )
            UiGenerationFence.Verdict.VALID -> Unit
        }
        var node: AccessibilityNodeInfo? = roots.getOrNull(locator.rootIndex)
        for (index in locator.childPath) node = node?.getChild(index)
        if (node != null && identityOf(locator).matches(identityOf(node))) {
            return UiRefResolution.Found(service, node, locator)
        }
        // The node moved inside the tree: only an identity that is provably unique
        // in a complete snapshot may still be used. A truncated fingerprint cannot
        // prove that a text/description identity is unique in the rest of the window.
        val candidates = if (observation.snapshotTruncated) emptyList() else matchIdentities(roots, locator)
        if (
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = locator.uniqueId.isNotBlank(),
                snapshotTruncated = observation.snapshotTruncated,
                identityMatchCount = candidates.size,
            )
        ) {
            return UiRefResolution.Found(service, candidates.single(), locator)
        }
        return UiRefResolution.Error("STALE_UI_REF", "ref $ref no longer resolves to the observed semantic node")
    }

    /**
     * Whether re-resolving [ref] proves that the observed window changed. Only a
     * failed comparison that could not be compared for another reason (expired
     * generation, truncated snapshot, unreadable window set, disconnected service)
     * stays [UiChangeObservation.UNKNOWN]; none of those may be reported as an effect.
     */
    @Synchronized
    fun changeEvidence(generation: Long, ref: String): UiChangeObservation {
        if (observations[generation] == null) return UiChangeObservation.UNKNOWN
        return when (val resolved = resolve(generation, ref)) {
            is UiRefResolution.Found -> UiChangeObservation.UNCHANGED
            is UiRefResolution.Error ->
                if (resolved.code == "STALE_UI_REF") UiChangeObservation.CHANGED else UiChangeObservation.UNKNOWN
        }
    }

    @Synchronized
    internal fun clearForTests() {
        observations.clear()
        generationFence.clear()
    }

    /**
     * Re-samples the semantic anchors of [generation] at their current on-screen
     * bounds. This is evidence for an action only: it never installs a generation,
     * never changes ref validity, and anchors that disappeared or whose identity
     * is not unique in the current tree are omitted instead of guessed.
     */
    @Synchronized
    fun sampleAnchors(service: MinisAccessibilityService, generation: Long): List<UiAnchorSample>? {
        val observation = observations[generation] ?: return null
        val wanted = observation.locators.values.take(MAX_EVIDENCE_ANCHORS)
        if (wanted.isEmpty()) return emptyList()
        val windowSet = service.visibleWindowSet()
        // Anchors are evidence only: an unreadable or unresolvable window set yields no
        // evidence instead of evidence from a screen that does not match touch.
        if (!windowSet.complete) return null
        val seen = HashMap<String, MutableList<Pair<Int, Int>>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        windowSet.roots.forEach(queue::addLast)
        var visited = 0
        while (queue.isNotEmpty() && visited < FINGERPRINT_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited += 1
            val bounds = Rect().also(node::getBoundsInScreen)
            val key = identityKey(identityOf(node))
            seen.getOrPut(key) { ArrayList(1) }.add(bounds.left to bounds.top)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return wanted.mapNotNull { locator ->
            val key = identityKey(identityOf(locator))
            val positions = seen[key] ?: return@mapNotNull null
            if (positions.size != 1) return@mapNotNull null
            UiAnchorSample(key, positions[0].first, positions[0].second)
        }
    }

    /** Semantic key used to pair anchors across an action; positions are deliberately excluded. */
    private fun identityKey(identity: AccessibilityNodeIdentity): String = listOf(
        identity.windowId.toString(),
        identity.packageName,
        identity.className,
        identity.viewId,
        identity.text,
        identity.description,
        identity.password.toString(),
    ).joinToString("|")

    private fun uniqueIdOf(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { node.uniqueId.orEmpty() } catch (_: Throwable) { "" }
        } else {
            ""
        }

    private fun identityOf(node: AccessibilityNodeInfo): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
        uniqueId = uniqueIdOf(node),
        windowId = node.windowId,
        packageName = node.packageName?.toString().orEmpty(),
        className = node.className?.toString().orEmpty(),
        viewId = node.viewIdResourceName.orEmpty(),
        text = node.text?.toString().orEmpty(),
        description = node.contentDescription?.toString().orEmpty(),
        password = node.isPassword,
    )

    private fun identityOf(locator: UiLocator): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
        uniqueId = locator.uniqueId,
        windowId = locator.windowId,
        packageName = locator.packageName,
        className = locator.className,
        viewId = locator.resourceId,
        text = locator.text,
        description = locator.contentDescription,
        password = locator.password,
    )

    /** Every current node that still satisfies the observed identity, in tree order. */
    private fun matchIdentities(roots: List<AccessibilityNodeInfo>, locator: UiLocator): List<AccessibilityNodeInfo> {
        val wanted = identityOf(locator)
        val found = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach(queue::addLast)
        var visited = 0
        while (queue.isNotEmpty() && visited < FINGERPRINT_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited += 1
            if (wanted.matches(identityOf(node))) found.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return found
    }

    private fun nodeJson(node: AccessibilityNodeInfo, locator: UiLocator, depth: Int, parentRef: String?): JSONObject {
        val bounds = locator.bounds
        return JSONObject().apply {
            put("ref", locator.ref)
            parentRef?.let { put("parentRef", it) }
            put("depth", depth)
            if (locator.text.isNotEmpty()) put("text", locator.text)
            if (locator.contentDescription.isNotEmpty()) put("contentDescription", locator.contentDescription)
            if (locator.resourceId.isNotEmpty()) put("viewIdResourceName", locator.resourceId)
            if (locator.className.isNotEmpty()) put("className", locator.className)
            put("bounds", JSONObject()
                .put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
            put("clickable", node.isClickable)
            put("longClickable", node.isLongClickable)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("selected", node.isSelected)
            put("checked", node.isChecked)
            put("focused", node.isFocused)
            put("supportedActions", JSONArray(actionNames(node)))
        }
    }

    private fun actionNames(node: AccessibilityNodeInfo): List<String> = node.actionList.map { action ->
        when (action.id) {
            AccessibilityNodeInfo.ACTION_CLICK -> "click"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
            AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
            else -> action.label?.toString()?.takeIf(String::isNotBlank) ?: "action_${action.id}"
        }
    }.distinct()

    private fun fingerprint(roots: List<AccessibilityNodeInfo>): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach(queue::addLast)
        while (queue.isNotEmpty() && count < FINGERPRINT_NODE_LIMIT) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val line = buildString {
                append(node.windowId).append('|')
                append(node.packageName).append('|').append(node.className).append('|')
                append(node.viewIdResourceName).append('|').append(node.text).append('|')
                append(node.contentDescription).append('|').append(bounds.flattenToString()).append('|')
                append(node.childCount).append(';')
            }
            digest.update(line.toByteArray(Charsets.UTF_8))
            count += 1
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        digest.update("count=$count,roots=${roots.size}".toByteArray(Charsets.UTF_8))
        return Fingerprint(
            hash = digest.digest().joinToString("") { "%02x".format(it) },
            truncated = queue.isNotEmpty(),
        )
    }

    private fun trimLocked() {
        val now = System.currentTimeMillis()
        observations.entries.removeAll { now - it.value.createdAt > OBSERVATION_TTL_MS }
        while (observations.size > MAX_OBSERVATIONS) observations.remove(observations.keys.first())
    }
}
