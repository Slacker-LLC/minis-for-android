package com.openminis.app.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection

/**
 * Everything the pet settings screen needs, snapshotted by the activity.
 *
 * Held as one immutable value rather than a pile of individual state objects
 * so a single `refresh()` after any mutation cannot leave two rows disagreeing
 * about, say, whether the pet is enabled.
 */
internal data class PetUiState(
    val pets: List<InstalledPet> = emptyList(),
    val selected: InstalledPet? = null,
    val enabled: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val scale: Float = 1f,
    val speed: Float = 1f,
    val wander: Boolean = true,
    val edgeSnap: Boolean = true,
    val autoHide: Boolean = true,
    val bubble: Boolean = true,
    val tapOpensApp: Boolean = true,
)

internal class PetActions(
    val onBack: () -> Unit = {},
    val onToggleEnabled: (Boolean) -> Unit = {},
    val onSelectPet: (InstalledPet) -> Unit = {},
    val onImport: () -> Unit = {},
    val onScale: (Float) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onCommitAppearance: () -> Unit = {},
    val onWander: (Boolean) -> Unit = {},
    val onEdgeSnap: (Boolean) -> Unit = {},
    val onAutoHide: (Boolean) -> Unit = {},
    val onBubble: (Boolean) -> Unit = {},
    val onTapOpensApp: (Boolean) -> Unit = {},
)

private val AccentBlue = Color(0xFF4D6BFE)
private val AccentGreen = Color(0xFF34C759)
private val AccentOrange = Color(0xFFFF9500)
private val AccentPurple = Color(0xFF5856D6)
private val AccentTeal = Color(0xFF30B0C7)
private val AccentPink = Color(0xFFFF2D55)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PetControlScreen(state: PetUiState, actions: PetActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面宠物") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            PetPreviewCard(state.selected)

            PetSection(
                title = "显示",
                // The permission itself is managed in 设置 → 权限 → 系统权限,
                // next to the other system-level grants; duplicating the grant
                // flow here would give the same permission two owners.
                footer = if (!state.hasOverlayPermission) {
                    "还缺「显示在其他应用上层」权限，宠物浮不到桌面上。去 设置 → 权限 → 系统权限 里打开。"
                } else {
                    null
                },
            ) {
                PetRow(
                    icon = Icons.Outlined.Pets,
                    iconColor = AccentBlue,
                    title = "启用桌面宠物",
                    subtitle = when {
                        state.selected == null -> "先导入并选择一个宠物"
                        !state.hasOverlayPermission -> "缺少悬浮窗权限"
                        else -> state.selected.manifest.displayName
                    },
                    showDivider = false,
                ) {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = actions.onToggleEnabled,
                        enabled = state.selected != null,
                    )
                }
            }

            PetSection(
                title = "对话",
                footer = "点一下宠物打开独立聊天小窗；点到别的 App、按返回键或键盘返回都会关闭小窗。" +
                    "语音输入和语音回复直接复用 App 的语音模型与 API 配置。",
            ) {
                PetRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    iconColor = AccentBlue,
                    title = "说话气泡",
                    subtitle = "显示回答，以及 Agent 在干什么",
                ) {
                    Switch(checked = state.bubble, onCheckedChange = actions.onBubble)
                }
                PetRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    iconColor = AccentPink,
                    title = "语音对话",
                    subtitle = "直接跟随 App 的 Voice Input / Voice Output 模型与 API 配置",
                    showDivider = false,
                ) {
                    Text(
                        text = "跟随 App",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PetSection(
                title = "行为",
                footer = "宠物只在真正空闲时才会自己走动；Agent 忙碌、等待或报错时它会停在原地表演对应状态。",
            ) {
                PetRow(
                    icon = Icons.Outlined.DirectionsWalk,
                    iconColor = AccentGreen,
                    title = "自主巡游",
                    subtitle = "空闲时自己在屏幕上溜达",
                ) {
                    Switch(checked = state.wander, onCheckedChange = actions.onWander)
                }
                PetRow(
                    icon = Icons.Outlined.ZoomOutMap,
                    iconColor = AccentTeal,
                    title = "边缘吸附",
                    subtitle = "松手后自动贴到最近的一侧",
                ) {
                    Switch(checked = state.edgeSnap, onCheckedChange = actions.onEdgeSnap)
                }
                PetRow(
                    icon = Icons.Outlined.VisibilityOff,
                    iconColor = AccentOrange,
                    title = "闲置后贴边隐藏",
                    subtitle = "半分钟没互动就缩到屏幕边上，点一下回来",
                ) {
                    Switch(checked = state.autoHide, onCheckedChange = actions.onAutoHide)
                }
                PetRow(
                    icon = Icons.Outlined.TouchApp,
                    iconColor = AccentBlue,
                    title = "双击打开 App",
                    subtitle = "单击说话，双击回主界面，长按出菜单",
                    showDivider = false,
                ) {
                    Switch(checked = state.tapOpensApp, onCheckedChange = actions.onTapOpensApp)
                }
            }

            PetSection(title = "外观", footer = "调整后立即生效。") {
                PetSliderRow(
                    icon = Icons.Outlined.ZoomOutMap,
                    iconColor = AccentPurple,
                    title = "大小",
                    value = state.scale,
                    valueLabel = "%.2f×".format(state.scale),
                    onValueChange = actions.onScale,
                    onValueChangeFinished = actions.onCommitAppearance,
                )
                PetSliderRow(
                    icon = Icons.Outlined.Speed,
                    iconColor = AccentOrange,
                    title = "动画速度",
                    value = state.speed,
                    valueLabel = "%.2f×".format(state.speed),
                    onValueChange = actions.onSpeed,
                    onValueChangeFinished = actions.onCommitAppearance,
                    showDivider = false,
                )
            }

            PetSection(
                title = "宠物包",
                footer = "ZIP 里放 pet.json + spritesheet.webp；默认兼容 8×9 网格、单格 192×208 的 Codex 图集。",
            ) {
                state.pets.forEach { pet ->
                    PetChoiceRow(
                        pet = pet,
                        selected = pet.manifest.id == state.selected?.manifest?.id,
                        onClick = { actions.onSelectPet(pet) },
                    )
                }
                PetRow(
                    icon = Icons.Outlined.FileDownload,
                    iconColor = AccentTeal,
                    title = "导入宠物 ZIP",
                    subtitle = if (state.pets.isEmpty()) "还没有任何宠物" else "已安装 ${state.pets.size} 个",
                    onClick = actions.onImport,
                    showDivider = false,
                ) {
                    Chevron()
                }
            }
        }
    }
}

