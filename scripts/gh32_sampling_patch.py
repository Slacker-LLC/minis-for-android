from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    p.write_text(text.replace(old, new, 1))


# Anthropic: native top_p + top_k, omitted while extended thinking is active.
p = "src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt"
anchor = """    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)
"""
replace_once(
    p,
    anchor,
    anchor
    + """
    override fun streamMessageSamplingClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        topP: Double?,
        topK: Int?,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        topP = topP, topK = topK,
    ).failOnSilentEmptyCompletion(name)
""",
    "anthropic sampling override",
)
replace_once(
    p,
    """        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
""",
    """        thinkingLevel: ThinkingLevel,
        topP: Double? = null,
        topK: Int? = null,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, topP = topP, topK = topK, thinkingLevel = thinkingLevel)
""",
    "anthropic raw stream params",
)
replace_once(
    p,
    """        tools: List<AgentToolDefinition> = emptyList(),
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
""",
    """        tools: List<AgentToolDefinition> = emptyList(),
        topP: Double? = null,
        topK: Int? = null,
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
""",
    "anthropic body params",
)
replace_once(
    p,
    """        if (temperature != null && !thinkingLevel.isEnabled && !modelRejectsTemperature(model.id)) {
            body.put("temperature", temperature)
        }
""",
    """        if (temperature != null && !thinkingLevel.isEnabled && !modelRejectsTemperature(model.id)) {
            body.put("temperature", temperature)
        }
        // GH#32: keep extended-thinking requests conservative; sampling
        // overrides are emitted only when thinking is off.
        if (!thinkingLevel.isEnabled) {
            topP?.let { body.put("top_p", it) }
            topK?.let { body.put("top_k", it) }
        }
""",
    "anthropic wire fields",
)

# Gemini: generationConfig natively supports topP + topK.
p = "src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt"
anchor = """    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)
"""
replace_once(
    p,
    anchor,
    anchor
    + """
    override fun streamMessageSamplingClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        topP: Double?,
        topK: Int?,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        topP = topP, topK = topK,
    ).failOnSilentEmptyCompletion(name)
""",
    "gemini sampling override",
)
replace_once(
    p,
    """        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
""",
    """        thinkingLevel: ThinkingLevel,
        topP: Double? = null,
        topK: Int? = null,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel, topP = topP, topK = topK)
""",
    "gemini raw stream params",
)
replace_once(
    p,
    """        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
""",
    """        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        topP: Double? = null,
        topK: Int? = null,
    ): JSONObject {
""",
    "gemini body params",
)
replace_once(
    p,
    """        if (temperature != null) {
            config.put("temperature", temperature)
        }
""",
    """        if (temperature != null) {
            config.put("temperature", temperature)
        }
        topP?.let { config.put("topP", it) }
        topK?.let { config.put("topK", it) }
""",
    "gemini wire fields",
)

# OpenAI-compatible Chat Completions: top_p is portable; top_k is not.
p = "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt"
anchor = """    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)
"""
replace_once(
    p,
    anchor,
    anchor
    + """
    override fun streamMessageSamplingClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        topP: Double?,
        topK: Int?,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        topP = topP,
    ).failOnSilentEmptyCompletion(name)
""",
    "openai sampling override",
)
replace_once(
    p,
    """        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
""",
    """        thinkingLevel: ThinkingLevel,
        topP: Double? = null,
    ): Flow<LLMStreamChunk> = callbackFlow {
""",
    "openai raw stream params",
)
replace_once(
    p,
    """            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
""",
    """            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, topP = topP, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
""",
    "openai chat body call",
)
replace_once(
    p,
    """        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
""",
    """        stream: Boolean,
        temperature: Double?,
        topP: Double? = null,
        imageParts: List<LLMMessage.ImagePart>,
""",
    "openai body params",
)
replace_once(
    p,
    """        if (temperature != null) {
            body.put("temperature", temperature)
        }
""",
    """        if (temperature != null) {
            body.put("temperature", temperature)
        }
        topP?.let { body.put("top_p", it) }
""",
    "openai top-p wire field",
)

# Central session agent loop.
p = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
replace_once(
    p,
    """                currentProvider.streamMessage(
                    messages = applyRequestImageBudget(effectiveAgentHistory()),
""",
    """                currentProvider.streamMessageWithSampling(
                    messages = applyRequestImageBudget(effectiveAgentHistory()),
""",
    "agent sampling entry",
)
replace_once(
    p,
    """                    temperature = sessionOverrides.temperature,
                    tools = turnTools,
""",
    """                    temperature = sessionOverrides.temperature,
                    tools = turnTools,
                    topP = sessionOverrides.topP,
                    topK = sessionOverrides.topK,
""",
    "agent sampling values",
)
