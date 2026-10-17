package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {
    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        /**
         * Agent preset's real tool configuration: `CORE` (minimal mode)
         * exposes the persistent shell + file triad and task controls; `FULL` keeps the
         * whole capability set.
         */
        presetToolset: com.openminis.app.remote.AgentPresetRegistry.Toolset =
            com.openminis.app.remote.AgentPresetRegistry.Toolset.FULL,
        // Names of the enabled sub agents (the `subagent.agent` enum) while sub agents are allowed
        // (Settings); null withholds delegation. Existing transcripts keep the same tool name.
        subAgentRosterNames: List<String>? = if (com.openminis.app.agent.subagents.SubAgents.isEnabled())
            com.openminis.app.agent.subagents.SubAgentStore.currentRoster().map { it.name } else null,
        // Names the delegating model may pass as `subagent.model` (the user's agent model list); empty
        // leaves that parameter out. Only used with [subAgentRosterNames].
        subAgentModelHandles: List<String> = emptyList(),
        // True for a sub agent's own child session: delegation is one level deep, so no `subagent`
        // tool is offered there in any form.
        subAgentChild: Boolean = false,
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())

        add(askUserQuestionDefinition())
        add(getGoalDefinition())
        add(createGoalDefinition())
        add(updateGoalDefinition())
        add(goalDefinition("verify_outcome"))
        add(goalDefinition("review_restored_task"))
        add(todoWriteDefinition())
        add(JobTools.jobOutputDefinition())
        add(JobTools.jobListDefinition())
        add(JobTools.jobKillDefinition())
        add(JobTools.jobInputDefinition())
        add(catalogDefinition())

        if (presetToolset == com.openminis.app.remote.AgentPresetRegistry.Toolset.CORE) {
            return@buildList
        }

        // These are intentional fork product capabilities. They reuse the
        // existing runtime/permission/checkpoint seams rather than creating a
        // parallel Ubuntu execution path.
        addAll(com.openminis.app.tools.android.AndroidAgentTools.definitions())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        add(browserUseDefinition())
        if (!subAgentChild && subAgentRosterNames != null && com.openminis.app.agent.subagents.SubAgents.isEnabled()) {
            add(com.openminis.app.agent.subagents.SubAgentToolSchema.definition(
                subAgentRosterNames, modelHandles = subAgentModelHandles,
            ))
        }
        add(RalphTool.definition())
        if (memoryEnabled) {
            add(memoryWriteDefinition())
            add(memoryGetDefinition())
        }

        // ToolRegistry owns canonical fork runtime schemas. Legacy definitions
        // above retain their model-facing names; add only canonical handlers
        // not already represented, while respecting the tool gates above.
        val legacyCanonicals = mapNotNull {
            com.openminis.app.tools.runtime.ToolRegistry.canonicalName(it.name)
        }.toSet()
        // The capability gate above decided whether read_image is offered; the registry's copy of it
        // must not bring it back for a model that cannot see images and has no Vision Group to describe
        // them for it.
        val withheldImageTool = if (supportsImageInput || visionGroupConfigured) {
            emptySet()
        } else {
            setOfNotNull(
                ReadImageTool.NAME,
                com.openminis.app.tools.runtime.ToolRegistry.canonicalName(ReadImageTool.NAME),
            )
        }
        addAll(
            com.openminis.app.tools.runtime.ToolRegistry.definitions().filter {
                it.name !in legacyCanonicals && it.name !in withheldImageTool &&
                    (memoryEnabled || it.name !in setOf("memory_write", "memory_get")) &&
                    // The registry's own copy of the delegation tool is never offered directly: the
                    // roster definition above stands in for it, and a sub agent's
                    // child gets none.
                    it.name != com.openminis.app.agent.subagents.SubAgentToolSchema.HANDLER_NAME
            },
        )
    }

    fun catalogDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "find_tools",
        description = "Find the names, descriptions, and parameter schemas of the tools in this session. " +
            "Search by name or description. Set include_schema=true to get the exact arguments.\n\n" +
            "The system prompt lists the deferred tools. A deferred tool is not sent with each request. " +
            "A query that matches a deferred tool loads it, with a maximum of 6 tools per call and exact names first. " +
            "You can call a loaded tool from your next step. A listing with no query loads nothing.\n\n" +
            "A registered tool can still be unavailable on the device. " +
            "Check android_capabilities and the result of each operation.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Words to match against tool names and descriptions. A tool name loads that tool if it is deferred."),
            "include_schema" to AgentToolParam("boolean", "If true, include the complete parameter schema. The default is false."),
            "offset" to AgentToolParam("integer", "The result offset. The default is 0."),
            "limit" to AgentToolParam("integer", "The number of results per page, from 1 to 100. The default is 20."),
        ),
    )

    // Upstream shell contract adapted to the direct Ubuntu backend. Root only
    // launches the mount namespace/chroot; guest commands are privilege-dropped
    // to the Android app UID with Linux capabilities cleared. ToolRegistry
    // reuses the same schema under its canonical linux.shell name.
    fun shellExecuteDefinition(name: String = "shell_execute"): AgentToolDefinition = AgentToolDefinition(
        name = name,
        description = "Run a command in the Ubuntu 24.04 environment on the device. " +
            "The command runs as the Android app user, with no Linux capabilities. Root only starts the chroot. " +
            "The workspace of the session is /workspace, in app-private storage.\n\n" +
            "The command runs in a persistent Bash shell, one command at a time, with stdout and stderr merged. " +
            "The shell keeps cd and export for the next command. The default timeout is 15 minutes.\n\n" +
            "For a long task, such as a build, a server, or a download, set background=true. " +
            "The command then runs as a separate job and the call returns a job_id at once. " +
            "To run several commands in parallel, start each one as a job. " +
            "Use job_output, job_list, and job_kill to read, list, or stop jobs. " +
            "Use job_input to send text to the stdin of a job.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "command" to AgentToolParam("string", "The shell command to run. A multi-line command is allowed and needs no escaping. Keep it under 1000 characters. For a longer script, write it to a file with file_write, then run the file."),
            "timeout" to AgentToolParam("integer", "The timeout in seconds. The default is 900. Use a larger value for a long task, such as a package install."),
            "delay" to AgentToolParam("integer", "The seconds to wait before the command starts, from 0 to 600. The wait does not use the shell, so other tasks can use it. Use delay instead of a sleep command."),
            "background" to AgentToolParam("boolean", "If true, run the command as a separate background job and not in the persistent shell. The default is false. The job does not see the cd and export state of the shell. With background=true, timeout is the deadline of the job. Its default is 2 hours and its maximum is 24 hours."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay", "background"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        description = "Control a web browser with up to 3 tabs. " +
            "Do not use this tool for minis:// action URLs, such as open_terminal, views, and settings. " +
            "Those URLs are app deep links, so write them as Markdown links in your reply.\n\n" +
            "The browser opens web URLs and minis:// resource URLs. " +
            "Use a minis:// URL to preview a session file, for example minis://workspace/index.html. " +
            "The sub-resources of a page, such as JS, CSS, images, and fonts, load from minis:// absolute paths and from relative paths.\n\n" +
            "The get_text and get_readable actions return text in windows. " +
            "While more text remains, the result shows next_offset. Pass it back as offset to keep reading the same page.\n\n" +
            "The get_cookies action reads the cookies of the current site only, including HttpOnly cookies. " +
            "It returns a summary and the path of an offload env file. It does not return raw cookie values. " +
            "To use the cookies in a shell command, run: . /var/minis/offloads/env_cookies_xxx.sh && command. " +
            "The set_cookies action writes through the native cookie store, so HttpOnly cookies work.\n\n" +
            "Use wait_for_selector when the next call clicks or reads a known selector. " +
            "Use wait_for_dom_stable after a page change that loads data later.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "action" to AgentToolParam("string", "The browser action to run. To read a page, use get_readable or get_text. To see it, use screenshot. To find a selector, use find_elements. To read an infinite-scroll page, use scroll_and_collect.", enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "The URL to open (navigate) or the resource to download (fetch). A fetch uses the session of the page and returns metadata and a minis:// URL."),
            "selector" to AgentToolParam("string", "A CSS selector for the target element (click, type, get_text, scroll, hover, find_elements). For scroll, give a scrollable container, such as div.timeline. Without it, the app finds the best scrollable element. For find_elements, omit it to list the interactive elements. Each row has a unique selector for the next click."),
            "text" to AgentToolParam("string", "The text to type (type)."),
            "coordinate_x" to AgentToolParam("integer", "The X coordinate of a click. Use it instead of selector."),
            "coordinate_y" to AgentToolParam("integer", "The Y coordinate of a click. Use it instead of selector."),
            "direction" to AgentToolParam("string", "The scroll direction.", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "The scroll distance in pixels. The default is 500."),
            "script" to AgentToolParam("string", "The JavaScript to run (execute_js). Send one expression, for example document.title, and its value comes back. Or send a script body with await and a top-level return, for example: var r = await fetch(url); return await r.json(). A body without return gives undefined."),
            "user_agent" to AgentToolParam("string", "The user agent profile to switch to (set_user_agent).", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "The maximum tree depth for get_backbone. The default is 5."),
            "scroll_count" to AgentToolParam("integer", "The number of scroll steps for scroll_and_collect. The default is 10 and the maximum is 20. Each step scrolls by amount pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "A CSS selector for the content items in scroll_and_collect, for example article. Without it, the app finds repeated elements."),
            "tab_id" to AgentToolParam("integer", "The ID of the target tab. The default is the most recently used tab. Use list_tabs to see the tabs."),
            "keywords" to AgentToolParam("string", "The cookie-name filter for get_cookies: a space-separated string or an array of strings. The match ignores case. Without it, the result has all cookies of the current site."),
            "fuzzy" to AgentToolParam("boolean", "The match mode of keywords for get_cookies. If true (default), the cookie name must contain all keywords. If false, it must equal one of them."),
            "cookies" to AgentToolParam("string", "The cookies to write (set_cookies): a JSON array of objects. A JSON-encoded string of the array is also accepted. Each object has name and value (required). It can also have domain (default: the host of the current page), path (default: /), secure, http_only, and expires. Set expires to a Unix timestamp in seconds. Without expires, the cookie is a session cookie.\n\nThe names of common cookie exports are also accepted, such as httpOnly, expirationDate, and sameSite. You can paste cookies from browser extensions, Playwright, or Puppeteer."),
            "timeout" to AgentToolParam("integer", "The milliseconds for wait_for_dom_stable. The default is 10000. The range is 1000 to 60000. The action ends when the DOM stops changing."),
            "timeout_ms" to AgentToolParam("integer", "The milliseconds for wait_for_selector. The default is 5000. The range is 500 to 30000. The action ends when the selector matches a visible element."),
            "submit" to AgentToolParam("boolean", "For type: after the text is in the field, submit the form of the field. If the field has no form, press Enter. The default is false."),
            "read_image" to AgentToolParam("boolean", "If true (default), attach a captured screenshot to the result for you to see. If false, you get the metadata only and the image stays on disk for the user."),
            "viewport_width" to AgentToolParam("integer", "The viewport width in CSS pixels for set_viewport, for example 1920. Required with viewport_height unless reset=true. A screenshot is cropped to the phone viewport, so set a larger viewport before you capture a wide page."),
            "viewport_height" to AgentToolParam("integer", "The viewport height in CSS pixels for set_viewport, for example 1080. Required with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: if true, clear the viewport override of this session and use the global browser setting."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset"),
    )

    fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to the daily log of today (YYYY-MM-DD.md). " +
            "Memories persist across all sessions. The app adds a timestamp to each entry.\n\n" +
            "Do not save passwords, API keys, tokens, or secrets unless the user explicitly confirms after you warn the user. " +
            "Save user preferences, recurring patterns, key facts, project conventions, and reusable knowledge. " +
            "Keep each entry short and general. GLOBAL.md is read-only, because the user maintains it in Settings.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "content" to AgentToolParam("string", "The memory text. Use short Markdown with a short heading (## Topic) and the context of what you did or learned."),
            "kind" to AgentToolParam("string", "The type of the background record. The default is task_fact. Neither type is a current instruction.", enumValues = listOf("task_fact", "user_preference")),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content", "kind"),
    )

    fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Search the persistent memory by keyword, with fuzzy matching across the memory files. " +
            "The result has the matching lines with their surrounding lines. " +
            "Use it to recall earlier knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "scope" to AgentToolParam("string", "Use daily to search the daily logs only. Use all to search the daily logs and GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords, for example: python preference. A match needs all keywords in a line or in its surrounding lines. Leave it empty to return the full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )

    private fun askUserQuestionDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = AskUserQuestionTool.NAME,
        description = "Pause and ask the user a short question. " +
            "Use it when you need a confirmation, a choice, or missing information to continue. " +
            "The answer comes back as a structured tool result.\n\n" +
            "Do not use it for a rhetorical question or for a decision that you can make yourself. " +
            "Ask one question at a time, and only if you cannot continue with a reasonable assumption.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "question" to AgentToolParam("string", "The question to ask, in the language of the user."),
            "options" to AgentToolParam(
                type = "array",
                description = "The optional answer choices. Omit it for a free-form question.",
                items = AgentToolParam(
                    type = "object",
                    description = "One answer choice.",
                    properties = mapOf(
                        "label" to AgentToolParam("string", "The label that the user sees."),
                        "value" to AgentToolParam("string", "The stable value that comes back to you."),
                        "recommended" to AgentToolParam("boolean", "An optional hint for the user."),
                    ),
                    requiredProperties = listOf("label", "value"),
                ),
            ),
            "multiple" to AgentToolParam("boolean", "If true, the user can select more than one choice. The default is false."),
            "allowCustom" to AgentToolParam("boolean", "If true, the user can type a custom answer. The default is true."),
            "timeoutMinutes" to AgentToolParam("integer", "How long to wait for the user, from 1 to 30 minutes. The default is 10."),
        ),
        required = listOf("tool_title", "question"),
        propertyOrdering = listOf("tool_title", "question", "options", "multiple", "allowCustom", "timeoutMinutes"),
    )

    /**
     * Legacy model names project the same schema as the canonical handler. Each action has its own
     * description ([ToolText.GOAL_ACTIONS]); the parameter descriptions are shared with the handler.
     */
    private fun goalDefinition(action: String): AgentToolDefinition {
        val schema = com.openminis.app.tools.runtime.AgentGoalHandler().definition
        val params = schema.parameters.filterKeys { key ->
            key != "action" && when (action) {
                "get_goal" -> key == "tool_title"
                "verify_outcome" -> key in setOf("tool_title", "task_id", "revision", "stop_generation", "outcome", "verification")
                "review_restored_task" -> key in setOf("tool_title", "task_id", "revision", "stop_generation")
                else -> key !in setOf("outcome", "verification")
            }
        }
        return schema.copy(
            name = action,
            description = checkNotNull(ToolText.GOAL_ACTIONS[action]) { "no goal action $action" },
            parameters = params,
            required = when (action) {
                "create_goal" -> listOf("tool_title", "goal")
                "verify_outcome" -> listOf("tool_title", "task_id", "revision", "stop_generation", "outcome")
                "review_restored_task" -> listOf("tool_title", "task_id", "revision", "stop_generation")
                else -> listOf("tool_title")
            },
            propertyOrdering = params.keys.toList(),
            timeoutMs = if (action in setOf("verify_outcome", "review_restored_task")) 605_000L else 10_000L,
        )
    }

    private fun getGoalDefinition() = goalDefinition("get_goal")
    private fun createGoalDefinition() = goalDefinition("create_goal")
    private fun updateGoalDefinition() = goalDefinition("update_goal")

    private fun todoWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "todo_write",
        description = "Replace the todo list of the session in one atomic call. " +
            "Send the complete list every time, because there are no partial updates. " +
            "Use it for multi-step work, so that the user can see the progress.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "todos" to AgentToolParam(
                type = "array",
                description = "The complete todo list.",
                items = AgentToolParam(
                    type = "object",
                    description = "One todo item.",
                    properties = mapOf(
                        "title" to AgentToolParam("string", "The text of the todo."),
                        "status" to AgentToolParam("string", "One of pending, in_progress, completed, or skipped."),
                        "id" to AgentToolParam("string", "A stable item ID. Keep the existing IDs when you update the list."),
                    ),
                    requiredProperties = listOf("title"),
                ),
            ),
        ),
        required = listOf("tool_title", "todos"),
        propertyOrdering = listOf("tool_title", "todos"),
        timeoutMs = 10_000L,
    )
}