/** Live animated preview — reuses the exact view the overlay renders with. */
@Composable
private fun PetPreviewCard(pet: InstalledPet?) {
    // Reload the preview only when the pet id changes; reloading on every
    // recomposition would re-decode the spritesheet for each animated frame.
    var loadedPetId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pet == null) {
            loadedPetId = null
            Icon(
                imageVector = Icons.Outlined.Pets,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "还没有选择宠物",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AndroidView(
                factory = { ctx -> PetSpriteView(ctx) },
                update = { view ->
                    if (loadedPetId != pet.manifest.id) {
                        loadedPetId = pet.manifest.id
                        runCatching {
                            view.loadPet(pet)
                            view.setState(PetState.IDLE)
                        }
                    }
                },
                modifier = Modifier.size(width = 120.dp, height = 130.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = pet.manifest.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            pet.manifest.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun PetSection(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsSection(header = title, footer = footer, content = content)
}

@Composable
private fun PetRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showChevron = false,
        showDivider = showDivider,
        trailing = trailing,
    )
}

@Composable
private fun PetSliderRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(color = iconColor, shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0.5f..2.0f,
            modifier = Modifier.padding(start = 56.dp, end = 18.dp),
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp, end = 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun PetChoiceRow(pet: InstalledPet, selected: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pet.manifest.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pet.manifest.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 58.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(20.dp),
    )
}
