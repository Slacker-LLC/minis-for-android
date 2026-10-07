package com.openminis.app.browser

import android.webkit.CookieManager
import android.webkit.WebView
import com.openminis.app.browser.BrowserUseManager.Companion.DEFAULT_DOM_STABLE_TIMEOUT_MS
import com.openminis.app.browser.BrowserUseManager.Companion.MAX_DOM_STABLE_TIMEOUT_MS
import com.openminis.app.browser.BrowserUseManager.Companion.MIN_DOM_STABLE_TIMEOUT_MS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Upstream's `reload` action: reload the tab, then say where it landed. */
internal suspend fun BrowserUseManager.reloadPage(): BrowserActionResult {
    reloadAndWait()
    return BrowserActionResult(
        text = BrowserHistoryPolicy.reloaded() + "\n" + navigationMetadata(),
        pageURL = _currentURL.value,
    )
}

fun BrowserUseManager.loadURL(urlString: String) {
    var normalized = urlString
    if (!normalized.contains("://")) normalized = "https://$normalized"
    _isLoading.value = true
    webView.loadUrl(normalized)
}

// -- JS Evaluation Helpers --

internal suspend fun BrowserUseManager.evaluateJavascript(js: String): String = withContext(Dispatchers.Main) {
    val deferred = CompletableDeferred<String>()
    webView.evaluateJavascript(js) { result ->
        // Android WebView returns JSON-encoded strings, so unquote
        val unquoted = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
            try {
                JSONObject("{\"v\":$result}").getString("v")
            } catch (_: Exception) {
                result
            }
        } else {
            result ?: "null"
        }
        deferred.complete(unquoted)
    }
    deferred.await()
}

internal suspend fun BrowserUseManager.evaluateAndReturn(js: String): BrowserActionResult {
    return try {
        val raw = evaluateJavascript(js)
        val json = try { JSONObject(raw) } catch (_: Exception) { null }
        if (json != null) {
            if (json.has("error")) {
                BrowserActionResult.error(json.getString("error"))
            } else {
                BrowserActionResult(
                    text = formatJSONResult(BrowserPayloadLimiter.bound(json)),
                )
            }
        } else {
            BrowserActionResult(text = BrowserPayloadLimiter.boundText(raw))
        }
    } catch (e: Exception) {
        BrowserActionResult.error("JavaScript error: ${e.message}")
    }
}

internal suspend fun BrowserUseManager.evaluateJSAndParse(js: String): BrowserActionResult {
    return try {
        val raw = evaluateJavascript(js)
        val json = try { JSONObject(raw) } catch (_: Exception) { null }
        if (json != null) {
            if (json.has("error")) {
                BrowserActionResult.error(json.getString("error"))
            } else {
                BrowserActionResult(
                    text = formatJSONResult(BrowserPayloadLimiter.bound(json)),
                )
            }
        } else {
            BrowserActionResult(text = BrowserPayloadLimiter.boundText(raw))
        }
    } catch (e: Exception) {
        BrowserActionResult.error("JavaScript error: ${e.message}")
    }
}

// -- Result Formatting --

