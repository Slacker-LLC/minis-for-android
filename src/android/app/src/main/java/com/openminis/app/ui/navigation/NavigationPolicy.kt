package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Suspends until this lifecycle is RESUMED. For one-shot external events (a launch link): the tap
 * guard in [safeNavigate] drops a call made before the entry settles, which is right for a double
 * tap and wrong for an event that fires only once.
 */
internal suspend fun Lifecycle.awaitResumed() {
    currentStateFlow.first { it == Lifecycle.State.RESUMED }
}

/** [awaitResumed] for whichever destination is current, following it as the back stack changes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun NavController.awaitResumed() {
    currentBackStackEntryFlow
        .flatMapLatest { it.lifecycle.currentStateFlow }
        .first { it == Lifecycle.State.RESUMED }
}

/**
 * A share that arrives while the app is running is consumed by a mounted ChatScreen. From any
 * other page (home, settings, the file browser, the session list) nothing would drain it before
 * it expires, so the app opens a fresh chat for it.
 */
internal fun shareNeedsFreshChat(currentRoute: String?): Boolean =
    currentRoute != null && currentRoute != Routes.CHAT

/**
 * The chat the user is actually looking at: the one a ChatScreen reports as mounted, which
 * follows drawer and split-pane switches, else the chat the outer route names.
 */
internal fun visibleChatSessionId(mountedChat: String?, routeChat: String?): String? =
    mountedChat ?: routeChat
