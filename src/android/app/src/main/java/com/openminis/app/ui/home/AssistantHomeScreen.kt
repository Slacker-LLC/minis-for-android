package com.openminis.app.ui.home

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.AssistantHomePrefs
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.settings.SettingsSwitchRow
import com.openminis.app.ui.theme.ChatColors
import java.time.LocalTime

/**
 * [T-android-assistant-home] The assistant home page: a greeting plus a 2×2
 * grid of quick actions, with the session list one tap away.
 *
 * Roadmap contract (docs/analysis/eta-port-program.md, Phase 1 item 2):
 *  - greeting + 2×2 quick action cards (analyze screen / open WeChat / browse
 *    the web / memory pressure), the set configurable from the page itself;
 *  - the SESSION LIST stays the primary surface — this page is reached from its
 *    top entry and offers a one-tap way back;
 *  - it can be set as the launch page ([AssistantHomePrefs.KEY_START_PAGE]),
 *    which changes nothing until the user asks for it.
 *
 * Eta @ c15de97 has no equivalent page (its home screen IS the chat stage, see
 * `ui/screens/home/AgentHomeScreen.kt`), so this is the "补一块" the roadmap
 * calls for, built entirely from this repo's own components and design tokens.
 */
@Composable
fun AssistantHomeEntryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ChatColors.secondaryBg.copy(alpha = 0.6f))
            .border(
                width = 0.5.dp,
                color = ChatColors.separator.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.assistant_home_entry_title),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
            )
            Text(
                text = stringResource(R.string.assistant_home_entry_subtitle),
                fontSize = 12.sp,
                color = ChatColors.secondaryText,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = ChatColors.tertiaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantHomeScreen(
    onOpenSessions: () -> Unit,
    onAnalyzeScreen: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actions by AssistantHomePrefs.actions.collectAsState()
    val startPage by AssistantHomePrefs.startPage.collectAsState()
    var showActionPicker by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var memorySnapshot by remember { mutableStateOf<MemoryPressureSnapshot?>(null) }
    val browserTabPool = remember { BrowserTabPool(context) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .verticalScroll(rememberScrollState())
            .padding(top = topInset, bottom = bottomInset),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.assistant_home_back),
                        tint = ChatColors.primaryText,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(R.string.assistant_home_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(greetingPeriodFor(LocalTime.now().hour).titleRes()),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.primaryText,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.assistant_home_subtitle),
                fontSize = 14.sp,
                color = ChatColors.secondaryText,
            )
            Spacer(Modifier.height(18.dp))

            actions.chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowActions.forEach { action ->
                        QuickActionCard(
                            action = action,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (action) {
                                    AssistantQuickAction.ANALYZE_SCREEN -> onAnalyzeScreen()
                                    AssistantQuickAction.OPEN_WECHAT -> launchWeChat(context)
                                    AssistantQuickAction.BROWSE_WEB -> {
                                        browserTabPool.ensureTabForUI()
                                        showBrowser = true
                                    }
                                    AssistantQuickAction.MEMORY_PRESSURE -> {
                                        memorySnapshot = readMemorySnapshot(context)
                                    }
                                }
                            },
                        )
                    }
                    // Keep a lone card in the left column instead of letting it
                    // stretch across the full width (2×2 grid contract).
                    if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ChatColors.secondaryBg.copy(alpha = 0.6f))
                    .clickable(onClick = onOpenSessions)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_home_open_sessions),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ChatColors.tertiaryText,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.assistant_home_start_page_title),
                subtitle = stringResource(R.string.assistant_home_start_page_subtitle),
                checked = startPage,
                onCheckedChange = { AssistantHomePrefs.setStartPage(context, it) },
                showDivider = false,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showActionPicker = true }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_home_configure_actions),
                    fontSize = 15.sp,
                    color = ChatColors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ChatColors.tertiaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showActionPicker) {
        ModalBottomSheet(
            onDismissRequest = { showActionPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    text = stringResource(R.string.assistant_home_configure_actions),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Text(
                    text = stringResource(R.string.assistant_home_configure_hint),
                    fontSize = 13.sp,
                    color = ChatColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Spacer(Modifier.height(8.dp))
                AssistantQuickAction.DEFAULT_ORDER.forEach { action ->
                    SettingsSwitchRow(
                        title = stringResource(action.titleRes()),
                        subtitle = stringResource(action.subtitleRes()),
                        checked = action in actions,
                        onCheckedChange = {
                            AssistantHomePrefs.setActions(
                                context,
                                AssistantQuickAction.toggle(AssistantHomePrefs.actions.value, action),
                            )
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showBrowser) {
        BrowserSheet(tabPool = browserTabPool, onDismiss = { showBrowser = false })
    }

    memorySnapshot?.let { snapshot ->
        MemoryPressureDialog(snapshot = snapshot, onDismiss = { memorySnapshot = null })
    }
}

/** One 2×2 grid card: icon, title, one-line description. */
@Composable
private fun QuickActionCard(
    action: AssistantQuickAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ChatColors.secondaryBg.copy(alpha = 0.6f))
            .border(
                width = 0.5.dp,
                color = ChatColors.separator.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(action.titleRes()),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(action.subtitleRes()),
            fontSize = 12.sp,
            color = ChatColors.secondaryText,
        )
    }
}

@Composable
private fun MemoryPressureDialog(
    snapshot: MemoryPressureSnapshot,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.assistant_action_memory_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryRow(
                    label = stringResource(R.string.assistant_memory_device_used),
                    value = stringResource(
                        R.string.assistant_memory_device_value,
                        formatMemoryBytes(snapshot.usedBytes),
                        formatMemoryBytes(snapshot.totalBytes),
                        snapshot.usedPercent,
                    ),
                )
                MemoryRow(
                    label = stringResource(R.string.assistant_memory_available),
                    value = formatMemoryBytes(snapshot.availBytes),
                )
                MemoryRow(
                    label = stringResource(R.string.assistant_memory_app_pss),
                    value = formatMemoryBytes(snapshot.appPssBytes),
                )
                Text(
                    text = stringResource(
                        if (snapshot.underPressure) R.string.assistant_memory_pressure_high
                        else R.string.assistant_memory_pressure_normal,
                    ),
                    fontSize = 13.sp,
                    color = if (snapshot.underPressure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        ChatColors.secondaryText
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.assistant_memory_close))
            }
        },
    )
}