internal fun BrowserUseManager.formatJSONResult(json: JSONObject): String = buildString {
    when {
        json.optBoolean("clicked") -> {
            // [T-browser-targeting-android] The hit element is reported the way
            // find_elements reports one, so a click that landed on the wrong node
            // is visible in the transcript instead of implied by a tag name.
            val tag = json.optString("tag", "?").lowercase()
            val text = json.optString("text", "").take(160).replace('"', '\'')
            appendLine(
                buildString {
                    append("Clicked <").append(tag).append('>')
                    if (text.isNotEmpty()) append(" \"").append(text).append('"')
                },
            )
            BrowserElementListFormatter
                .detail(json.optJSONObject("matched_element"))
                ?.let { appendLine(it) }
        }
        json.optBoolean("typed") -> {
            val sel = json.optString("selector", "?")
            val len = json.optInt("length", 0)
            append("Typed $len chars into $sel")
            if (json.optBoolean("submitted", false)) append(" (form submitted)")
            BrowserElementListFormatter
                .detail(json.optJSONObject("matched_element"))
                ?.let { appendLine(); append(it) }
        }
        json.optBoolean("scrolled") -> {
            val dir = json.optString("direction", "?")
            val amt = json.optInt("amount", 0)
            append("Scrolled ").append(dir).append(' ').append(amt).append("px")
            // [T-browser-scroll-evidence-android] Upstream reports where the
            // scroll started and where it ended; without the pair, "did it move"
            // is unanswerable from a single reading.
            if (json.has("before") && json.has("after")) {
                append(" (position ")
                append(json.optInt("before")).append(" -> ").append(json.optInt("after"))
                append(')')
            }
            appendLine()
            if (!json.has("before") && json.has("scrollY")) {
                appendLine("  Scroll Y: ${json.optInt("scrollY")}")
            }
            if (json.has("scrollHeight")) appendLine("  Page height: ${json.optInt("scrollHeight")}")
            if (json.has("viewportHeight")) append("  Viewport height: ${json.optInt("viewportHeight")}")
        }
        json.has("scrolledTo") -> {
            val sel = json.optString("scrolledTo")
            val tag = json.optString("tag", "?")
            append("Scrolled to <$tag> ($sel)")
        }
        json.optBoolean("hovered") -> {
            val tag = json.optString("tag", "?")
            appendLine("Hovered <$tag>")
            val text = json.optString("text", "")
            if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
        }
        json.has("text") && (json.has("text_length") || json.has("length")) -> {
            val title = json.optString("title", "")
            if (title.isNotEmpty()) appendLine("Title: $title")
            val text = json.optString("text", "")
            if (json.has("text_length")) {
                // [T-browser-paged-text-android] The header says which slice of the
                // document this is and the offset that continues it; without it a
                // clipped page reads exactly like a short one, and the model has no
                // way to ask for the rest.
                val total = json.optInt("text_length", text.length)
                val offset = json.optInt("offset", 0)
                val returned = json.optInt("returned_chars", text.length)
                val window = BrowserTextWindowPolicy.window(offset, returned, total)
                appendLine(
                    BrowserTextWindowPolicy
                        .describe(window, total, json.optBoolean("source_truncated", false)) + ":",
                )
            } else {
                val len = json.optInt("length", text.length)
                appendLine("Text ($len chars):")
            }
            append(text)
        }
        json.has("elements") -> {
            // [T-browser-element-rows-android] One row per element plus its
            // selector / accessibility fields / box; the payload limiter drops
            // trailing rows, and the formatter prints what is actually here.
            append(BrowserElementListFormatter.format(json))
        }
        else -> {
            // Fallback: format each key-value pair
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                appendLine("  $key: ${json.opt(key)}")
            }
        }
    }
    // [T-browser-payload-budget-android] Upstream budgets every browser payload
    // at 12 KiB; when that gate (or its element ladder) had to cut this one the
    // model is told, instead of reading a clipped result as a complete one.
    if (json.optBoolean("payload_truncated") || json.optBoolean("elements_truncated")) {
        appendLine()
        append("Payload truncated to stay within the browser result budget")
        if (json.optBoolean("elements_truncated")) {
            append("; element_count=").append(json.optInt("element_count", 0))
        }
        if (json.optBoolean("payload_truncated")) {
            append("; text_length=").append(json.optInt("text_length", 0))
        }
        append('.')
    }
}.trimEnd()

internal fun BrowserUseManager.formatBackboneResult(json: JSONObject): String = buildString {
    val nodeCount = json.optInt("nodeCount")
    val depth = json.optInt("depth")
    val merged = json.optInt("merged")
    appendLine("Page backbone: $nodeCount nodes, depth $depth, $merged merged")

    val backbone = json.optJSONArray("backbone")
    if (backbone != null) {
        for (i in 0 until backbone.length()) {
            formatBackboneNode(backbone.getJSONObject(i), 0, this)
        }
    }
}.trimEnd()

