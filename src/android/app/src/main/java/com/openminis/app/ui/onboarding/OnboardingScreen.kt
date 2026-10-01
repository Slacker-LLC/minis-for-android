package com.openminis.app.ui.onboarding

import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import com.openminis.app.R
import com.openminis.app.ui.bots.StatusPill
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSegmented
import com.openminis.app.ui.settings.MinisTopBar
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * Multi-step onboarding flow shown on first launch.
 * Step 1: Welcome + add at least one provider API key
 * Step 2: Select models to configure the Main slot
 */
@Composable
fun OnboardingScreen(
    providerRepository: ProviderRepository,
    onComplete: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }

    when (step) {
        0 -> WelcomeStep(onNext = { step = 1 })
        1 -> ApiKeyStep(
            providerRepository = providerRepository,
            onBack = { step = 0 },
            onNext = { step = 2 },
            onSkip = onComplete,
        )
        2 -> ModelSelectionStep(
            providerRepository = providerRepository,
            onBack = { step = 1 },
            onComplete = onComplete,
        )
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Box(
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.onboarding_welcome_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            WelcomeFeature(Icons.Outlined.Memory, R.string.onboarding_feature_device_title, R.string.onboarding_feature_device_desc)
            WelcomeFeature(Icons.Outlined.Terminal, R.string.onboarding_feature_linux_title, R.string.onboarding_feature_linux_desc)
            WelcomeFeature(Icons.Outlined.VpnKey, R.string.onboarding_feature_models_title, R.string.onboarding_feature_models_desc)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ChatColors.warn.copy(alpha = 0.14f))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = ChatColors.warn, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.onboarding_root_required), fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        MinisButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_get_started), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            com.openminis.app.BuildConfig.VERSION_NAME,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
    }
}

@Composable
private fun WelcomeFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, desc: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(desc), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shared top bar of the two setup steps: Back on the left, Skip on the right, large title below. */
@Composable
private fun OnboardingHeader(title: String, subtitle: String, onBack: () -> Unit, onSkip: () -> Unit) {
    MinisTopBar(
        title = {},
        onBack = onBack,
        actions = {
            MinisTextButton(onClick = onSkip) { Text(stringResource(R.string.common_skip), fontSize = 17.sp) }
        },
    )
    Text(
        title,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun ApiKeyStep(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(ProviderType.anthropic) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(com.openminis.app.ui.settings.settingsPageBackground()).statusBarsPadding().navigationBarsPadding().imePadding()) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_provider_title),
            subtitle = stringResource(R.string.onboarding_provider_subtitle),
            onBack = onBack,
            onSkip = onSkip,
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Provider choice: a segmented row keeps the four built-in providers one tap away.
            val types = listOf(ProviderType.anthropic, ProviderType.gemini, ProviderType.openAI, ProviderType.openRouter)
            SettingsSegmented(
                options = types.map { it.displayName },
                selectedIndex = types.indexOf(selectedType),
                onSelect = { selectedType = types[it]; apiKey = ""; saved = false },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SettingsSection {
                SettingsRow(
                    title = selectedType.displayName,
                    showDivider = false,
                    trailing = {
                        if (saved) StatusPill(stringResource(R.string.onboarding_saved), ChatColors.ok)
                    },
                )
            }
            SettingsSection(header = stringResource(R.string.onboarding_provider_api_key_label, selectedType.displayName)) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; saved = false },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (apiKey.isEmpty()) {
                        Text("sk-…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
            MinisTextButton(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        val instance = ProviderInstance(
                            id = "${selectedType.name}_onboarding",
                            label = selectedType.displayName,
                            providerType = selectedType,
                            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                        )
                        providerRepository.addInstance(instance)
                        providerRepository.saveApiKey(instance.id, apiKey.trim())
                        // Add built-in models
                        for (model in selectedType.builtInModels) {
                            providerRepository.addEntry(
                                ModelEntry(providerInstanceId = instance.id, baseModel = model)
                            )
                        }
                        saved = true
                    }
                },
                enabled = apiKey.isNotBlank() && !saved,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            ) {
                Text(stringResource(if (saved) R.string.onboarding_saved else R.string.onboarding_save_api_key), fontSize = 17.sp)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        MinisTextButton(
            onClick = onNext,
            enabled = saved,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun ModelSelectionStep(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    // Refresh every enabled provider's model list when the page opens, so the user picks from the
    // live catalog rather than only the built-in placeholder list seeded when the provider was added.
    LaunchedEffect(Unit) {
        for (instance in providerRepository.config.value.instances.filter { it.isEnabled }) {
            launch(kotlinx.coroutines.Dispatchers.IO) { providerRepository.refreshModels(instance) }
        }
    }
    val selected = remember { mutableStateListOf<String>() } // entry UUIDs
    var searchText by remember { mutableStateOf("") }

    val enabledInstanceIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val allEntries = config.modelEntries.filter {
        it.providerInstanceId in enabledInstanceIds && !it.isHidden
    }
    val filteredEntries = if (searchText.isBlank()) allEntries else {
        val q = searchText.lowercase()
        allEntries.filter { it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q) }
    }

    // Group by instance
    val grouped = filteredEntries.groupBy { it.providerInstanceId }

    Column(modifier = Modifier.fillMaxSize().background(com.openminis.app.ui.settings.settingsPageBackground()).statusBarsPadding().navigationBarsPadding()) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_select_models_title),
            subtitle = stringResource(R.string.onboarding_select_models_subtitle),
            onBack = onBack,
            onSkip = onComplete,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(com.openminis.app.ui.settings.fillOnGrey())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searchText.isEmpty()) {
                    Text(stringResource(R.string.onboarding_search_models), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            for ((instanceId, entries) in grouped) {
                val instance = config.instances.find { it.id == instanceId }
                item(key = "h_$instanceId") {
                    Text(
                        (instance?.label ?: instanceId),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                item(key = "c_$instanceId") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        entries.forEachIndexed { idx, entry ->
                            val isSelected = entry.id in selected
                            val selectionIndex = selected.indexOf(entry.id)
                            val canSelect = selected.size < 3 || isSelected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) selected.remove(entry.id)
                                        else if (selected.size < 3) selected.add(entry.id)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary)
                                            else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Text(
                                            "${selectionIndex + 1}",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    entry.model.id,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected && selectionIndex == 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.onboarding_main_model),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (idx < entries.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 50.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        MinisTextButton(
            onClick = {
                if (selected.isNotEmpty()) {
                    val current = providerRepository.config.value.slots.main
                    providerRepository.setSlotEntries(
                        com.openminis.app.data.model.ModelSlot.main,
                        (current + selected).distinct(),
                    )
                }
                onComplete()
            },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.onboarding_done_count, selected.size), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
