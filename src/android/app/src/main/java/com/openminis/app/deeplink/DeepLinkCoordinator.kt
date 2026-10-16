package com.openminis.app.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds pending deep-link side-effects that outlive a single navigation event.
 * Mirrors iOS DeepLinkCoordinator.
 */
object DeepLinkCoordinator {

    data class EnvVarCreate(val key: String, val value: String, val note: String)

    private val _pendingEnvVarCreate = MutableStateFlow<EnvVarCreate?>(null)
    val pendingEnvVarCreate: StateFlow<EnvVarCreate?> = _pendingEnvVarCreate.asStateFlow()

    fun setPendingEnvVarCreate(key: String, value: String, note: String = "") {
        _pendingEnvVarCreate.value = EnvVarCreate(key, value, note)
    }

    fun consumePendingEnvVarCreate(): EnvVarCreate? {
        val current = _pendingEnvVarCreate.value
        _pendingEnvVarCreate.value = null
        return current
    }

    /**
     * Optional `?tab=…` hint from `minis://settings/logs?tab=config-audit`.
     * The Logs screen reads this on appear to land on the right
     * segmented-control tab. Cleared by the screen after consumption.
     * Mirrors iOS DeepLinkCoordinator.pendingLogsTab.
     */
    private val _pendingLogsTab = MutableStateFlow<String?>(null)
    val pendingLogsTab: StateFlow<String?> = _pendingLogsTab.asStateFlow()

    fun setPendingLogsTab(tab: String?) { _pendingLogsTab.value = tab }
    fun consumePendingLogsTab(): String? {
        val current = _pendingLogsTab.value
        _pendingLogsTab.value = null
        return current
    }

    /**
     * Pending pinned-shortcut HTML preview: filesystem path + cached title.
     * MainActivity sets this on `minis://preview/html` deep link; ChatScreen
     * reads it on first composition and routes into WebPreviewFullscreen.
     */
    data class HtmlPreview(val sessionId: String, val resourcePath: String, val title: String)

    private val _pendingHtmlPreview = MutableStateFlow<HtmlPreview?>(null)
    val pendingHtmlPreview: StateFlow<HtmlPreview?> = _pendingHtmlPreview.asStateFlow()

    fun setPendingHtmlPreview(sessionId: String, resourcePath: String, title: String) {
        _pendingHtmlPreview.value = HtmlPreview(sessionId, resourcePath, title)
    }

    fun consumePendingHtmlPreview(): HtmlPreview? {
        val current = _pendingHtmlPreview.value
        _pendingHtmlPreview.value = null
        return current
    }

    /**
     * App-icon quick-action that a freshly-opened ChatScreen should auto-
     * trigger on first compose. Mirrors iOS `pendingChatAction` on
     * AIChatViewModel. Set by [com.openminis.app.MainActivity] /
     * [com.openminis.app.ui.navigation.AppNavigation] when the launch
     * intent carries `minis://action/voice_chat` or
     * `minis://action/camera_chat`; consumed exactly once by ChatScreen
     * so re-entering the same chat later doesn't fire the action again.
     */
    /**
     * [T-android-assistant-home] ANALYZE_SCREEN is set by the assistant home
     * page's "analyze screen" card: it opens a fresh draft and the ChatScreen
     * prefills the composer with the screen-analysis prompt, so the user sees
     * (and can edit) what the agent is about to be asked.
     */
    enum class ChatAction { START_VOICE, OPEN_CAMERA, ANALYZE_SCREEN }

    private val _pendingChatAction = MutableStateFlow<ChatAction?>(null)
    val pendingChatAction: StateFlow<ChatAction?> = _pendingChatAction.asStateFlow()

    /**
     * Session the pending action was minted for, or null for "whoever holds
     * the composer". Deep links seed the action and *then* navigate to their
     * own chat, so the ChatScreen that is about to be replaced observes the
     * new value one frame before it is disposed and used to swallow it (the
     * voice dialog opened and died with that screen). Scoping the action to
     * the target session keeps it for the ChatScreen that is arriving.
     */
    private val _pendingChatActionSessionId = MutableStateFlow<String?>(null)

    /**
     * True when only the ChatScreen rendered by a `chat/<sessionId>`
     * destination may consume the action. Deep links always navigate to that
     * destination, and both `sessions` and `chat/<id>` render a ChatScreen, so
     * without this the screen being navigated away from consumes the action
     * and the voice dialog dies with it.
     */
    private val _pendingChatActionChatRouteOnly = MutableStateFlow(false)

    fun setPendingChatAction(
        action: ChatAction,
        sessionId: String? = null,
        chatRouteOnly: Boolean = false,
    ) {
        _pendingChatActionSessionId.value = sessionId
        _pendingChatActionChatRouteOnly.value = chatRouteOnly
        _pendingChatAction.value = action
    }

    /**
     * The pending action as seen by the ChatScreen for [sessionId]. Unscoped
     * actions match every session; [fromChatRoute] must be true for actions
     * minted by a deep link.
     */
    fun pendingChatActionFor(sessionId: String?, fromChatRoute: Boolean = false): ChatAction? {
        val action = _pendingChatAction.value ?: return null
        if (_pendingChatActionChatRouteOnly.value && !fromChatRoute) return null
        val target = _pendingChatActionSessionId.value ?: return action
        return if (target == sessionId) action else null
    }

    /** Session the pending action is scoped to; null when it targets "whoever holds the composer". */
    fun pendingChatActionSessionId(): String? = _pendingChatActionSessionId.value

    fun consumePendingChatAction(): ChatAction? {
        val current = _pendingChatAction.value
        _pendingChatAction.value = null
        _pendingChatActionSessionId.value = null
        _pendingChatActionChatRouteOnly.value = false
        return current
    }
}
