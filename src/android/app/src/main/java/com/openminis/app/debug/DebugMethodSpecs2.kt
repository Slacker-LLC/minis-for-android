package com.openminis.app.debug

import com.openminis.app.debug.DebugMethodRegistry.MethodSpec
import com.openminis.app.debug.DebugMethodRegistry.ParamSpec
import com.openminis.app.debug.DebugMethodRegistry.ex
import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Part 2 of the method catalogue [DebugMethodRegistry.methods] is built from. */
internal val baseMethodsPart2: List<MethodSpec> = listOf(
        MethodSpec(
            name = "chat.models.list",
            description = "Return the candidate models and fixed slot bindings visible to the chat picker.",
            params = listOf(
                ParamSpec("includeHidden", "bool", required = false, default = false, description = "Include user-hidden entries."),
                ParamSpec("includeDisabled", "bool", required = false, default = false, description = "Include entries from disabled provider instances."),
            ),
            returns = "{fallbackTrigger, entryCount, slots:{main, light, vision, voiceInput, voiceOutput, image}, entries:[...]}",
            example = JSONObject(),
        ),

        // --- Provider mutate ---
        MethodSpec(
            name = "provider.instances.create",
            description = "Create a new provider instance. apiKey/oauthToken are write-only.",
            params = listOf(
                ParamSpec("providerType", "string", required = true, description = "anthropic / gemini / openAI / openRouter"),
                ParamSpec("label", "string", required = true, description = "User-visible name."),
                ParamSpec("credentialType", "string", required = false, default = "apiKey", description = "apiKey or oauth"),
                ParamSpec("apiKey", "string", required = false, description = "Stored encrypted; never returned by any read."),
                ParamSpec("oauthToken", "string", required = false, description = "Manual OAuth token seed."),
                ParamSpec("customBaseURL", "string", required = false, description = "openAI only."),
                ParamSpec("appendV1Suffix", "bool", required = false, default = true, description = "Whether to append /v1 to customBaseURL."),
                ParamSpec("useResponsesAPI", "bool", required = false, default = false, description = "openAI only — route via /v1/responses."),
                ParamSpec("customUserAgent", "string", required = false, description = "Override outbound User-Agent. apiKey providers only."),
                ParamSpec("isEnabled", "bool", required = false, default = true, description = "Create in enabled state."),
                ParamSpec("seedBuiltInModels", "bool", required = false, default = true, description = "If false, create instance with no model entries."),
            ),
            returns = "{instance:{...}}",
            example = ex("providerType" to "openAI", "label" to "Custom API", "apiKey" to "sk-…", "customBaseURL" to "https://api.example.com", "seedBuiltInModels" to false),
        ),
        MethodSpec(
            name = "provider.instances.update",
            description = "Patch fields on an existing instance. Omitted fields are untouched. Pass apiKey=\"\" to clear.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("label", "string", required = false, description = "New display name."),
                ParamSpec("apiKey", "string", required = false, description = "Replace stored API key. \"\" clears."),
                ParamSpec("oauthToken", "string", required = false, description = "Replace stored OAuth token."),
                ParamSpec("customBaseURL", "string", required = false, description = "null reverts to default."),
                ParamSpec("appendV1Suffix", "bool", required = false, description = "Update suffix behavior."),
                ParamSpec("useResponsesAPI", "bool", required = false, description = "openAI only."),
                ParamSpec("customUserAgent", "string", required = false, description = "Override outbound User-Agent (null/\"\" reverts to default). apiKey providers only."),
                ParamSpec("isEnabled", "bool", required = false, description = "Enable / disable."),
                ParamSpec("imageEndpointMode", "string", required = false, description = "auto | images_generations | chat_completions. Forcing non-auto clears the cached probe result (GH#68)."),
            ),
            returns = "{instance:{...}}",
            example = ex("instanceId" to "pi_xyz", "isEnabled" to false),
        ),
        MethodSpec(
            name = "provider.instances.delete",
            description = "Remove a provider instance, all its model entries, and its stored credential.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{instanceId, deletedModelEntries, deleted}",
            example = ex("instanceId" to "pi_xyz", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.instances.test",
            description = "Probe an instance with the stored credential to verify reachability.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("timeoutMs", "int", required = false, default = 10000, description = "Clamped to [1000, 30000]."),
            ),
            returns = "{ok, httpStatus, latencyMs, reachableModelCount?, error?}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.models.add",
            description = "Add a custom model entry to an instance.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("modelId", "string", required = true, description = "API model id."),
                ParamSpec("displayName", "string", required = false, description = "Defaults to a prettified modelId."),
                ParamSpec("contextWindow", "int", required = false, description = "Tokens."),
                ParamSpec("maxOutputTokens", "int", required = false, description = "Tokens."),
                ParamSpec("supportsReasoning", "bool", required = false, description = "Tri-state — pass null to leave unknown."),
                ParamSpec("supportsImageInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsAudioInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsVideoInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsPDFInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsImageOutput", "bool", required = false, description = "Modality flag."),
            ),
            returns = "Entry shape (same as provider.models.list[].entries[i]), including defaultThinkingLevel and contextLimitTokens when set.",
            example = ex("instanceId" to "pi_xyz", "modelId" to "vendor/model-pro"),
        ),
        MethodSpec(
            name = "provider.models.update",
            description = "Patch a model entry. Omitted fields are untouched; pass null to clear an override; modelId only mutable on custom entries.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("displayName", "string", required = false, description = "null clears the override."),
                ParamSpec("maxOutputTokens", "int", required = false, description = "null clears the override."),
                ParamSpec("contextWindow", "int", required = false, description = "null clears the override."),
                ParamSpec("isHidden", "bool", required = false, description = "Toggle picker visibility."),
                ParamSpec("modelId", "string", required = false, description = "Custom entries only."),
            ),
            returns = "Updated entry.",
            example = ex("entryId" to "entry_123", "isHidden" to true),
        ),
        MethodSpec(
            name = "provider.models.delete",
            description = "Remove a custom model entry. Built-in entries can't be deleted — hide them via update isHidden=true.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{entryId, deleted}",
            example = ex("entryId" to "entry_123", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.models.refresh",
            description = "Re-query the provider's /v1/models for an instance and update the entry list.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
            ),
            returns = "{instanceId, added, disappeared, total, durationMs}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.models.setAgentLoop",
            description = "Toggle whether a model entry is exposed to the in-shell minis-model-use agent.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("inLoop", "bool", required = true, description = "true to add, false to remove."),
            ),
            returns = "{entryId, inLoop}",
            example = ex("entryId" to "entry_123", "inLoop" to true),
        ),
        MethodSpec(
            name = "provider.slots.set",
            description = "Replace a fixed slot's ordered entry IDs and/or set the Main fallback trigger. Slot entry capabilities are validated; slots cannot be added or removed.",
            params = listOf(
                ParamSpec("slot", "string", required = false, description = "main / light / vision / voiceInput / voiceOutput / image; required together with entryIds."),
                ParamSpec("entryIds", "[string]", required = false, description = "Ordered entry IDs; array order defines fallback order."),
                ParamSpec("fallbackTrigger", "string", required = false, description = "default (429/5xx) or always; may be passed with a slot update."),
            ),
            returns = "{slot?, entryIds?, fallbackTrigger?}",
            example = ex("slot" to "main", "entryIds" to JSONArray().apply { put("entry_a") }),
        ),
        MethodSpec(
            name = "provider.models.setDefaults",
            description = "Set or clear a model entry's default thinking level and context cap; pass null to inherit/unlimit.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target model-entry UUID."),
                ParamSpec("defaultThinkingLevel", "string|null", required = false, description = "OFF / LOW / MEDIUM / HIGH / XHIGH / MAX / ULTRA; null clears."),
                ParamSpec("contextLimitTokens", "int|null", required = false, description = "1..2147483647 token cap; null or 0 clears."),
            ),
            returns = "{entryId, defaultThinkingLevel, contextLimitTokens}",
            example = ex("entryId" to "entry_123", "defaultThinkingLevel" to "MEDIUM", "contextLimitTokens" to 128000),
        ),

        // --- Chat mutate ---
        MethodSpec(
            name = "chat.prompt",
            description = "Send a prompt to a new or existing chat session. wait=true blocks until completion.",
            params = listOf(
                ParamSpec("prompt", "string", required = true, description = "User message text."),
                ParamSpec("sessionId", "string", required = false, description = "Existing session id; omit to create a new one (source=debug)."),
                ParamSpec("attachments", "[object]", required = false, description = "Array of {name, data, mime?}; data is base64."),
                ParamSpec("modelEntryId", "string", required = false, description = "Pin to a specific model entry."),
                ParamSpec("thinkingLevel", "string", required = false, description = "off / low / medium / high / xhigh / max / ultra — applies before send; leaves the VM setting alone when omitted."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, isNewSession, modelName, status, prompt, responseText, userMessageId, timedOut?}",
            example = ex("prompt" to "Hello", "wait" to true),
        ),
        MethodSpec(
            name = "chat.retry",
            description = "Retry from a specific user message in a session.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("messageId", "string", required = false, description = "User message id; omit to retry from the most recent user message."),
                ParamSpec("modelEntryId", "string", required = false, description = "Pin retry to a specific entry."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, status, retriedMessageId, deletedMessageCount, modelName, responseText, timedOut?}",
            example = ex("sessionId" to "6D0F…", "wait" to true),
        ),
        MethodSpec(
            name = "chat.rerunFromToolBlock",
            description = "Block-boundary re-run: cut at a specific tool_use block (keep earlier blocks in its turn, drop it + everything after) and regenerate. In-app this is the tool-bubble long-press 'Re-run From Here'.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("assistantMessageId", "string", required = true, description = "UI assistant bubble id owning the tool block."),
                ParamSpec("blockId", "string", required = true, description = "Tool block id (equals its tool_use id)."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, status, deletedMessageCount, responseText, timedOut?}",
            example = ex("sessionId" to "6D0F…", "assistantMessageId" to "assistant_…", "blockId" to "call_…", "wait" to true),
        ),
        MethodSpec(
            name = "chat.session.status",
            description = "Poll a session's live status (after async chat.prompt / chat.retry).",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, title, modelName, isRunning, messageCount, lastMessageRole, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.cancel",
            description = "Cancel an in-progress agent run. No-op if the session isn't running.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, wasRunning, cancelled}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.selectModel",
            description = "Switch the live session's bound model mid-session (same as tapping a model in the in-app picker: ChatViewModel.selectEntry). Unlike chat.prompt's modelEntryId (which only rewrites the DB binding and is ignored by the loaded VM), this re-resolves the live model/provider. The thinking level is preserved across the switch; the response echoes it back.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("modelEntryId", "string", required = true, description = "Model entry id to bind (from chat.models.list)."),
            ),
            returns = "{sessionId, modelEntryId, modelName, thinkingLevel}",
            example = ex("sessionId" to "6D0F…", "modelEntryId" to "…/gpt-5.6-sol"),
        ),
        MethodSpec(
            name = "chat.session.selectThinkingLevel",
            description = "Change the current session's reasoning effort without switching models.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("thinkingLevel", "string", required = true, description = "off / low / medium / high / xhigh / max / ultra."),
            ),
            returns = "{sessionId,thinkingLevel,applied}",
            example = ex("sessionId" to "6D0F…", "thinkingLevel" to "high"),
        ),
        MethodSpec(
            name = "chat.compact.markers.list",
            description = "List all compact markers on a session, oldest → newest. Includes v2 fields (anchorMessageId, version, summaryLength, summaryPreview).",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("includeFullSummary", "bool", required = false, default = false, description = "Include the full summary string in each marker (default: 120-char preview only)."),
            ),
            returns = "{sessionId, count, markers:[{id, version, anchorMessageId, firstKeptMessageId, summaryLength, summaryPreview, compactedCount, createdAt}]}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.compact.before",
            description = "Trigger compaction on a session. includesBoundary=true → compactAll semantics (the in-app /compact slash command, anchor = last active message). includesBoundary=false → 'compact up through messageId' (long-press equivalent, anchor = caller-supplied DB message id). In v2 both modes write the same shape of marker — includesBoundary is preserved for ABI compat with iOS.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("messageId", "string", required = false, description = "DB message id to use as the anchor. Required when includesBoundary=false."),
                ParamSpec("includesBoundary", "bool", required = false, default = false, description = "true → compactAll mode (auto-anchor on last active message)."),
                ParamSpec("waitTimeout", "int", required = false, default = 120, description = "Seconds to wait for compaction LLM call. Clamped [1,1800]."),
            ),
            returns = "{sessionId, messageId, includesBoundary, beforeMarkerCount, afterMarkerCount, wrote, status, timedOut?, error?, latestMarker:{id, version, anchorMessageId, summaryLength, compactedCount, createdAt}}",
            example = ex("sessionId" to "6D0F…", "includesBoundary" to true),
        ),
        MethodSpec(
            name = "chat.compact.revert",
            description = "Revert the most recent compact on a session. Drops the latest marker; cachedLatestMarker falls back to the previous one (or null), then the UI rebuilds.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, beforeMarkerCount, afterMarkerCount, removedMarkerId, newLatestMarkerId}",
            example = ex("sessionId" to "6D0F…"),
        ),

        // ── Web question cards + cross-session search (chat.*) ──────────────
        MethodSpec(
            name = "chat.question.pending",
            description = "List pending ask_user_question cards. The agent turn suspends until answered via chat.question.answer.",
            params = listOf(
                ParamSpec("sessionId", "string", required = false, description = "Restrict to one session; omit for all."),
            ),
            returns = "{questions:[{id, sessionId, prompt, options:[{value,label,recommended?}], multiple, allowCustom, createdAt}]}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.question.answer",
            description = "Answer a pending ask_user_question card and resume the paused agent turn.",
            params = listOf(
                ParamSpec("questionId", "string", required = true, description = "Question id from chat.question.pending."),
                ParamSpec("selected", "[string]", required = false, description = "Selected option values."),
                ParamSpec("custom", "string", required = false, description = "Free-form answer."),
                ParamSpec("skipped", "bool", required = false, default = false, description = "Skip the question."),
            ),
            returns = "{ok:true, answered:true}",
            example = ex("questionId" to "q1", "selected" to JSONArray().apply { put("yes") }),
        ),
        MethodSpec(
            name = "chat.search",
            description = "Cross-session full-text search over message content. Space-separated terms are ANDed and " +
                "treated literally; results are grouped per session.",
            params = listOf(
                ParamSpec("query", "string", required = true, description = "Search text (literal phrase / AND terms)."),
                ParamSpec("limit", "int", required = false, default = 20, description = "Max result sessions (1..50)."),
                ParamSpec("sessionId", "string", required = false, description = "Restrict to one session."),
            ),
            returns = "{query, count, total, results:[{sessionId, title, matchedCount, snippet, hits:[{messageId, role, createdAt, content}]}]}",
            example = ex("query" to "Minis 设计", "limit" to 20),
        ),
        MethodSpec(
            name = "chat.feedback.put",
            description = "Set per-message feedback (up/down) with an optional note. Replaces any previous feedback for the message.",
            params = listOf(
                ParamSpec("messageId", "string", required = true, description = "Message id."),
                ParamSpec("kind", "string", required = true, description = "'up' or 'down'."),
                ParamSpec("note", "string", required = false, description = "Optional note."),
            ),
            returns = "{ok:true, kind, note, at}",
            example = ex("messageId" to "msg_1", "kind" to "down", "note" to "答案太长了"),
        ),
        MethodSpec(
            name = "chat.feedback.delete",
            description = "Remove feedback for a message.",
            params = listOf(
                ParamSpec("messageId", "string", required = true, description = "Message id."),
            ),
            returns = "{ok:true}",
            example = ex("messageId" to "msg_1"),
        ),
        MethodSpec(
            name = "chat.feedback.listForMessages",
            description = "List feedback for the given message ids (all when omitted).",
            params = listOf(
                ParamSpec("messageIds", "[string]", required = false, description = "Message ids; omit for all."),
            ),
            returns = "{feedback:[{messageId, kind, note, at}]}",
            example = ex("messageIds" to JSONArray().apply { put("msg_1") }),
        ),
        MethodSpec(
            name = "chat.session.delete",
            description = "Permanently delete a session and its messages. Cancels any in-flight run first.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{sessionId, deleted}",
            example = ex("sessionId" to "6D0F…", "confirm" to true),
        ),

        // ── Skills (web remote: skills.*) ─────────────────────────────────────
        MethodSpec(
            name = "skills.list",
            description = "List installed skills with their metadata.",
            params = emptyList(),
            returns = "{skills:[{id, name, description, version, importSource, isEnabled, installedAt, updatedAt, useCount}]}",
            example = ex(),
        ),
        MethodSpec(
            name = "skills.get",
            description = "Return one installed skill, including its full body source.",
            params = listOf(
                ParamSpec("skillId", "string", required = true, description = "Skill identifier."),
            ),
            returns = "Same shape as a skills.list item, plus {body}.",
            example = ex("skillId" to "pet-chat"),
        ),
        MethodSpec(
            name = "skills.create",
            description = "Create a local skill in the same repository used by native Settings and Agent turns.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Human-readable skill name; its slug becomes the id."),
                ParamSpec("description", "string", required = false, description = "When and why the skill is useful."),
                ParamSpec("body", "string", required = false, description = "SKILL.md instruction body."),
                ParamSpec("version", "string", required = false, default = "1.0.0", description = "Initial version."),
            ),
            returns = "{skill:{id,name,description,version,body,…}}",
            example = ex("name" to "Project review", "description" to "Review a code project", "body" to "Inspect the repository…"),
        ),
        MethodSpec(
            name = "skills.importUrl",
            description = "Import or update a SKILL.md from a public HTTPS/GitHub URL.",
            params = listOf(
                ParamSpec("url", "string", required = true, description = "Public HTTPS URL to SKILL.md or its GitHub directory."),
            ),
            returns = "{skill:{id,name,description,version,sourceURL,body,…}}",
            example = ex("url" to "https://github.com/example/skills/tree/main/project-review"),
        ),
        MethodSpec(
            name = "skills.update",
            description = "Edit an installed skill's name, description and/or SKILL.md body.",
            params = listOf(
                ParamSpec("skillId", "string", required = true, description = "Skill identifier."),
                ParamSpec("name", "string", required = false, description = "Replacement display name."),
                ParamSpec("description", "string", required = false, description = "Replacement description."),
                ParamSpec("body", "string", required = false, description = "Replacement SKILL.md body."),
            ),
            returns = "{skill:{id,name,description,version,body,…}}",
            example = ex("skillId" to "project-review", "body" to "Updated instructions…"),
        ),
        MethodSpec(
            name = "skills.toggle",
            description = "Enable or disable an installed skill.",
            params = listOf(
                ParamSpec("skillId", "string", required = true, description = "Skill identifier."),
                ParamSpec("enabled", "bool", required = true, description = "New enabled state."),
            ),
            returns = "{ok:true}",
            example = ex("skillId" to "pet-chat", "enabled" to true),
        ),
        MethodSpec(
            name = "skills.delete",
            description = "Permanently remove an installed skill.",
            params = listOf(
                ParamSpec("skillId", "string", required = true, description = "Skill identifier."),
            ),
            returns = "{ok:true}",
            example = ex("skillId" to "pet-chat"),
        ),

        // ── Memory (web remote: memory.*, soul.*) ─────────────────────────────
        MethodSpec(
            name = "memory.files.list",
            description = "List memory Markdown files the Agent reads/writes between runs.",
            params = emptyList(),
            returns = "{files:[{name, isGlobal, modifiedDate, fileSize, preview}]}",
            example = ex(),
        ),
        MethodSpec(
            name = "memory.files.read",
            description = "Read one memory file. GLOBAL.md is the global memory file. Rejects path traversal.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "File name (no '/' or '..')."),
            ),
            returns = "{name, content, isGlobal}",
            example = ex("name" to "GLOBAL.md"),
        ),
        MethodSpec(
            name = "memory.files.write",
            description = "Write (create or replace) one memory file. Rejects path traversal.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "File name (no '/' or '..')."),
                ParamSpec("content", "string", required = true, description = "Full file content."),
            ),
            returns = "{ok:true}",
            example = ex("name" to "GLOBAL.md", "content" to "## 长期记忆\n…"),
        ),
)