@Composable
private fun MemoryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = ChatColors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.primaryText,
        )
    }
}

/**
 * Launch WeChat through its own launcher intent. Absent app → a toast, never a
 * crash and never a silent no-op (the card is the only feedback the user gets).
 */
private fun launchWeChat(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(ASSISTANT_HOME_WECHAT_PACKAGE)
    if (intent == null) {
        Toast.makeText(
            context,
            context.getString(R.string.assistant_action_wechat_missing),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.assistant_action_wechat_missing),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/** Device + app memory reading behind the "memory pressure" card. */
private fun readMemorySnapshot(context: Context): MemoryPressureSnapshot {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val device = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    val app = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    return MemoryPressureSnapshot(
        totalBytes = device.totalMem,
        availBytes = device.availMem,
        thresholdBytes = device.threshold,
        lowMemory = device.lowMemory,
        appPssBytes = app.totalPss.toLong() * 1024L,
    )
}

@Composable
private fun GreetingPeriod.titleRes(): Int = when (this) {
    GreetingPeriod.MORNING -> R.string.assistant_home_greeting_morning
    GreetingPeriod.AFTERNOON -> R.string.assistant_home_greeting_afternoon
    GreetingPeriod.EVENING -> R.string.assistant_home_greeting_evening
    GreetingPeriod.NIGHT -> R.string.assistant_home_greeting_night
}

private fun AssistantQuickAction.titleRes(): Int = when (this) {
    AssistantQuickAction.ANALYZE_SCREEN -> R.string.assistant_action_screen_title
    AssistantQuickAction.OPEN_WECHAT -> R.string.assistant_action_wechat_title
    AssistantQuickAction.BROWSE_WEB -> R.string.assistant_action_web_title
    AssistantQuickAction.MEMORY_PRESSURE -> R.string.assistant_action_memory_title
}

private fun AssistantQuickAction.subtitleRes(): Int = when (this) {
    AssistantQuickAction.ANALYZE_SCREEN -> R.string.assistant_action_screen_subtitle
    AssistantQuickAction.OPEN_WECHAT -> R.string.assistant_action_wechat_subtitle
    AssistantQuickAction.BROWSE_WEB -> R.string.assistant_action_web_subtitle
    AssistantQuickAction.MEMORY_PRESSURE -> R.string.assistant_action_memory_subtitle
}

private fun AssistantQuickAction.icon(): ImageVector = when (this) {
    AssistantQuickAction.ANALYZE_SCREEN -> Icons.Outlined.Visibility
    AssistantQuickAction.OPEN_WECHAT -> Icons.Outlined.ChatBubbleOutline
    AssistantQuickAction.BROWSE_WEB -> Icons.Outlined.Language
    AssistantQuickAction.MEMORY_PRESSURE -> Icons.Default.Memory
}
