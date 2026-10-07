package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.flow.first

@Composable
internal fun SlashCommandPopup(
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    inputTextState: androidx.compose.runtime.State<kotlin.String>,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    composerWidthPxState: androidx.compose.runtime.MutableState<kotlin.Int>,
    showSlashMenuState: androidx.compose.runtime.State<kotlin.Boolean>,
    filteredSlashCommands: kotlin.collections.List<com.openminis.app.ui.chat.SlashCommand>,
) {
    val inputText by inputTextState
    var composerWidthPx by composerWidthPxState
    val showSlashMenu by showSlashMenuState
    if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
        val thinkingLevelState by viewModel.thinkingLevel.collectAsState()
        val thinkingSupported = viewModel.currentModelSupportsReasoning
        val memoryOnState by viewModel.memoryEnabled.collectAsState()
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): androidx.compose.ui.unit.IntOffset {
                        // Anchor: top-edge of the composer column. Place
                        // the popup so its bottom sits 12dp above that edge.
                        // T301: bumped from 6dp — at 6dp the panel was
                        // visually glued to the composer; 12dp gives a
                        // clear breathing gap matching the iOS spacing.
                        val gap = 12
                        val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val y = (anchorBounds.top - popupContentSize.height - gap)
                            .coerceAtLeast(0)
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            },
            onDismissRequest = {
                viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
            },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                // dismissOnClickOutside=false: with focusable=false the popup
                // never receives focus, so the system "click outside" detector
                // can't tell a tap on the BasicTextField below from a tap on
                // the chat list — flipping this off would dismiss the popup
                // every time the IME caret was moved. Dismiss is driven from
                // the chat list / topbar tap-spy below instead, which lets
                // the input field keep focus while still closing the menu
                // when the user clearly looks elsewhere.
                dismissOnClickOutside = false,
            ),
        ) {
            // [T-slash-picker-fixed-height port from iOS 73f1b94a]
            // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
            // Short lists show empty space below the last row;
            // long lists scroll inside the same frame with a
            // visible scroll indicator. Prevents installed
            // Skills + built-ins from pushing the menu past
            // the input bar / off the top of the screen.
           val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
           Box(
               modifier = Modifier
                    .then(
                        if (composerWidthPx > 0) {
                            Modifier.width(
                                with(LocalDensity.current) { composerWidthPx.toDp() },
                            )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .padding(horizontal = 12.dp)
                    // T240: keep a thin visible border instead of the
                    // diffuse 8dp halo that bled out past the panel edge.
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp))
                    .background(ChatColors.inputBg, RoundedCornerShape(14.dp))
                    .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(14.dp)),
            ) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = slashListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 264.dp)
                        .verticalScrollbar(slashListState),
                ) {
                itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                    // Section divider between builtins and
                    // installed Skills (mirrors iOS divider
                    // at the first skill row). Drawn as the
                    // top of the skill row, not between every
                    // row — keeps the menu visually grouped
                    // without splitting every command.
                    if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = ChatColors.toolBorder,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    val isThinking = cmd.id == "thinking"
                    val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                    val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                    val subtitleColor = if (isThinking && !thinkingSupported) {
                        ChatColors.secondaryText
                    } else if (isThinkingActive) {
                        ChatColors.sendButton.copy(alpha = 0.7f)
                    } else ChatColors.secondaryText
                    val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let {
                                if (!isThinking) {
                                    it.clickable {
                                        // [T-android-slash-menu-clears-input] Pass the
                                        // LIVE input so an action command keeps the
                                        // user's body text instead of wiping it.
                                        viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                        // For Skill rows, "/<name> "
                                        // is a typing aid — the user
                                        // still needs to type
                                        // arguments. Bring the IME
                                        // back up + grab focus so
                                        // they can keep typing
                                        // without an extra tap on
                                        // the composer.
                                        if (cmd.isSkill) {
                                            try {
                                                inputFocusRequester.requestFocus()
                                            } catch (_: IllegalStateException) {
                                                // FocusRequester not attached yet.
                                            }
                                            keyboardController?.show()
                                        }
                                    }
                                } else if (thinkingSupported) {
                                    it.clickable {
                                        val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                        viewModel.setThinkingLevel(newLevel)
                                    }
                                } else it
                            }
                            // The first row is the one Enter runs: tint it so that is visible.
                            .background(
                                if (index == 0) ChatColors.sendButton.copy(alpha = 0.10f) else Color.Transparent,
                            )
                            // 44dp rows (board): easy to hit with a thumb.
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = cmd.icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "/${cmd.title.lowercase()}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = titleColor,
                            maxLines = 1,
                            modifier = Modifier.widthIn(min = 108.dp),
                        )
                        // One line, ellipsised: long Skill descriptions must not stretch the row.
                        Text(
                            text = cmd.subtitle,
                            fontSize = 13.sp,
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (cmd.id == "memory") {
                            Icon(
                                imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                contentDescription = null,
                                tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (isThinking && thinkingSupported) {
                            ThinkingLevelPicker(
                                current = thinkingLevelState,
                                // [T-android-thinking-level-arch] Only
                                // offer tiers the bound model supports.
                                availableLevels = viewModel.availableThinkingLevels,
                                onSelect = { level -> viewModel.setThinkingLevel(level) },
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun MentionPopup(
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    inputFieldValueState: androidx.compose.runtime.MutableState<androidx.compose.ui.text.input.TextFieldValue>,
    composerWidthPxState: androidx.compose.runtime.MutableState<kotlin.Int>,
    showMentionMenuState: androidx.compose.runtime.State<kotlin.Boolean>,
    mentionEntriesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.data.FileMentionIndex.Entry>>,
    isMentionScanningState: androidx.compose.runtime.State<kotlin.Boolean>,
    mentionSelectedIndexState: androidx.compose.runtime.State<kotlin.Int>,
) {
    var inputFieldValue by inputFieldValueState
    var composerWidthPx by composerWidthPxState
    val showMentionMenu by showMentionMenuState
    val mentionEntries by mentionEntriesState
    val isMentionScanning by isMentionScanningState
    val mentionSelectedIndex by mentionSelectedIndexState
    if (showMentionMenu) {
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): androidx.compose.ui.unit.IntOffset {
                        val gap = 6
                        val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val y = (anchorBounds.top - popupContentSize.height - gap)
                            .coerceAtLeast(0)
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            },
            onDismissRequest = { viewModel.dismissMentionMenu() },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                // Same rationale as the slash popup: dismissOnClickOutside=false
                // because the input field below the popup sits in the
                // "outside" region (focusable=false → caret moves still
                // count as outside). The chat-list tap-spy that drives
                // dismissSlashMenu also dismisses this menu via
                // dismissMentionMenu(); see the LazyColumn pointerInput.
                dismissOnClickOutside = false,
            ),
        ) {
           Column(
               modifier = Modifier
                    .then(
                        if (composerWidthPx > 0) {
                            Modifier.width(
                                with(LocalDensity.current) { composerWidthPx.toDp() },
                            )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .padding(horizontal = 12.dp)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp))
                    .background(ChatColors.inputBg, RoundedCornerShape(14.dp))
                    .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(14.dp)),
            ) {
                if (mentionEntries.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isMentionScanning) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = ChatColors.secondaryText,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(
                                if (isMentionScanning) R.string.mention_scanning
                                else R.string.mention_no_match
                            ),
                            fontSize = 13.sp,
                            color = ChatColors.secondaryText,
                        )
                    }
                } else {
                    // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                    // Mention picker shares the slash picker's
                    // locked 192dp height (4 rows × 46dp + 8dp)
                    // so both popups have the same band on screen.
                    val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    // Keep the highlighted row visible when the user
                    // navigates with a hardware keyboard. iOS gets this
                    // for free from SwiftUI's List/scrollTo binding;
                    // mimic it explicitly here.
                    LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                        val idx = mentionSelectedIndex
                        if (idx in mentionEntries.indices) {
                            mentionListState.animateScrollToItem(idx)
                        }
                    }
                   LazyColumn(
                       state = mentionListState,
                       modifier = Modifier
                           .fillMaxWidth()
                           .heightIn(max = 264.dp)
                           .verticalScrollbar(mentionListState),
                   ) {
                        itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                            val isSelected = i == mentionSelectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable {
                                        val (newText, newCaret) = viewModel.selectMention(
                                            entry,
                                            currentText = inputFieldValue.text,
                                            currentCaret = inputFieldValue.selection.end,
                                        )
                                        viewModel.setInputText(newText)
                                        inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                            text = newText,
                                            selection = androidx.compose.ui.text.TextRange(newCaret),
                                        )
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                // One line, the path the model will see (mono); the bucket is the badge.
                                Text(
                                    text = entry.displayPath + if (entry.isDirectory) "/" else "",
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = ChatColors.primaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                // Scope / mount badge — matches iOS capsule.
                                Text(
                                    text = entry.mountName ?: entry.scope.displayLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier
                                        .background(
                                            ChatColors.toolCapsuleBg,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentPresetDialog(
    sessionId: kotlin.String,
    context: android.content.Context,
    showAgentPresetSheetState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
) {
    var showAgentPresetSheet by showAgentPresetSheetState
    if (showAgentPresetSheet) {
        val ActivePreset = com.openminis.app.remote.AgentPresetRegistry
            .presetForSession(context, sessionId).id
        MinisAlertDialog(
            onDismissRequest = { showAgentPresetSheet = false },
            title = { Text(stringResource(R.string.chat_agent_presets)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.chat_agent_presets_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (preset in com.openminis.app.remote.AgentPresetRegistry.list()) {
                        SettingsRow(
                            title = preset.name,
                            subtitle = preset.description,
                            onClick = {
                                com.openminis.app.remote.AgentPresetRegistry
                                    .applyToSession(context, sessionId, preset.id)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.chat_agent_preset_applied, preset.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showAgentPresetSheet = false
                            },
                            trailing = {
                                if (preset.id == ActivePreset) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(R.string.chat_agent_preset_current),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentPresetSheet = false }) {
                    Text(stringResource(R.string.overlay_dismiss))
                }
            },
        )
    }
}

@Composable
internal fun ChatModelPickerHost(
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    showModelPickerState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
) {
    var showModelPicker by showModelPickerState
    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()

        // When the user picks a model whose output is image/audio/video, defer
        // the actual binding behind a confirmation dialog — those models can't
        // drive an Agent loop, so we steer the user toward a text-output model
        // (or, if they really want it, hint at adding it as a tool inside an
        // Agent loop instead).
        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
                else -> null
            }
        }
        fun entryById(entryId: String): ModelEntry? =
            config.modelEntries.firstOrNull { it.id == entryId }

        ModelPickerSheet(
            activeEntryId = activeEntryId,
            config = config,
            providerRepository = providerRepository,
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Entry(
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectEntry(entryId)
                    showModelPicker = false
                }
            },
            onDismiss = { showModelPicker = false },
        )

        pendingNonTextSelection?.let { pending ->
            MinisAlertDialog(
                onDismissRequest = { pendingNonTextSelection = null },
                title = stringResource(R.string.model_picker_non_text_warning_title),
                text = stringResource(
                    R.string.model_picker_non_text_warning_body,
                    pending.modelDisplayName,
                    pending.modalityLabel,
                    pending.modalityLabel,
                ),
                confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
                dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
                onConfirm = {
                    viewModel.selectEntry(pending.entryId)
                    pendingNonTextSelection = null
                    showModelPicker = false
                },
            )
        }
    }
}
