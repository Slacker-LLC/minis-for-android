package com.openminis.app.ui.browser

import com.openminis.app.browser.BrowserTabPool.Tab as BrowserTab
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import android.content.Intent
import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserHistoryStore
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.UserAgentProfile
import com.openminis.app.ui.chat.StandardChatSheet
import kotlinx.coroutines.launch

/**
 * Bottom sheet presenting the browser tab pool with tab bar, URL bar,
 * WebView, and navigation controls. Mirrors iOS BrowserSheetView.
 */
@Composable
fun BrowserSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val tabs by tabPool.tabs.collectAsState()
    val selectedTabId by tabPool.selectedTabId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val selectedTab = tabs.find { it.id == selectedTabId }
    val currentURL = selectedTab?.manager?.currentURL?.collectAsState()?.value ?: ""
    val pageTitle = selectedTab?.manager?.pageTitle?.collectAsState()?.value ?: ""
    val isLoading = selectedTab?.manager?.isLoading?.collectAsState()?.value ?: false
    val canGoBack = selectedTab?.manager?.canGoBack?.collectAsState()?.value ?: false
    val canGoForward = selectedTab?.manager?.canGoForward?.collectAsState()?.value ?: false
    val isAgentBusy = tabPool.isAgentBusy
    val userAgentProfile = tabPool.currentUserAgentProfile.collectAsState().value

    var urlInput by remember(currentURL) { mutableStateOf(currentURL) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // [T-android-browser-download-ux] Downloads panel + badge state.
    var showDownloads by remember { mutableStateOf(false) }
    val downloadEntries by tabPool.downloads.collectAsState()

    val accent = MaterialTheme.colorScheme.primary
    val secondaryBg = MaterialTheme.colorScheme.surfaceContainer
    val tertiaryBg = MaterialTheme.colorScheme.surfaceContainerHigh

    // Mirror iOS onAppear: ensure at least one tab exists when the sheet opens.
    // Callers should also call `ensureTabForUI()` before flipping the sheet
    // visible so the first composition sees a non-empty tab list; this is a
    // defensive fallback.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) tabPool.ensureTabForUI()
    }

    // Prevent the bottom sheet from swallowing WebView scroll gestures.
    // Returning the full delta as "consumed" on pre-scroll keeps the sheet's
    // nested-scroll dispatcher from dragging the sheet down when the user
    // scrolls inside a webpage.
    val webViewScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    var showTabs by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val isDesktop = userAgentProfile == UserAgentProfile.DESKTOP_CHROME

    StandardChatSheet(
        title = pageTitle.ifEmpty { stringResource(R.string.browser_title) },
        onDismiss = onDismiss,
        heightFraction = 0.96f,
        header = false,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top row: close, address pill, tab counter (board) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.standard_sheet_close), tint = accent)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(ChatColors.secondaryBg, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrowserAddressBarIcon(isLoading = isLoading, accent = accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            enabled = !isAgentBusy,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val trimmed = urlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val normalized = normalizeURLInput(trimmed)
                                    selectedTab?.manager?.loadURL(normalized)
                                    urlInput = normalized
                                }
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (urlInput.isEmpty()) {
                            Text(
                                stringResource(R.string.browser_search_placeholder),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (isLoading) selectedTab?.manager?.stopLoading() else selectedTab?.manager?.reload() },
                        enabled = !isAgentBusy && selectedTab != null,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = stringResource(if (isLoading) R.string.browser_stop else R.string.browser_reload),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .border(1.5.dp, accent, RoundedCornerShape(8.dp))
                        .clickable { showTabs = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${tabs.size}", color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            // ── Agent banner: while Minis drives the browser the page is read-only for you ──
            if (isAgentBusy) {
                AgentBrowsingBanner(accent = accent, onTakeover = { tabPool.releaseAllTabs() })
            }

            // ── WebView ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(webViewScrollGuard),
            ) {
                if (selectedTab != null && currentURL.isNotEmpty()) {
                    // Only mount the WebView once there's something to display.
                    // Mounting an empty WebView (no URL loaded) corrupts the
                    // OpenGL swap behavior for the hosting ModalBottomSheet —
                    // sibling Compose UI (top nav, URL bar, tab chips) draws
                    // blank white on the first-open case until the WebView
                    // has real content. Keep it out of the hierarchy until
                    // the user navigates somewhere.
                    BrowserWebView(
                        webView = selectedTab.manager.webView,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp).background(ChatColors.secondaryBg, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
                        }
                        Text(
                            if (selectedTab == null) stringResource(R.string.browser_no_tab_open) else stringResource(R.string.browser_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.browser_enter_url_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isAgentBusy) {
                    // Swallow touches so the page cannot be driven by two hands at once.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            },
                    )
                }
            }

            // ── Download progress banner ──
            val activeDownload by tabPool.activeDownload.collectAsState()
            activeDownload?.let { dl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(secondaryBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = accent,
                    )
                    Text(
                        dl.filename,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dl.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    }
                }
            }

            // ── Bottom toolbar: back, forward, share, tabs, more ──
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDesc = stringResource(R.string.browser_nav_back),
                    enabled = canGoBack && !isAgentBusy,
                    tint = accent,
                    onClick = { selectedTab?.manager?.goBack() },
                )
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDesc = stringResource(R.string.browser_nav_forward),
                    enabled = canGoForward && !isAgentBusy,
                    tint = accent,
                    onClick = { selectedTab?.manager?.goForward() },
                )
                ToolbarIcon(
                    icon = Icons.Default.Share,
                    contentDesc = stringResource(R.string.browser_share),
                    enabled = currentURL.isNotEmpty(),
                    tint = accent,
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, currentURL)
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    },
                )
                ToolbarIcon(
                    icon = Icons.Outlined.FilterNone,
                    contentDesc = stringResource(R.string.browser_tab_count_title, tabs.size),
                    enabled = true,
                    tint = accent,
                    onClick = { showTabs = true },
                )
                Box {
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            val badgeCount = tabPool.downloadBadgeCount(downloadEntries)
                            if (badgeCount > 0) androidx.compose.material3.Badge { Text("$badgeCount") }
                        },
                    ) {
                        ToolbarIcon(
                            icon = Icons.Default.MoreHoriz,
                            contentDesc = stringResource(R.string.browser_settings_title),
                            enabled = true,
                            tint = accent,
                            onClick = { showMore = true },
                        )
                    }
                    com.openminis.app.ui.components.MinisMenu(
                        expanded = showMore,
                        onDismissRequest = { showMore = false },
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 0.dp,
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_new_tab)) },
                            enabled = tabs.size < 3 && !isAgentBusy,
                            trailingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = { showMore = false; scope.launch { tabPool.newTabFromUI() } },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_history_action)) },
                            enabled = !isAgentBusy,
                            trailingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            onClick = { showMore = false; showHistory = true },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_downloads_title)) },
                            trailingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            onClick = { showMore = false; showDownloads = true },
                        )
                        com.openminis.app.ui.components.MinisMenuDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_desktop_site)) },
                            trailingIcon = {
                                Icon(
                                    if (isDesktop) Icons.Default.Check else Icons.Default.Language,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                showMore = false
                                tabPool.setUserAgentFromUI(
                                    if (isDesktop) UserAgentProfile.MOBILE_CHROME else UserAgentProfile.DESKTOP_CHROME,
                                )
                                selectedTab?.manager?.reload()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_settings_title)) },
                            trailingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { showMore = false; showSettings = true },
                        )
                    }
                }
            }
        }
    }

    if (showTabs) {
        BrowserTabsDialog(
            tabs = tabs,
            selectedTabId = selectedTabId,
            agentBusy = isAgentBusy,
            onSelect = { tabPool.selectTab(it); showTabs = false },
            onClose = { id -> scope.launch { tabPool.closeTabFromUI(id) } },
            onNew = { scope.launch { tabPool.newTabFromUI() } },
            onDismiss = { showTabs = false },
        )
    }

    if (showDownloads) {
        BrowserDownloadsSheet(
            tabPool = tabPool,
            onDismiss = { showDownloads = false },
        )
    }

    if (showHistory) {
        BrowserHistorySheet(
            historyStore = BrowserHistoryStore.getInstance(context),
            // [T-android-browser-history-no-tab] C1: with no tabs open,
            // selectedTab is null and the old safe-call silently dropped the
            // navigation. Create a tab first (same path as the "+" button);
            // if the pool refuses (MAX_TABS), just dismiss the sheet.
            onNavigate = { url ->
                scope.launch {
                    val tab = selectedTab ?: tabPool.newTabFromUI()
                    tab?.manager?.loadURL(url)
                    showHistory = false
                }
            },
            onDismiss = { showHistory = false },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            tabPool = tabPool,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun TabChip(
    title: String,
    isSelected: Boolean,
    showClose: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isSelected) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = if (isSelected) accent.copy(alpha = 0.4f) else Color.Transparent
    val textColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (showClose) 6.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showClose) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.browser_close_tab),
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = contentDesc,
            modifier = Modifier.size(22.dp),
            tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Globe icon with a spinning arc border when loading. */