internal fun BrowserUseManager.formatBackboneNode(node: JSONObject, indent: Int, sb: StringBuilder) {
    val pad = "  ".repeat(indent)
    val tag = node.optString("tag", "?")
    val sel = node.optString("sel", "")
    val rect = node.optString("rect", "")

    val header = buildString {
        append("$pad<$tag>")
        val id = node.optString("id", "")
        if (id.isNotEmpty()) append(" #$id")
        val cls = node.optString("cls", "")
        if (cls.isNotEmpty()) append(" .${cls.replace(" ", ".")}")
        val role = node.optString("role", "")
        if (role.isNotEmpty()) append(" [$role]")
        append(" | $sel | $rect")
    }
    sb.appendLine(header)

    val text = node.optString("text", "")
    if (text.isNotEmpty()) sb.appendLine("$pad  \"$text\"")
    val href = node.optString("href", "")
    if (href.isNotEmpty()) sb.appendLine("$pad  -> $href")
    val img = node.optString("img", "")
    if (img.isNotEmpty()) sb.appendLine("$pad  img: $img")
    val input = node.optString("input", "")
    if (input.isNotEmpty()) sb.appendLine("$pad  input: $input")

    val children = node.optJSONArray("children")
    if (children != null) {
        for (i in 0 until children.length()) {
            formatBackboneNode(children.getJSONObject(i), indent + 1, sb)
        }
    }
}

internal fun BrowserUseManager.extensionForMimeType(mime: String): String {
    val lower = mime.lowercase().split(";").firstOrNull()?.trim() ?: ""
    return when (lower) {
        "text/html" -> "html"; "text/plain" -> "txt"; "text/css" -> "css"; "text/csv" -> "csv"
        "application/json" -> "json"; "application/xml", "text/xml" -> "xml"
        "application/pdf" -> "pdf"; "image/png" -> "png"; "image/jpeg" -> "jpg"
        "image/gif" -> "gif"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"
        "application/zip" -> "zip"; "application/gzip" -> "gz"
        else -> "bin"
    }
}

internal fun BrowserUseManager.formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

// -- Get Cookies --

/**
 * Return cookies for the current page's origin, filtered by keyword.
 * Android exposes cookies as a single `Cookie` header string via
 * [CookieManager]; we split on `;` and match each `name=value` pair.
 *
 * `fuzzy=false` (default): exact name match (case-insensitive).
 * `fuzzy=true`: substring match within the cookie name.
 */
internal fun BrowserUseManager.getCookies(keywords: List<String>?, fuzzy: Boolean): BrowserActionResult {
    val url = _currentURL.value.takeIf { it.isNotEmpty() }
        ?: return BrowserActionResult.error("get_cookies: no page is loaded (navigate first)")
    val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
        ?: return BrowserActionResult.error("get_cookies: CookieManager unavailable")
    val raw = cookieMgr.getCookie(url).orEmpty()
    if (raw.isEmpty()) {
        return BrowserActionResult(text = "No cookies set for $url")
    }
    val pairs = raw.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
        }
    val filtered = if (keywords.isNullOrEmpty()) pairs else pairs.filter { (name, _) ->
        keywords.any { kw ->
            if (fuzzy) name.contains(kw, ignoreCase = true)
            else name.equals(kw, ignoreCase = true)
        }
    }
    val text = buildString {
        appendLine("Cookies for $url (${filtered.size} of ${pairs.size}):")
        for ((name, value) in filtered) {
            val preview = if (value.length > 80) value.take(77) + "…" else value
            appendLine("  $name = $preview")
        }
    }.trimEnd()
    return BrowserActionResult(text = text)
}

// -- Set Cookies --

/**
 * Write cookies into the WebView cookie store via [CookieManager.setCookie],
 * which accepts a Set-Cookie-style string and (unlike `document.cookie`) can
 * set HttpOnly cookies. Symmetric with [getCookies]. Each entry needs
 * `name` + `value`; `domain` defaults to the current page host, `path` to
 * "/". Mirrors iOS `setCookies`.
 */
