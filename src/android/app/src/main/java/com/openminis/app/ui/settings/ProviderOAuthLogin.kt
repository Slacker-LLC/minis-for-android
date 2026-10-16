package com.openminis.app.ui.settings

import android.content.Context
import com.openminis.app.R
import com.openminis.app.auth.AntigravityOAuthManager
import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.auth.KimiDeviceFlow
import com.openminis.app.auth.KimiOAuthManager
import com.openminis.app.auth.OAuthNetworkUnreachableException
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.auth.OpenRouterOAuthManager
import com.openminis.app.auth.ProviderCustomizationUnavailableException
import com.openminis.app.auth.XAIOAuthManager
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository

/**
 * The OAuth sign-in shared by the add-provider form and the provider detail page, so a new
 * OAuth provider is wired in one place.
 */

/** Claude and Antigravity sign-in is compiled in only when the private customization is present. */
internal fun isOAuthAvailableInThisBuild(type: ProviderType): Boolean = when (type) {
    ProviderType.anthropic -> ClaudeOAuthManager.isAvailableInThisBuild
    ProviderType.antigravity -> AntigravityOAuthManager.isAvailableInThisBuild
    else -> true
}

/**
 * Runs the sign-in flow of [type] and returns the credential it stored under [instanceId], or null
 * when [type] has no OAuth flow. [onDeviceCode] fires only for the device-code logins (Kimi Code, xAI).
 */
internal suspend fun loginWithOAuth(
    context: Context,
    type: ProviderType,
    instanceId: String,
    providerRepository: ProviderRepository,
    onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
): String? = when (type) {
    ProviderType.kimiCode -> KimiOAuthManager.login(context, instanceId, providerRepository, onDeviceCode)
    ProviderType.anthropic -> ClaudeOAuthManager.login(context, instanceId, providerRepository)
    ProviderType.openAI -> OpenAIOAuthManager.login(context, instanceId, providerRepository)
    ProviderType.openRouter -> OpenRouterOAuthManager.login(context, instanceId, providerRepository)
    ProviderType.xAI -> XAIOAuthManager.login(context, instanceId, providerRepository, onDeviceCode)
    ProviderType.antigravity -> AntigravityOAuthManager.login(context, instanceId, providerRepository)
    else -> null
}

/**
 * The localized text for the sign-in failures a user can act on (build without the provider,
 * no network), or null for any other error, which each screen words its own way.
 */
internal fun oauthKnownFailureMessage(context: Context, e: Exception): String? = when (e) {
    is ProviderCustomizationUnavailableException -> context.getString(R.string.provider_oauth_not_available_in_this_build)
    is OAuthNetworkUnreachableException -> context.getString(R.string.add_provider_oauth_network_unreachable)
    else -> null
}