@Composable
private fun BrowserAddressBarIcon(isLoading: Boolean, accent: Color) {
    val transition = rememberInfiniteTransition(label = "addrIcon")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "angle",
    )
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isLoading) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(22.dp)
                    .rotate(angle),
                color = accent,
                strokeWidth = 1.5.dp,
            )
        }
    }
}

/** Breathing-light overlay shown when the agent is controlling the browser. */
/** "Minis is browsing · Take over": a tinted banner under the address bar. */
@Composable
private fun AgentBrowsingBanner(accent: Color, onTakeover: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "breathingAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(accent.copy(alpha = breathingAlpha), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.browser_minis_browsing),
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.browser_takeover),
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onTakeover),
        )
    }
}

/** All open tabs as cards (title, domain, close); the open one is outlined. There is no page preview. */
@Composable
private fun BrowserTabsDialog(
    tabs: List<BrowserTab>,
    selectedTabId: Int?,
    agentBusy: Boolean,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
            Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
                Text(
                    stringResource(R.string.browser_tab_count_title, tabs.size),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val title = tab.manager.pageTitle.collectAsState().value
                        val url = tab.manager.currentURL.collectAsState().value
                        val host = try { java.net.URI(url).host } catch (_: Exception) { null }
                        val selected = tab.id == selectedTabId
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    if (selected) 2.dp else 0.5.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { onSelect(tab.id) },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    title.ifEmpty { host ?: "Tab ${tab.id}" },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!agentBusy) {
                                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.browser_close_tab), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(120.dp).background(ChatColors.secondaryBg))
                            Text(
                                host ?: url,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onNew, enabled = tabs.size < 3 && !agentBusy) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browser_new_tab), tint = MaterialTheme.colorScheme.primary)
                    }
                    com.openminis.app.ui.components.MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.browser_settings_done), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Normalize URL input: search terms → Google search, bare domains → https:// prefix. */
private fun normalizeURLInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.contains("://")) return trimmed
    if (trimmed.contains(' ') || !trimmed.contains('.')) {
        return "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
    }
    return "https://$trimmed"
}
