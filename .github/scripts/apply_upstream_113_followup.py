from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def upstream(rel: str) -> str:
    return subprocess.check_output(
        ["git", "show", f"upstream/main:{rel}"],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
    )


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 anchor, found {count}")
    return text.replace(old, new, 1)


def slice_between(text: str, start: str, end: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"{label}: start anchor missing")
    j = text.find(end, i + len(start))
    if j < 0:
        raise SystemExit(f"{label}: end anchor missing")
    return text[i:j]


def replace_between_from_upstream(
    current: str,
    source: str,
    start: str,
    end: str,
    label: str,
) -> str:
    old = slice_between(current, start, end, label + " current")
    new = slice_between(source, start, end, label + " upstream")
    return replace_once(current, old, new, label)


# ---------------------------------------------------------------------------
# 1) Model modality normalization and native-vision consistency.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt"
s = read(rel)
s = replace_once(
    s,
    'fun String.normalizeModalityName(): String =\n    removeSuffix("_input").removeSuffix("_output").lowercase()\n',
    'fun String.normalizeModalityName(): String =\n    lowercase().removeSuffix("_input").removeSuffix("_output")\n',
    "modality lowercase-before-suffix",
)
s = replace_once(
    s,
    '        val inputs = inputModalities?.map { it.lowercase() } ?: emptyList()\n',
    '        val inputs = inputModalities.normalizeModalities() ?: emptyList()\n',
    "capability prompt normalized modalities",
)
write(rel, s)

rel = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
s = read(rel)
if "import com.openminis.app.data.model.hasImageInput\n" not in s:
    s = replace_once(
        s,
        "import com.openminis.app.data.model.LLMUsage\n",
        "import com.openminis.app.data.model.LLMUsage\nimport com.openminis.app.data.model.hasImageInput\n",
        "ChatViewModel hasImageInput import",
    )
raw_vision = '''currentModel?.let {
                it.inputModalities?.map { m -> m.lowercase() }?.contains("image") == true
            } == true'''
if s.count(raw_vision) != 2:
    raise SystemExit(f"ChatViewModel native-vision anchors: expected 2, found {s.count(raw_vision)}")
s = s.replace(raw_vision, "currentModel?.hasImageInput == true")
write(rel, s)

# ---------------------------------------------------------------------------
# 2) Provider refresh: keyless self-hosted compatibility + live xAI catalog.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt"
s = read(rel)
s = replace_once(
    s,
    "        var apiKey = loadApiKey(instance.id)\n",
    "        var apiKey = usableApiKey(instance)\n",
    "refreshModels usableApiKey",
)
old_xai = '''                    // xAI: the OAuth model list is fixed (no /v1/models gating
                    // call needed — XAIModelsApi exposes the spec-mandated set).
                    // For API-key users we still call the same static list; if
                    // xAI later exposes a dynamic /v1/models endpoint this is
                    // the place to swap in OpenAI-compatible fetch.
                    ProviderType.xAI -> com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth()
'''
new_xai = '''                    // xAI exposes the OpenAI-compatible /v1/models endpoint.
                    // Keep the built-in catalog only as seed/fallback so models
                    // released after this APK can appear on Refresh. OAuth xAI
                    // instances may have no custom base, so default explicitly to
                    // api.x.ai rather than letting the OpenAI helper pick OpenAI.
                    ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "https://api.x.ai/v1",
                        customUserAgent = instance.customUserAgent,
                    ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
'''
s = replace_once(s, old_xai, new_xai, "xAI dynamic catalog")
write(rel, s)

# ---------------------------------------------------------------------------
# 3) xAI Priority Processing (Fast Mode) at provider boundary.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/data/FastModePrefs.kt"
s = read(rel)
if "setCachedEnabledForTest" not in s:
    s = replace_once(
        s,
        '''    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}''',
        '''    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        cachedEnabled = enabled
    }
}''',
        "FastModePrefs test setter",
    )
write(rel, s)

rel = "src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt"
s = read(rel)
u = upstream(rel)
s = replace_between_from_upstream(
    s,
    u,
    "            ProviderType.xAI -> {\n",
    "            ProviderType.kimiCode -> {\n",
    "ProviderFactory xAI capability block",
)
write(rel, s)

