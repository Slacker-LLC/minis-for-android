package com.openminis.app.voicecall.realtime

import android.content.Context
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通话引擎选择。API key 不在这里存：直接复用「管理提供商」里已有的提供商和它的 key，
 * 这里只存非敏感的选择（选了哪个提供商、哪个协议档案、模型/音色/业务空间/自定义地址）。
 */
data class RealtimeSelection(
    val enabled: Boolean = false,
    val instanceId: String = "",
    val profileId: String = RealtimeProfiles.OPENAI.id,
    val model: String = "",
    val voice: String = "",
    val workspace: String = "",
    val customUrl: String = "",
)

/**
 * 这个提供商的 key 能不能用在该协议档案上。OpenAI/Gemini/xAI 的实时端点是官方固定地址，
 * 所以只接受官方端点的提供商（自定义 base URL 或 Azure 的 key 发给官方地址是错的）；
 * 其余档案（千问、智谱、阶跃、豆包、自定义）接受任意 API key 类型的提供商，由用户自己对应。
 */
internal fun providerFits(profile: RealtimeProfile, instance: ProviderInstance): Boolean {
    if (!instance.isEnabled || instance.credentialType != ProviderCredential.apiKey) return false
    return when (profile.id) {
        RealtimeProfiles.OPENAI.id ->
            instance.providerType == ProviderType.openAI && instance.customBaseURL.isNullOrBlank() && !instance.azureMode
        RealtimeProfiles.GEMINI.id -> instance.providerType == ProviderType.gemini && instance.customBaseURL.isNullOrBlank()
        RealtimeProfiles.XAI.id -> instance.providerType == ProviderType.xAI && instance.customBaseURL.isNullOrBlank()
        else -> !instance.providerType.isUnsupported()
    }
}

private fun ProviderType.isUnsupported() = this == ProviderType.unsupported

/** 由选择 + 提供商 + 它的 key 得到连接；任何一项不成立都返回 null（调用方回到经典流水线）。 */
internal fun resolveRealtime(selection: RealtimeSelection, instance: ProviderInstance?, apiKey: String?): RealtimeConnection? {
    if (!selection.enabled || instance == null || apiKey.isNullOrBlank()) return null
    val profile = RealtimeProfiles.byId(selection.profileId) ?: return null
    if (instance.id != selection.instanceId || !providerFits(profile, instance)) return null
    return RealtimeProfiles.connection(
        RealtimeUserConfig(
            profileId = profile.id, apiKey = apiKey, model = selection.model.trim(), voice = selection.voice.trim(),
            workspace = selection.workspace.trim(), customUrl = selection.customUrl.trim(),
        ),
    )
}

/** 选择的持久化（SharedPreferences，非敏感）。 */
object RealtimeCallSettings {
    private const val PREFS = "voice_call_realtime"

    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val _state = MutableStateFlow(RealtimeSelection())
    val state: StateFlow<RealtimeSelection> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = RealtimeSelection(
            enabled = p.getBoolean("enabled", false),
            instanceId = p.getString("instanceId", "").orEmpty(),
            profileId = p.getString("profileId", RealtimeProfiles.OPENAI.id).orEmpty(),
            model = p.getString("model", "").orEmpty(),
            voice = p.getString("voice", "").orEmpty(),
            workspace = p.getString("workspace", "").orEmpty(),
            customUrl = p.getString("customUrl", "").orEmpty(),
        )
    }

    @Synchronized
    fun update(transform: (RealtimeSelection) -> RealtimeSelection) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        prefs?.edit()
            ?.putBoolean("enabled", next.enabled)?.putString("instanceId", next.instanceId)
            ?.putString("profileId", next.profileId)?.putString("model", next.model)
            ?.putString("voice", next.voice)?.putString("workspace", next.workspace)
            ?.putString("customUrl", next.customUrl)?.apply()
    }
}
