package com.openminis.app.debug

import com.openminis.app.debug.DebugMethodRegistry.MethodSpec
import com.openminis.app.debug.DebugMethodRegistry.ParamSpec
import com.openminis.app.debug.DebugMethodRegistry.ex
import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Part 1 of the method catalogue [DebugMethodRegistry.methods] is built from. */
internal val baseMethodsPart1: List<MethodSpec> = listOf(
        MethodSpec(
            name = "rpc.discover",
            description = "Return the full list of supported methods with parameter schemas and examples.",
            params = emptyList(),
            returns = "{platform, version, build, methodCount, methods:[{name, description, params, returns, example}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.appInfo",
            description = "Return app metadata, device info, and disk usage.",
            params = emptyList(),
            returns = "{platform, sdkVersion, device, androidVersion, ubuntu, filesDir, logFiles, totalLogSize, diskUsage:{filesDir, sessions, global}}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.screenshot",
            description = "Capture a live screenshot of the foreground Activity as PNG.",
            params = listOf(
                ParamSpec("scale", "number", required = false, default = 1.0, description = "Render scale (0 < scale <= 1)."),
            ),
            returns = "{base64, size, encoding: 'png'}",
            example = ex("scale" to 0.5),
        ),
        MethodSpec(
            name = "debug.ls",
            description = "List directory contents inside the guest Linux filesystem.",
            params = listOf(
                ParamSpec("path", "string", required = false, default = "/", description = "Linux path under the guest rootfs."),
                ParamSpec("recursive", "bool", required = false, default = false, description = "Recurse into subdirectories."),
                ParamSpec("maxDepth", "int", required = false, default = 3, description = "Max recursion depth when recursive=true."),
            ),
            returns = "Array of {name, type, size, modified}; when recursive=true, directories include a 'children' array.",
            example = ex("path" to "/root", "recursive" to true, "maxDepth" to 2),
        ),
        MethodSpec(
            name = "debug.readFile",
            description = "Read a file from the guest rootfs with optional offset/limit slicing.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Linux path under the guest rootfs."),
                ParamSpec("offset", "long", required = false, default = 0, description = "Byte offset to start reading from."),
                ParamSpec("limit", "int", required = false, default = 524288, description = "Maximum bytes to read (default 512KB)."),
                ParamSpec("base64", "bool", required = false, default = false, description = "Force base64 encoding (otherwise detected from content)."),
            ),
            returns = "{size, content, encoding, bytesRead, truncated?}",
            example = ex("path" to "/etc/os-release", "limit" to 4096),
        ),
        MethodSpec(
            name = "debug.logs.list",
            description = "List AppLogger log files on disk.",
            params = emptyList(),
            returns = "{totalSize, files:[{name, size, modified}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.logs.read",
            description = "Read a log file by filename (no path separators allowed).",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Log filename (no '/' or '..')."),
                ParamSpec("offset", "int", required = false, default = 0, description = "Character offset to start reading from."),
                ParamSpec("limit", "int", required = false, default = 524288, description = "Maximum characters to read (default 512KB)."),
            ),
            returns = "{name, size, content, bytesRead, truncated?}",
            example = ex("name" to "2026-04-17.log", "limit" to 16384),
        ),
        MethodSpec(
            name = "provider.quickTest",
            description = "Run Quick Test on a model entry (same code path as the UI Quick Test sheet). " +
                "Tests applicable modalities (text, speechOut, transcription, imageGen) and returns " +
                "per-kind status. Audio/image results report byte counts, not payloads. " +
                "Android counterpart of iOS debug.providers.quickTest.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Model entry id from provider.models.list."),
                ParamSpec("kinds", "string[]", required = false, description = "Subset of text/speechOut/transcription/imageGen; omit to auto-detect."),
            ),
            returns = "{entryId, modelId, displayName, providerLabel, results:[{kind, status, elapsedMs, detail, bytes?}]}",
            example = ex("entryId" to "pi_xyz/mimo-v2.5-tts"),
        ),
        MethodSpec(
            name = "debug.crash.list",
            description = "List on-device crash reports (ACRA Java/Kotlin + native), newest first, " +
                "each with a one-line summary of the exception and first app frame.",
            params = listOf(
                ParamSpec("limit", "int", required = false, default = 20, description = "Max reports to return (1-200)."),
            ),
            returns = "{count, returned, crashes:[{name, size, modified, native, summary}]}",
            example = ex("limit" to 5),
        ),
        MethodSpec(
            name = "debug.crash.read",
            description = "Read a crash report. Omit 'name' to get the NEWEST crash — the usual " +
                "\"why did it just die?\" call. Returns the stack section only by default; " +
                "pass stackOnly=false for the full file including the embedded logcat tail.",
            params = listOf(
                ParamSpec("name", "string", required = false, description = "Report filename from debug.crash.list; omit for newest."),
                ParamSpec("stackOnly", "bool", required = false, default = true, description = "Trim the trailing logcat/Build dump."),
                ParamSpec("limit", "int", required = false, default = 262144, description = "Max characters to return."),
            ),
            returns = "{name, modified, native, fileSize, stackOnly, content, truncated?}",
            example = ex("stackOnly" to true),
        ),
        MethodSpec(
            name = "debug.tap",
            description = "Dispatch a tap gesture at the given Activity-space coordinates.",
            params = listOf(
                ParamSpec("x", "number", required = true, description = "Screen x in pixels."),
                ParamSpec("y", "number", required = true, description = "Screen y in pixels."),
            ),
            returns = "{ok, point:{x,y}}",
            example = ex("x" to 300, "y" to 600),
        ),
        MethodSpec(
            name = "debug.scroll",
            description = "Simulate a scroll gesture from a point by deltaX/deltaY.",
            params = listOf(
                ParamSpec("x", "number", required = true, description = "Start x in pixels."),
                ParamSpec("y", "number", required = true, description = "Start y in pixels."),
                ParamSpec("deltaX", "number", required = false, default = 0, description = "Horizontal delta."),
                ParamSpec("deltaY", "number", required = false, default = 0, description = "Vertical delta. At least one of deltaX/deltaY must be non-zero."),
            ),
            returns = "{ok, start:{x,y}, delta:{x,y}}",
            example = ex("x" to 300, "y" to 600, "deltaY" to -400),
        ),
        MethodSpec(
            name = "debug.inputText",
            description = "Type text into the currently focused field.",
            params = listOf(
                ParamSpec("text", "string", required = true, description = "Text to input."),
            ),
            returns = "{ok, length}",
            example = ex("text" to "hello world"),
        ),
        MethodSpec(
            name = "debug.setClipboard",
            description = "Put text on the system clipboard from inside the app process, " +
                "so `input keyevent PASTE` can trigger a REAL single-shot paste. " +
                "debug.inputText types per character and therefore cannot exercise " +
                "paste-detection logic.",
            params = listOf(
                ParamSpec("text", "string", required = true, description = "Text to place on the clipboard."),
                ParamSpec("label", "string", required = false, default = "minis-debug", description = "ClipData label."),
            ),
            returns = "{ok, length, clipboardLength}",
            example = ex("text" to "a long block of text"),
        ),
        MethodSpec(
            name = "debug.llmRequests",
            description = "Return captured LLM provider requests/responses for debugging.",
            params = listOf(
                ParamSpec("last", "int", required = false, description = "Limit to the N most recent entries."),
                ParamSpec("formatted", "bool", required = false, default = false, description = "If true, return a single 'text' string (same shape as iOS Copy Requests output) instead of structured JSON."),
            ),
            returns = "{count, requests:[...]} or {count, text} when formatted=true",
            example = ex("last" to 5),
        ),
        MethodSpec(
            name = "debug.llmRequests.clear",
            description = "Clear the captured LLM request log.",
            params = emptyList(),
            returns = "{cleared: true}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.agentTrace",
            description = "Get agent request traces (alias of debug.llmRequests, iOS-compatible shape).",
            params = listOf(
                ParamSpec("last", "bool", required = false, default = false, description = "If true, return only the most recent trace object instead of {traces:[...]}."),
            ),
            returns = "{traces:[...]} or a single trace object when last=true",
            example = ex("last" to true),
        ),
        MethodSpec(
            name = "debug.fetch",
            description = "Diagnostic HTTP probe: runs DNS, HttpURLConnection, OkHttp, and proxy-selector checks against a URL.",
            params = listOf(
                ParamSpec("url", "string", required = true, description = "Absolute URL to probe."),
            ),
            returns = "{dns?, dns_error?, httpurlconn_status?, httpurlconn_error?, okhttp_status?, okhttp_error?, proxies?, proxy_error?}",
            example = ex("url" to "https://example.com"),
        ),
        MethodSpec(
            name = "debug.logs.setEnabled",
            description = "Enable or disable AppLogger file capture at runtime.",
            params = listOf(
                ParamSpec("enabled", "bool", required = true, description = "true to start capturing, false to stop."),
            ),
            returns = "{enabled}",
            example = ex("enabled" to true),
        ),
        MethodSpec(
            name = "debug.viewTree",
            description = "Return the foreground Activity's View hierarchy with embedded Compose semantics nodes (shallow). Addresses are good only until the next viewTree/search call.",
            params = listOf(
                ParamSpec("maxDepth", "int", required = false, default = 50, description = "Maximum tree depth to traverse."),
            ),
            returns = "Array of root nodes; each node has {address, type, bounds, text?, description?, children?, compose?}.",
            example = ex("maxDepth" to 20),
        ),
        MethodSpec(
            name = "debug.search",
            description = "Search the View + Compose semantics tree for nodes matching a keyword (case-insensitive substring).",
            params = listOf(
                ParamSpec("keyword", "string", required = true, description = "Substring matched against text, contentDescription, type, or testTag."),
                ParamSpec("scope", "string", required = false, default = "all", description = "'all', 'text', or 'type'."),
            ),
            returns = "Array of matching nodes (shallow describe shape).",
            example = ex("keyword" to "Login", "scope" to "text"),
        ),
        MethodSpec(
            name = "debug.inspect",
            description = "Return full properties of the node at the given address from a prior viewTree/search call.",
            params = listOf(
                ParamSpec("address", "string", required = true, description = "Address returned by viewTree/search."),
            ),
            returns = "Node object with extra detail (alpha, class, idName, actions…), or {error} if not found.",
            example = ex("address" to "0x12abcd"),
        ),
        MethodSpec(
            name = "debug.highlight",
            description = "Flash a colored overlay on a specific node for visual verification.",
            params = listOf(
                ParamSpec("address", "string", required = true, description = "Address returned by viewTree/search."),
                ParamSpec("color", "string", required = false, default = "red", description = "Named color: red, green, blue, yellow, purple, orange — or any #RRGGBB."),
                ParamSpec("duration", "number", required = false, default = 2.0, description = "Seconds to keep the overlay visible (0.1..10)."),
            ),
            returns = "{ok: bool}",
            example = ex("address" to "0x12abcd", "color" to "green", "duration" to 1.5),
        ),
        MethodSpec(
            name = "debug.cloudSync",
            description = "Return cloud sync status. Android does not implement iCloud sync — this method is a stub returning {enabled: false, status: 'unsupported'} for iOS API compatibility.",
            params = emptyList(),
            returns = "{enabled, status, devices, deviceCount}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.writeFile",
            description = "Write a file into the App-owned guest workspace or an authorized external mount. Intended for staging test fixtures before debug.shellExecute; Root-owned rootfs paths are rejected.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Linux path under App-owned guest storage (e.g. /var/minis/workspace/test.sh, /var/minis/skills/example.md)."),
                ParamSpec("content", "string", required = true, description = "File content. Encoded per the 'encoding' field."),
                ParamSpec("encoding", "string", required = false, default = "utf8", description = "'utf8' or 'base64'."),
                ParamSpec("overwrite", "bool", required = false, default = true, description = "Whether to overwrite an existing file."),
                ParamSpec("mode", "string", required = false, default = "0644", description = "Accepted for cross-platform compatibility; App-owned workspace files use the secure file API's default mode."),
            ),
            returns = "{ok, path, hostPath, size}",
            example = ex("path" to "/var/minis/workspace/hello.sh", "content" to "IyEvYmluL3NoCmVjaG8gaGVsbG8K", "encoding" to "base64"),
        ),
        MethodSpec(
            name = "debug.screenshot.capture",
            description = "Capture a screenshot into the in-memory ring buffer with an optional label.",
            params = listOf(
                ParamSpec("label", "string", required = false, default = "", description = "Free-form label."),
                ParamSpec("scale", "number", required = false, default = 0.5, description = "Render scale (0 < scale <= 1)."),
            ),
            returns = "{id, label, timestamp, size}",
            example = ex("label" to "before-tap"),
        ),
        MethodSpec(
            name = "debug.screenshot.list",
            description = "List entries in the screenshot ring buffer (newest last).",
            params = emptyList(),
            returns = "{count, entries:[{id, label, timestamp, size}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.screenshot.get",
            description = "Return a single screenshot from the ring buffer as base64 PNG.",
            params = listOf(
                ParamSpec("id", "int", required = false, description = "Entry id; defaults to the latest entry."),
            ),
            returns = "{id, label, timestamp, base64, size, encoding: 'png'}",
            example = ex("id" to 3),
        ),
        MethodSpec(
            name = "debug.screenshot.clear",
            description = "Clear the screenshot ring buffer.",
            params = emptyList(),
            returns = "{cleared: int}",
            example = JSONObject(),
        ),

        // --- Browser ---
        MethodSpec(
            name = "debug.browser.listTabs",
            description = "List all open browser tabs in the application-scoped pool.",
            params = emptyList(),
            returns = "{tabs:[{id, url, title, selected, inUse}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.pageInfo",
            description = "Get URL/title/loading-state/viewport for a tab.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, url, title, isLoading, canGoBack, canGoForward, viewport, text}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.executeJS",
            description = "Run arbitrary JavaScript in a tab's WebView and return the evaluated result.",
            params = listOf(
                ParamSpec("script", "string", required = true, description = "JS source. The expression's value is stringified and returned."),
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{result}",
            example = ex("script" to "document.title"),
        ),
        MethodSpec(
            name = "debug.browser.getReadable",
            description = "Run the Readability-style content extraction on a tab.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, text, length}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.getText",
            description = "Extract text content from the page, optionally scoped to a CSS selector.",
            params = listOf(
                ParamSpec("selector", "string", required = false, description = "CSS selector to scope the extraction."),
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, text, length}",
            example = ex("selector" to "article"),
        ),
        MethodSpec(
            name = "debug.browser.screenshot",
            description = "Capture a JPEG screenshot of a tab's WebView.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{base64, size, encoding: 'jpeg', tabId}",
            example = JSONObject(),
        ),

        // --- Provider (read-only — Phase 1) ---
        MethodSpec(
            name = "provider.types",
            description = "Return the schema of every supported provider type, including built-in model ids and OAuth flows.",
            params = emptyList(),
            returns = "{types:[{id, displayName, supportedCredentials, oauthFlow, defaultBaseURL, customBaseURLSupported, appendV1SuffixConfigurable, builtInModelIds}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "provider.instances.list",
            description = "List all configured provider instances (write-only credential material excluded).",
            params = listOf(
                ParamSpec("includeDisabled", "bool", required = false, default = true, description = "If false, omit instances where isEnabled=false."),
            ),
            returns = "{count, instances:[{id, label, providerType, credentialType, isEnabled, customBaseURL, appendV1Suffix, useResponsesAPI, createdAt, hasCredential, modelEntryCount}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "provider.models.list",
            description = "List model entries for a specific provider instance.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("includeHidden", "bool", required = false, default = false, description = "Include user-hidden entries."),
            ),
            returns = "{instanceId, instanceLabel, count, entries:[...]}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.export",
            description = "Export a provider instance (config + models + stored API key) as a portable JSON envelope. Mirrors the in-app Share/Export button. The API key is base64-wrapped to survive JSON string escaping; this is NOT a security boundary — callers of the 5321 RPC are trusted.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
            ),
            returns = "{version, exportedAt, platform, instanceId, instanceLabel, config:{providerType, label, credentialType, apiKey?, customBaseURL?, models:[...]}}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.import",
            description = "Import a provider instance from a JSON document produced by `provider.export` or the in-app Share button. Accepts either the wrapped envelope or the raw legacy config JSON for cross-platform compatibility. Always assigns a new instance UUID; duplicate labels are auto-suffixed.",
            params = listOf(
                ParamSpec("configJson", "string", required = true, description = "Either the {version,config:{...}} envelope or the raw legacy config JSON."),
            ),
            returns = "{instanceId, instanceLabel, modelEntryCount, success}",
            example = ex("configJson" to "{\"version\":1,\"config\":{...}}"),
        ),
        MethodSpec(
            name = "provider.slots.get",
            description = "List the five fixed model slots and their ordered entry IDs.",
            params = emptyList(),
            returns = "{count, fallbackTrigger, slots:{main:{entryIds,entries}, light:{...}, vision:{...}, voiceInput:{...}, voiceOutput:{...}, image:{...}}}",
            example = JSONObject(),
        ),

        // --- Chat (read-only — Phase 1) ---
        MethodSpec(
            name = "chat.sessions.list",
            description = "List recent chat sessions sorted by updated_at desc.",
            params = listOf(
                ParamSpec("limit", "int", required = false, default = 50, description = "Max sessions to return (1..500)."),
                ParamSpec("includeEmpty", "bool", required = false, default = false, description = "If true, include sessions with no messages."),
            ),
            returns = "{count, sessions:[...]}",
            example = ex("limit" to 20),
        ),
        MethodSpec(
            name = "chat.sessions.get",
            description = "Fetch a single session's metadata.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{id, title, modelId, modelName, source, isRunning, memoryEnabled, category, messageCount, createdAt, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.sessions.usage",
            description = "Aggregate token usage for a session, optionally broken down per assistant turn.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("perTurn", "bool", required = false, default = false, description = "If true, include a turns:[] breakdown."),
            ),
            returns = "{sessionId, totals:{...}, turns?}",
            example = ex("sessionId" to "6D0F…", "perTurn" to true),
        ),
        MethodSpec(
            name = "chat.messages.list",
            description = "List messages in a session in chronological order, with optional filtering.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("limit", "int", required = false, default = 200, description = "Max messages (1..1000)."),
                ParamSpec("offset", "int", required = false, default = 0, description = "Skip the first N matched messages."),
                ParamSpec("roles", "[string]", required = false, description = "Filter by role: user/assistant/system/tool."),
                ParamSpec("includeTools", "bool", required = false, default = true, description = "Include tool-call/tool-result blocks."),
                ParamSpec("includeReasoning", "bool", required = false, default = false, description = "Include captured reasoning_content."),
            ),
            returns = "{sessionId, totalCount, count, messages:[...]}",
            example = ex("sessionId" to "6D0F…", "limit" to 50),
        ),
)
