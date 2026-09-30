package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository

/**
 * Settings → Models → Thinking rules. The rules themselves are edited per provider (they depend on
 * the provider's protocol), so this page lists the providers and opens the one you pick.
 */
@Composable
fun ThinkingRulesHubScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onProviderClick: (instanceId: String) -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instances = config.instances
    SettingsScaffold(
        title = stringResource(R.string.settings_thinking_rules),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_cat_models),
    ) {
        SettingsSection(footer = stringResource(R.string.thinking_rules_hub_footer)) {
            instances.forEachIndexed { index, instance ->
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    iconColor = Color(0xFF007AFF),
                    title = instance.label,
                    subtitle = stringResource(
                        if (instance.supportsCustomThinkingRules) R.string.thinking_rules_hub_custom
                        else R.string.thinking_rules_hub_official,
                    ),
                    onClick = { onProviderClick(instance.id) },
                    showDivider = index < instances.size - 1,
                )
            }
        }
    }
}