# ---------------------------------------------------------------------------
# 4) OpenAI-compatible image/tool-result parity + xAI priority + incomplete.
#    Pull only narrowly bounded upstream sections so Fork-specific GPT-6/runtime
#    changes elsewhere in this large file remain untouched.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt"
s = read(rel)
u = upstream(rel)

if "import com.openminis.app.data.model.hasImageInput\n" not in s:
    s = replace_once(
        s,
        "import com.openminis.app.data.model.ThinkingLevel\n",
        "import com.openminis.app.data.model.ThinkingLevel\nimport com.openminis.app.data.model.hasImageInput\n",
        "OpenAIProvider hasImageInput import",
    )

# Priority-processing capability and per-request resolver.
s = replace_between_from_upstream(
    s,
    u,
    "    var thinkingRuleInstanceId: String? = null\n",
    "    /** API Key constructor",
    "OpenAIProvider xAI priority fields",
)

# Both Chat Completions and Responses must use normalized modality capability.
old_supports = '        val supportsImages = "image" in (model.inputModalities ?: emptyList())\n'
if s.count(old_supports) != 2:
    raise SystemExit(f"OpenAI supportsImages anchors: expected 2, found {s.count(old_supports)}")
s = s.replace(old_supports, "        val supportsImages = model.hasImageInput\n")

# Tool-result pixels: transplant only the per-tool-result serialization blocks.
s = replace_between_from_upstream(
    s,
    u,
    "                        for (tr in toolResults) {\n",
    "                        val hasImages = imageParts.isNotEmpty()\n",
    "Chat Completions ToolResult image serialization",
)
s = replace_between_from_upstream(
    s,
    u,
    "                        for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {\n",
    "                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()\n",
    "Responses ToolResult image serialization",
)

# xAI service tier is emitted by both request-body builders. There are exactly
# two body stream assignments in these builders on the current Fork.
stream_anchor = '        body.put("stream", stream)\n'
stream_count = s.count(stream_anchor)
if stream_count != 2:
    raise SystemExit(f"OpenAI stream anchors: expected 2, found {stream_count}")
s = s.replace(
    stream_anchor,
    stream_anchor + '        resolvedServiceTier()?.let { body.put("service_tier", it) }\n',
)

# The existing GPT/Codex Fast Mode write must not overwrite an xAI-specific tier.
s = replace_once(
    s,
    '''        if (com.openminis.app.data.FastModePrefs.isEnabled() &&
            model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }
''',
    '''        if (!body.has("service_tier") &&
            com.openminis.app.data.FastModePrefs.isEnabled() &&
            model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }
''',
    "Codex service-tier no-clobber guard",
)

# Preserve partial Responses output on response.incomplete. Copy the exact
# upstream state tracking + terminal handler, bounded by stable neighboring code.
s = replace_between_from_upstream(
    s,
    u,
    "        var contentLen = 0\n",
    "        var reasoningLen = 0\n",
    "Responses incomplete nonblank state",
)
s = replace_between_from_upstream(
    s,
    u,
    '                        if (type == "response.output_text.delta")',
    '                        if (type.startsWith("response.reasoning_"))',
    "Responses output-text nonblank tracking",
)
s = replace_between_from_upstream(
    s,
    u,
    '                        type == "response.incomplete" -> {\n',
    '                        type == "response.completed" -> {\n',
    "Responses incomplete terminal behavior",
)

write(rel, s)

# ---------------------------------------------------------------------------
# 5) Copy upstream regression tests verbatim. They exercise request-body and
#    pure-model behavior and do not depend on the Fork runtime architecture.
# ---------------------------------------------------------------------------
TESTS = [
    "src/android/app/src/test/java/com/openminis/app/data/NativeVisionModalityTest.kt",
    "src/android/app/src/test/java/com/openminis/app/provider/ToolResultImageSerializationTest.kt",
    "src/android/app/src/test/java/com/openminis/app/provider/ResponsesIncompletePartialTest.kt",
    "src/android/app/src/test/java/com/openminis/app/provider/XAIDynamicCatalogTest.kt",
    "src/android/app/src/test/java/com/openminis/app/provider/XAIPriorityProcessingTest.kt",
]
for test_path in TESTS:
    write(test_path, upstream(test_path))

print("Applied upstream 1.13 follow-up parity patch")