internal fun BrowserUseManager.setCookies(cookies: List<Map<String, Any?>>?): BrowserActionResult {
    val url = _currentURL.value.takeIf { it.isNotEmpty() }
        ?: return BrowserActionResult.error("set_cookies: no page is loaded (navigate first)")
    // Distinguish "field omitted/unparseable" from "field present but empty".
    // The schema types `cookies` as a string, so a model may send a JSON
    // STRING that failed to re-parse, or the CLI's shell-escaping mangled the
    // array — guide the caller instead of a confusing empty result.
    if (cookies == null) {
        return BrowserActionResult.error(
            "set_cookies: 'cookies' must be a JSON array of cookie objects " +
                "(e.g. [{\"name\":\"foo\",\"value\":\"bar\"}]). It was missing or could not be " +
                "parsed — if you passed it as a string, ensure it is valid JSON; from the CLI " +
                "prefer --cookies-file <path> to avoid shell escaping.",
        )
    }
    if (cookies.isEmpty()) {
        return BrowserActionResult.error(
            "set_cookies: 'cookies' array is empty — provide at least one {name, value} object.",
        )
    }
    val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
        ?: return BrowserActionResult.error("set_cookies: CookieManager unavailable")

    // Default domain = current page host.
    val defaultDomain = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

    // expires (Unix seconds) → RFC-1123 "Expires=" date in GMT.
    val httpDateFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }

    val setNames = mutableListOf<String>()
    val domainsTouched = linkedSetOf<String>()
    val failures = mutableListOf<String>()

    for (raw in cookies) {
        // Accept the field-name variants common cookie exports use (browser
        // extensions EditThisCookie / Cookie-Editor, Playwright / Puppeteer
        // storage), so a model can paste cookies verbatim. [set-cookies-formats]
        val name = cookieString(raw, "name")?.takeIf { it.isNotEmpty() }
        val value = cookieString(raw, "value")
        if (name == null || value == null) {
            failures.add("(missing name/value)")
            continue
        }
        val domain = cookieString(raw, "domain")?.takeIf { it.isNotEmpty() } ?: defaultDomain
        val path = cookieString(raw, "path")?.takeIf { it.isNotEmpty() } ?: "/"

        val sb = StringBuilder()
        sb.append(name).append('=').append(value)
        if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
        sb.append("; Path=").append(path)
        if (cookieBool(raw, "secure") == true) sb.append("; Secure")
        // camelCase httpOnly (extensions / Playwright) + snake_case http_only.
        if (cookieBool(raw, "http_only", "httpOnly") == true) sb.append("; HttpOnly")
        // Expiry in Unix seconds. Aliases: expires (Puppeteer) + expirationDate
        // (EditThisCookie / Cookie-Editor, often fractional). <= 0 (Puppeteer's
        // -1, or 0) → session cookie (no Expires attribute).
        cookieNumber(raw, "expires", "expirationDate")?.takeIf { it > 0 }?.let { expires ->
            val date = java.util.Date(expires.toLong() * 1000L)
            sb.append("; Expires=").append(httpDateFmt.format(date))
        }
        // sameSite accepted (Lax/Strict/None, any case) so exports including
        // it aren't rejected, but NOT applied yet — CookieManager.setCookie
        // honors a SameSite attribute, but wiring it needs validation against
        // the cross-site captcha flows. TODO [set-cookies-samesite].
        @Suppress("UNUSED_VARIABLE")
        val sameSite = cookieString(raw, "sameSite", "same_site")

        cookieMgr.setCookie(url, sb.toString())
        setNames.add(name)
        domainsTouched.add(domain)
    }
    cookieMgr.flush()

    if (setNames.isEmpty()) {
        return BrowserActionResult.error(
            "set_cookies: no cookies were set (every entry was invalid: ${failures.joinToString(", ")})",
        )
    }
    val domainLabel = if (domainsTouched.size == 1) domainsTouched.first() else domainsTouched.joinToString(", ")
    var text = "Set ${setNames.size} cookie(s) for $domainLabel: ${setNames.joinToString(", ")}"
    if (failures.isNotEmpty()) {
        text += "\nSkipped ${failures.size} invalid entry(ies): ${failures.joinToString(", ")}"
    }
    return BrowserActionResult(text = text)
}

// -- Cookie field readers (format-tolerant) --

/** Look up `aliases` in the map: exact match first, then case-insensitive,
 *  so httpOnly / HttpOnly / http_only all resolve. */
