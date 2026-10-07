package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import kotlin.math.roundToInt
import com.openminis.app.data.repository.updateEntry

/**
 * Detail / edit screen for a single ModelEntry. T210: brought to iOS
 * parity with five inset-grouped sections — Identity, Capabilities,
 * Visibility, Input Modality, Output Modality (+ optional Reset). The
 * data layer (`ModelOverrides.contextWindow`, `supportsReasoning`,
 * `inputModalities`, `outputModalities`) was already in place; pre-T210
 * the UI only exposed displayName / maxOutputTokens / hidden, which
 * meant Android users had no way to override the things iOS lets them
 * tweak (context window, thinking flag, per-modality enablement).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEntryDetailScreen(
    instanceId: String,
    entryId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val entry = config.modelEntries.find { it.id == entryId && it.providerInstanceId == instanceId }
    val instance = config.instances.find { it.id == instanceId }

    if (entry == null) {
        onBack()
        return
    }

    val baseModel = entry.baseModel
    val overrides = entry.overrides

    var modelId by remember { mutableStateOf(baseModel.id) }
    var displayName by remember { mutableStateOf(overrides.displayName ?: baseModel.displayName) }
    var maxOutputTokensText by remember { mutableStateOf(overrides.maxOutputTokens?.toString() ?: "") }
    var contextWindowText by remember { mutableStateOf(overrides.contextWindow?.toString() ?: "") }
    var thinkingEnabled by remember {
        mutableStateOf(overrides.supportsReasoning ?: baseModel.supportsReasoning ?: false)
    }
    var defaultThinkingLevel by remember { mutableStateOf(overrides.defaultThinkingLevel) }
    var thinkingMenuExpanded by remember { mutableStateOf(false) }
    var contextLimitTokens by remember { mutableStateOf(overrides.contextLimitTokens) }
    var lastContextLimitTokens by remember { mutableStateOf(overrides.lastContextLimitTokens) }
    // [T-eta-hosted-web-search] The provider's own web search; off unless the entry asked for it.
    var hostedWebSearch by remember {
        mutableStateOf(overrides.hostedWebSearch ?: baseModel.hostedWebSearch)
    }
    var isHidden by remember { mutableStateOf(entry.isHidden) }
    var showQuickTest by remember { mutableStateOf(false) }

    // Modality state — derive from override-or-base so the toggles reflect
    // what the model actually supports today, then let the user flip from
    // there. A null override means "inherit baseModel" (data-layer
    // contract); on save we only persist a non-null list when it diverges
    // from the inherited set.
    // Defensive normalization: pre-T213 SharedPreferences may hold suffixed strings
    // (`image_input`) saved before the parse-boundary fix, which would silently fail
    // the bare-name `"image" in effectiveInput` checks below.
    val effectiveInput = (overrides.inputModalities ?: baseModel.inputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    val effectiveOutput = (overrides.outputModalities ?: baseModel.outputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    var imageInput by remember { mutableStateOf("image" in effectiveInput) }
    var pdfInput by remember { mutableStateOf("pdf" in effectiveInput) }
    var audioInput by remember { mutableStateOf("audio" in effectiveInput) }
    var videoInput by remember { mutableStateOf("video" in effectiveInput) }
    var imageOutput by remember { mutableStateOf("image" in effectiveOutput) }
    var audioOutput by remember { mutableStateOf("audio" in effectiveOutput) }

    SettingsScaffold(
        title = stringResource(R.string.model_entry_model_detail),
        // [T-android-modeldetail-savecancel-ios-parity] iOS modal pattern
        // (ProviderInstanceDetailView toolbar): Cancel is a plain leading
        // text action, the title is centered, and Save is the single
        // emphasized trailing action — the filled pill is the MD3 analog of
        // iOS's semibold Save. No back arrow: Cancel and system back both
        // discard + pop.
        onBack = null,
        centerTitle = true,
        navigation = {
            MinisTextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        actions = {
            MinisButton(
                onClick = {
                    val baseInputs = baseModel.inputModalities ?: emptyList()
                    val baseOutputs = baseModel.outputModalities ?: emptyList()
                    // The page shows some modalities only; the rest of the effective set (text in/out,
                    // video out, ...) belongs to the model and must survive the save.
                    val newInputs = mergeModalities(
                        effectiveInput,
                        shown = setOf("image", "pdf", "audio", "video"),
                        enabled = buildSet {
                            if (imageInput) add("image")
                            if (pdfInput) add("pdf")
                            if (audioInput) add("audio")
                            if (videoInput) add("video")
                        },
                    )
                    val newOutputs = mergeModalities(
                        effectiveOutput,
                        shown = setOf("image", "audio"),
                        enabled = buildSet {
                            if (imageOutput) add("image")
                            if (audioOutput) add("audio")
                        },
                    )
                    // Start from the stored overrides so fields this page does not edit
                    // (maxThinkingLevel, ...) are kept, not reset to null.
                    val newOverrides = overrides.copy(
                        displayName = displayName.trim().takeIf { it.isNotEmpty() && it != baseModel.displayName },
                        maxOutputTokens = maxOutputTokensText.trim().toIntOrNull()?.takeIf { it > 0 },
                        contextWindow = contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 },
                        // supportsReasoning: persist only when user diverged from base.
                        supportsReasoning = thinkingEnabled.takeIf { it != (baseModel.supportsReasoning ?: false) },
                        hostedWebSearch = hostedWebSearch.takeIf { it != baseModel.hostedWebSearch },
                        defaultThinkingLevel = defaultThinkingLevel,
                        contextLimitTokens = contextLimitTokens,
                        lastContextLimitTokens = lastContextLimitTokens,
                        // Modality lists: persist only when user-edited set differs
                        // from baseModel's set; otherwise leave null so the entry
                        // tracks future provider updates to the base modalities.
                        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
                        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
                    )
                    val updated = if (entry.isCustom) {
                        entry.copy(baseModel = baseModel.copy(id = modelId), overrides = newOverrides, isHidden = isHidden)
                    } else {
                        entry.copy(overrides = newOverrides, isHidden = isHidden)
                    }
                    providerRepository.updateEntry(updated)
                    onBack()
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        },
    ) {
        // ── Identity ────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = if (entry.isCustom) stringResource(R.string.modeldetail_identity_footer_custom)
                     else stringResource(R.string.modeldetail_identity_footer_builtin),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    onValueChange = { if (entry.isCustom) modelId = it },
                    singleLine = true,
                    readOnly = !entry.isCustom,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_display_name))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                )
            }
            SettingsValueRow(
                title = stringResource(R.string.model_entry_provider),
                value = instance?.label ?: instanceId,
                showDivider = false,
            )
        }

        // ── Capabilities ────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_capabilities),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_context_window))
                SectionTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.contextWindow?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_max_output_tokens))
                SectionTextField(
                    value = maxOutputTokensText,
                    onValueChange = { maxOutputTokensText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.maxOutputTokens?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // Inline warning when the value exceeds the provider-reported
                // limit. The save still goes through so power users can probe
                // a higher cap; we just flag it.
                val enteredTokens = maxOutputTokensText.trim().toIntOrNull()
                val baseLimit = baseModel.maxOutputTokens
                if (enteredTokens != null && baseLimit != null && enteredTokens > baseLimit) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.modeldetail_max_tokens_exceeds_warning, baseLimit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_thinking),
                checked = thinkingEnabled,
                onCheckedChange = { thinkingEnabled = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_hosted_web_search),
                subtitle = stringResource(R.string.modeldetail_hosted_web_search_sub),
                checked = hostedWebSearch,
                onCheckedChange = { hostedWebSearch = it },
                showDivider = false,
            )
        }

        // ── Entry defaults ──────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.model_entry_default_thinking),
        ) {
            Box {
                SettingsRow(
                    title = stringResource(R.string.model_entry_default_thinking),
                    subtitle = stringResource(thinkingLevelLabel(defaultThinkingLevel)),
                    onClick = { thinkingMenuExpanded = true },
                    showDivider = false,
                )
                DropdownMenu(
                    expanded = thinkingMenuExpanded,
                    onDismissRequest = { thinkingMenuExpanded = false },
                ) {
                    val choices = listOf<ThinkingLevel?>(null) + ThinkingLevel.entries
                    choices.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(stringResource(thinkingLevelLabel(level))) },
                            onClick = {
                                defaultThinkingLevel = level
                                thinkingMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.model_entry_context_limit_label),
            footer = stringResource(R.string.model_entry_context_limit_footer),
        ) {
            val enabled = contextLimitTokens != null
            SettingsSwitchRow(
                title = stringResource(R.string.model_entry_context_limit_enabled),
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        val restored = lastContextLimitTokens ?: 128_000
                        contextLimitTokens = restored
                        lastContextLimitTokens = restored
                    } else {
                        lastContextLimitTokens = contextLimitTokens ?: lastContextLimitTokens
                        contextLimitTokens = null
                    }
                },
                showDivider = enabled,
            )
            if (enabled) {
                ContextLimitSlider(
                    value = contextLimitTokens,
                    unlimitedLabel = stringResource(R.string.model_entry_context_unlimited),
                    onValueChange = { newTokens ->
                        contextLimitTokens = newTokens
                        lastContextLimitTokens = newTokens
                    },
                )
            }
        }

        // ── Visibility ──────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.model_entry_visibility),
            footer = stringResource(R.string.model_entry_hidden_models_won_t_appear_in_the_model_),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.model_entry_hidden),
                checked = isHidden,
                onCheckedChange = { isHidden = it },
                showDivider = false,
            )
        }

        // ── Input Modality ──────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_input_modality),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_input),
                checked = imageInput,
                onCheckedChange = { imageInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_pdf_input),
                checked = pdfInput,
                onCheckedChange = { pdfInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_input),
                checked = audioInput,
                onCheckedChange = { audioInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_input),
                checked = videoInput,
                onCheckedChange = { videoInput = it },
                showDivider = false,
            )
        }

        // ── Output Modality ─────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_output_modality),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_output),
                checked = imageOutput,
                onCheckedChange = { imageOutput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_output),
                checked = audioOutput,
                onCheckedChange = { audioOutput = it },
                showDivider = false,
            )
        }

        // ── Quick Test ──
        SettingsSection(
            footer = stringResource(R.string.quicktest_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.quicktest_button),
                icon = Icons.Outlined.Bolt,
                showChevron = false,
                showDivider = false,
                onClick = { showQuickTest = true },
            )
        }

        // ── Reset (only for built-in models that have been customized) ──
        if (!entry.isCustom && entry.isUserModified) {
            SettingsSection(
            ) {
                SettingsRow(
                    title = stringResource(R.string.model_entry_reset_to_default),
                    titleColor = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    showDivider = false,
                    onClick = {
                        providerRepository.updateEntry(entry.copy(overrides = ModelOverrides(), isHidden = false))
                        onBack()
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showQuickTest) {
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { showQuickTest = false },
        )
    }
}

private fun thinkingLevelLabel(level: ThinkingLevel?): Int = when (level) {
    null -> R.string.model_entry_thinking_default
    ThinkingLevel.OFF -> R.string.model_entry_thinking_off
    ThinkingLevel.LOW -> R.string.model_entry_thinking_low
    ThinkingLevel.MEDIUM -> R.string.model_entry_thinking_medium
    ThinkingLevel.HIGH -> R.string.model_entry_thinking_high
    ThinkingLevel.XHIGH -> R.string.model_entry_thinking_xhigh
    ThinkingLevel.MAX -> R.string.model_entry_thinking_max
    ThinkingLevel.ULTRA -> R.string.model_entry_thinking_ultra
}

private const val ENTRY_CONTEXT_UNLIMITED_SENTINEL: Int = Int.MAX_VALUE
private val ENTRY_CONTEXT_PRESETS = listOf(32_000, 64_000, 128_000, 200_000, 400_000, 1_000_000)

private fun formatEntryContextPreset(tokens: Int): String =
    if (tokens >= 1_000_000) "${tokens / 1_000_000}M" else "${tokens / 1_000}K"

@Composable
private fun ContextLimitSlider(
    value: Int?,
    unlimitedLabel: String,
    onValueChange: (Int) -> Unit,
) {
    data class Step(val label: String, val tokens: Int)
    val steps = remember(unlimitedLabel) {
        ENTRY_CONTEXT_PRESETS.map { Step(formatEntryContextPreset(it), it) } +
            Step(unlimitedLabel, ENTRY_CONTEXT_UNLIMITED_SENTINEL)
    }
    var selectedIndex by remember(steps, value) {
        mutableStateOf(
            when {
                value == null || value >= ENTRY_CONTEXT_UNLIMITED_SENTINEL -> steps.lastIndex
                else -> steps.indexOfLast { it.tokens <= value }.takeIf { it >= 0 } ?: 0
            },
        )
    }
    val currentLabel = when {
        value == null || value >= ENTRY_CONTEXT_UNLIMITED_SENTINEL -> unlimitedLabel
        else -> steps.firstOrNull { it.tokens == value }?.label ?: "${value / 1000}K"
    }
    SettingsCardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.model_entry_context_limit_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { index ->
                val snapped = index.roundToInt().coerceIn(0, steps.lastIndex)
                if (snapped != selectedIndex) {
                    selectedIndex = snapped
                    onValueChange(steps[snapped].tokens)
                }
            },
            valueRange = 0f..steps.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (steps.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Text(steps.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(unlimitedLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * [effective] with the modalities in [shown] set to exactly [enabled]; modalities the page does not
 * show stay as they are, in their original order, new ones appended in the order of [shown].
 */
internal fun mergeModalities(effective: List<String>, shown: Set<String>, enabled: Set<String>): List<String> {
    val kept = effective.filter { it !in shown }
    val added = shown.filter { it in enabled }
    return (kept + added).distinct()
}
