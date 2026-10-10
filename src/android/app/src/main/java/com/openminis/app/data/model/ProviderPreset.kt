package com.openminis.app.data.model

/**
 * Well-known model services that speak the OpenAI or Anthropic wire format: picking one in "Add provider" only fills in
 * the label and endpoint, the instance is an ordinary [ProviderType.openAI] / [ProviderType.anthropic] one. Base URLs are the
 * services' documented API roots (cross-checked against the models.dev catalogue); [appendV1] is false for every OpenAI-format
 * entry because the URL is already complete, and true for the Anthropic-format ones, whose root the app extends with `/v1`.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseURL: String,
    val appendV1: Boolean,
    val group: Group,
) {
    enum class Group { CHINA, CODING_PLAN, GLOBAL, GATEWAY, LOCAL }

    companion object {
        private fun oa(id: String, name: String, base: String, group: Group) =
            ProviderPreset(id, name, ProviderType.openAI, base, appendV1 = false, group = group)

        private fun an(id: String, name: String, root: String, group: Group) =
            ProviderPreset(id, name, ProviderType.anthropic, root, appendV1 = true, group = group)

        val all: List<ProviderPreset> = listOf(
            // Mainland China
            oa("deepseek", "DeepSeek", "https://api.deepseek.com", Group.CHINA),
            oa("moonshot-cn", "Moonshot (Kimi)", "https://api.moonshot.cn/v1", Group.CHINA),
            oa("zhipu", "Zhipu GLM", "https://open.bigmodel.cn/api/paas/v4", Group.CHINA),
            oa("qwen-cn", "Alibaba Qwen (DashScope)", "https://dashscope.aliyuncs.com/compatible-mode/v1", Group.CHINA),
            oa("volcengine", "Volcengine Ark (Doubao)", "https://ark.cn-beijing.volces.com/api/v3", Group.CHINA),
            oa("mimo", "Xiaomi MiMo", "https://api.xiaomimimo.com/v1", Group.CHINA),
            oa("stepfun", "StepFun", "https://api.stepfun.com/v1", Group.CHINA),
            oa("qianfan", "Baidu Qianfan", "https://qianfan.baidubce.com/v2", Group.CHINA),
            oa("siliconflow-cn", "SiliconFlow", "https://api.siliconflow.cn/v1", Group.CHINA),
            oa("modelscope", "ModelScope", "https://api-inference.modelscope.cn/v1", Group.CHINA),
            oa("longcat", "LongCat", "https://api.longcat.chat/openai", Group.CHINA),
            an("minimax-cn", "MiniMax", "https://api.minimax.cn/anthropic", Group.CHINA),
            // Coding plans (subscription endpoints)
            oa("zhipu-coding", "Zhipu GLM Coding Plan", "https://open.bigmodel.cn/api/coding/paas/v4", Group.CODING_PLAN),
            oa("zai-coding", "Z.ai Coding Plan", "https://api.z.ai/api/coding/paas/v4", Group.CODING_PLAN),
            an("zai-claude", "Z.ai Coding Plan (Anthropic format)", "https://api.z.ai/api/anthropic", Group.CODING_PLAN),
            oa("qwen-coding", "Alibaba Coding Plan", "https://coding.dashscope.aliyuncs.com/v1", Group.CODING_PLAN),
            oa("volcengine-coding", "Volcengine Coding Plan", "https://ark.cn-beijing.volces.com/api/coding/v3", Group.CODING_PLAN),
            oa("tencent-coding", "Tencent Coding Plan", "https://api.lkeap.cloud.tencent.com/coding/v3", Group.CODING_PLAN),
            oa("mimo-plan-cn", "Xiaomi MiMo Token Plan", "https://token-plan-cn.xiaomimimo.com/v1", Group.CODING_PLAN),
            an("mimo-plan-claude", "Xiaomi MiMo Token Plan (Anthropic format)", "https://token-plan-cn.xiaomimimo.com/anthropic", Group.CODING_PLAN),
            an("minimax", "MiniMax Coding Plan (global)", "https://api.minimax.io/anthropic", Group.CODING_PLAN),
            an("kimi-claude", "Kimi (Anthropic format)", "https://api.moonshot.ai/anthropic", Group.CODING_PLAN),
            an("deepseek-claude", "DeepSeek (Anthropic format)", "https://api.deepseek.com/anthropic", Group.CODING_PLAN),
            oa("commandcode", "Command Code", "https://api.commandcode.ai/provider/v1", Group.CODING_PLAN),
            oa("opencode-zen", "OpenCode Zen", "https://opencode.ai/zen/v1", Group.CODING_PLAN),
            oa("opencode-go", "OpenCode Go", "https://opencode.ai/zen/go/v1", Group.CODING_PLAN),
            // Global
            oa("moonshot", "Moonshot (global)", "https://api.moonshot.ai/v1", Group.GLOBAL),
            oa("zai", "Z.ai", "https://api.z.ai/api/paas/v4", Group.GLOBAL),
            oa("qwen-intl", "Alibaba Qwen (international)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", Group.GLOBAL),
            oa("stepfun-intl", "StepFun (international)", "https://api.stepfun.ai/v1", Group.GLOBAL),
            oa("siliconflow", "SiliconFlow (international)", "https://api.siliconflow.com/v1", Group.GLOBAL),
            oa("groq", "Groq", "https://api.groq.com/openai/v1", Group.GLOBAL),
            oa("cerebras", "Cerebras", "https://api.cerebras.ai/v1", Group.GLOBAL),
            oa("together", "Together AI", "https://api.together.xyz/v1", Group.GLOBAL),
            oa("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", Group.GLOBAL),
            oa("mistral", "Mistral", "https://api.mistral.ai/v1", Group.GLOBAL),
            oa("perplexity", "Perplexity", "https://api.perplexity.ai", Group.GLOBAL),
            oa("cohere", "Cohere", "https://api.cohere.ai/compatibility/v1", Group.GLOBAL),
            oa("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", Group.GLOBAL),
            oa("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", Group.GLOBAL),
            oa("huggingface", "Hugging Face Inference Providers", "https://router.huggingface.co/v1", Group.GLOBAL),
            oa("novita", "Novita AI", "https://api.novita.ai/openai", Group.GLOBAL),
            oa("nebius", "Nebius Token Factory", "https://api.tokenfactory.nebius.com/v1", Group.GLOBAL),
            oa("llama", "Meta Llama API", "https://api.llama.com/compat/v1", Group.GLOBAL),
            oa("ollama-cloud", "Ollama Cloud", "https://ollama.com/v1", Group.GLOBAL),
            // Gateways / aggregators
            oa("vercel", "Vercel AI Gateway", "https://ai-gateway.vercel.sh/v1", Group.GATEWAY),
            oa("poe", "Poe", "https://api.poe.com/v1", Group.GATEWAY),
            oa("aihubmix", "AIHubMix", "https://aihubmix.com/v1", Group.GATEWAY),
            oa("302ai", "302.AI", "https://api.302.ai/v1", Group.GATEWAY),
            oa("zenmux", "ZenMux", "https://zenmux.ai/api/v1", Group.GATEWAY),
            oa("requesty", "Requesty", "https://router.requesty.ai/v1", Group.GATEWAY),
            oa("helicone", "Helicone AI Gateway", "https://ai-gateway.helicone.ai/v1", Group.GATEWAY),
            oa("kilo", "Kilo Gateway", "https://api.kilo.ai/api/gateway", Group.GATEWAY),
            // On this phone / this network (plain http; the form asks before allowing it)
            oa("ollama", "Ollama (local)", "http://127.0.0.1:11434/v1", Group.LOCAL),
            oa("lmstudio", "LM Studio (local)", "http://127.0.0.1:1234/v1", Group.LOCAL),
        )
    }
}