internal fun BrowserUseManager.cookieValue(raw: Map<String, Any?>, vararg aliases: String): Any? {
    for (key in aliases) raw[key]?.let { return it }
    val lowered = aliases.map { it.lowercase() }.toSet()
    for ((k, v) in raw) if (k.lowercase() in lowered && v != null) return v
    return null
}

/** String reader; numbers are stringified so a numeric `value` still works. */
internal fun BrowserUseManager.cookieString(raw: Map<String, Any?>, vararg aliases: String): String? =
    when (val v = cookieValue(raw, *aliases)) {
        is String -> v
        is Number -> v.toString()
        else -> null
    }

/** Bool reader; tolerates JSON bool, 0/1, and stringified "true"/"false". */
internal fun BrowserUseManager.cookieBool(raw: Map<String, Any?>, vararg aliases: String): Boolean? =
    when (val v = cookieValue(raw, *aliases)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.lowercase() in setOf("true", "1", "yes")
        else -> null
    }

/** Numeric (seconds) reader; accepts JSON number or numeric string. */
internal fun BrowserUseManager.cookieNumber(raw: Map<String, Any?>, vararg aliases: String): Double? =
    when (val v = cookieValue(raw, *aliases)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

// -- Wait for DOM Stable --

/**
 * [T-browser-wait-for-selector-android] Ported from Eta `waitForSelector`
 * (Mangi-11/Eta @ c15de97): poll the page-side `selectorState` until the
 * selector matches something the browser can render, up to the clamped budget.
 * `wait_for_dom_stable` watches the document as a whole; this waits for the one
 * element the next click needs — which is what an app that renders after a
 * click actually requires, and what a mutation-stability heuristic guesses at.
 *
 * An invalid selector fails on the first poll instead of burning the budget:
 * the page-side `querySelectorAll` throws and the wrapper hands back the error.
 */
internal suspend fun BrowserUseManager.waitForSelector(selector: String?, timeoutMs: Int?): BrowserActionResult {
    if (selector.isNullOrBlank()) {
        return BrowserActionResult.error("wait_for_selector requires 'selector'")
    }
    val budget = BrowserSelectorWaitPolicy.timeout(timeoutMs)
    val deadline = System.currentTimeMillis() + budget
    while (true) {
        val state = try {
            JSONObject(evaluateJavascript(BrowserDomScripts.selectorState(selector)))
        } catch (e: Exception) {
            return BrowserActionResult.error("JavaScript error: ${e.message}")
        }
        if (state.has("error")) {
            return BrowserActionResult.error(state.optString("error", "selector lookup failed"))
        }
        if (state.optBoolean("found")) {
            val elapsed = budget - (deadline - System.currentTimeMillis())
            return BrowserActionResult(
                text = BrowserSelectorWaitPolicy.found(
                    selector = selector,
                    elapsedMs = elapsed,
                    enabled = state.optBoolean("enabled", false),
                ),
            )
        }
        if (System.currentTimeMillis() >= deadline) break
        delay(BrowserSelectorWaitPolicy.POLL_INTERVAL_MS)
    }
    return BrowserActionResult(
        text = BrowserSelectorWaitPolicy.notFound(selector, budget),
        success = false,
    )
}

/**
 * Poll the DOM for stability: repeatedly measures `document.body.innerHTML.length`
 * at ~200ms intervals and returns when two successive readings match, or the
 * timeout elapses. Matches iOS `wait_for_dom_stable`.
 */
internal suspend fun BrowserUseManager.waitForDomStable(timeoutMs: Int?): BrowserActionResult {
    val budget = (timeoutMs ?: DEFAULT_DOM_STABLE_TIMEOUT_MS).coerceIn(
        MIN_DOM_STABLE_TIMEOUT_MS, MAX_DOM_STABLE_TIMEOUT_MS,
    )
    val pollInterval = 200L
    var lastSize = -1L
    var stable = false
    val deadline = System.currentTimeMillis() + budget
    // [T-android-domstable-min-budget] C5 fast path: a document that has
    // already finished loading (readyState === 'complete') is almost
    // always stable — confirm with two samples 50ms apart and return
    // without paying the 200ms poll interval. Keeps trivial static
    // pages (e.g. minis:// docs) fast at any budget.
    val readyState = evaluateJavascript(
        "(function(){try{return document.readyState;}catch(e){return '';}})()"
    ).trim('"')
    if (readyState == "complete") {
        val first = evaluateJavascript(
            "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
        ).toLongOrNull() ?: -1L
        delay(50)
        val second = evaluateJavascript(
            "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
        ).toLongOrNull() ?: -1L
        // [T-android-review-p1-fixes] F4: require a NON-EMPTY body.
        // SPAs report readyState=complete with an empty/skeleton body
        // before the JS app renders — `first >= 0` declared those
        // "stable" instantly and the agent read a blank page. Empty
        // bodies fall through to the polling loop unchanged (which can
        // legitimately conclude an actually-empty page is stable, but
        // only after giving scripts the full budget to render).
        if (first == second && first > 0) {
            return BrowserActionResult(
                text = "DOM stable immediately (readyState=complete, body length=$first)",
            )
        }
        // Fast path inconclusive (DOM still mutating post-load, or body
        // still empty) — fall through to the normal polling loop with
        // lastSize untouched so its stability criterion stays exactly
        // as before.
    }
    while (System.currentTimeMillis() < deadline) {
        val raw = evaluateJavascript(
            "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
        )
        val size = raw.toLongOrNull() ?: -1L
        if (size == lastSize && size >= 0) { stable = true; break }
        lastSize = size
        delay(pollInterval)
    }
    val elapsed = budget - (deadline - System.currentTimeMillis())
    return if (stable) {
        BrowserActionResult(text = "DOM stable after ${elapsed}ms (body length=$lastSize)")
    } else {
        BrowserActionResult(
            text = "DOM did not stabilize within ${budget}ms (last body length=$lastSize)",
            success = false,
        )
    }
}

// -- Scroll and Collect --

/**
 * Scroll the page [scrollCount] times, collecting text of every element
 * matching [itemSelector]. Optional [keywords] filter the collected text
 * (case-insensitive substring match). Mirrors iOS `scroll_and_collect`.
 */
internal suspend fun BrowserUseManager.scrollAndCollect(
    scrollCount: Int?,
    itemSelector: String?,
    keywords: List<String>?,
): BrowserActionResult {
    val iterations = (scrollCount ?: 5).coerceIn(1, 50)
    val selector = itemSelector?.takeIf { it.isNotBlank() }
        ?: return BrowserActionResult.error("scroll_and_collect requires --item-selector")

    val collected = LinkedHashSet<String>()
    for (i in 0 until iterations) {
        val escaped = selector.replace("\\", "\\\\").replace("'", "\\'")
        val raw = evaluateJavascript(
            """(function(){
                try {
                    var nodes = document.querySelectorAll('$escaped');
                    var out = [];
                    for (var i=0;i<nodes.length;i++) {
                        var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                        if (t) out.push(t);
                    }
                    return JSON.stringify(out);
                } catch(e) { return '[]'; }
            })()"""
        )
        try {
            val arr = org.json.JSONArray(raw)
            for (j in 0 until arr.length()) {
                val s = arr.optString(j)
                if (s.isNotBlank()) collected.add(s)
            }
        } catch (_: Exception) { /* ignore malformed batches */ }

        // Scroll one viewport down and let the page settle before re-querying.
        evaluateJavascript("window.scrollBy(0, window.innerHeight);")
        delay(400)
    }

    val filtered = if (keywords.isNullOrEmpty()) collected.toList()
        else collected.filter { text -> keywords.any { k -> text.contains(k, ignoreCase = true) } }

    val text = buildString {
        appendLine("scroll_and_collect: $iterations scrolls, selector='$selector'")
        appendLine("  matched: ${filtered.size} / ${collected.size} total")
        for ((i, item) in filtered.withIndex()) {
            val preview = if (item.length > 160) item.take(157) + "…" else item
            appendLine("  [${i + 1}] $preview")
        }
    }.trimEnd()
    return BrowserActionResult(text = text)
}
