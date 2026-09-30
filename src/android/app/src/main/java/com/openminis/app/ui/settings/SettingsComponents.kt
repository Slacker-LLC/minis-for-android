package com.openminis.app.ui.settings

import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.ui.components.groupedCard

/**
 * Shared primitives for settings pages. Grouped-card layout (iOS/ChatGPT style).
 *
 * Structure:
 *   SettingsScaffold(title, actions?) {
 *     SettingsSection(header?, footer?) { SettingsRow/SwitchRow/ValueRow(...) ... }
 *     SettingsSection(...) { ... }
 *   }
 */

/**
 * [T-android-settings-metrics] Every measurement the settings surface uses, in one place.
 *
 * An audit of these screens found the same kind of row drawn with five horizontal insets
 * (14/16/20/12/6dp), dividers indented by four different amounts (14/16/38/58dp), card corners in
 * nine radii and screen-bottom padding in two sizes. The values below are what the shared
 * primitives use; a screen that needs a different number should be adding a primitive here rather
 * than a literal at the call site.
 *
 * The row inset is 16dp rather than the 14dp the row used to carry: the section card, the header
 * and footer text, the choice rows and the hand-written rows all already sat at 16, so 14 was the
 * single odd value making a row's text and the header above it differ by 2dp.
 */
/**
 * The page colour behind settings cards: a light grey in light mode (cards are white and read as
 * raised), black in dark mode (cards are the raised dark grey).
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun settingsPageBackground(): Color =
    if (ChatColors.isDark) MaterialTheme.colorScheme.background else Color(0xFFF2F2F7)

object SettingsMetrics {
    /** Distance from the screen edge to a section card. */
    val CardMarginHorizontal = 16.dp

    /** Inside the card: the row's own horizontal inset, which dividers also respect. */
    val RowPaddingHorizontal = 16.dp
    val RowPaddingVertical = 12.dp

    /** MD3 single-line list item; a two-line caller passes [RowMinHeightTwoLine] instead. */
    val RowMinHeight = 56.dp
    val RowMinHeightTwoLine = 72.dp

    /** Leading icon chip inside a row, and the gap between it and the title. */
    val IconChipSize = 30.dp
    val IconChipCorner = 8.dp
    val IconGap = 14.dp

    /** How far a divider is pulled in when the row it follows has a leading icon. */
    val DividerInsetWithIcon = RowPaddingHorizontal + IconChipSize + IconGap

    val SectionCorner = 14.dp

    /** Gap between two sections, applied as top padding so the first one is spaced too. */
    val SectionSpacing = 24.dp

    /** Header and footer text align with the card's content: card margin + row inset. */
    val SectionTextInset = CardMarginHorizontal + RowPaddingHorizontal

    /** Bottom breathing room inside the scroll container, owned by [SettingsScaffold]. */
    val ScreenBottomPadding = 24.dp
}

// ─── Scaffold ──────────────────────────────────────────────────────────────────

/**
 * The navigation bar every settings-style page shares (design: iOS-style bar): a back chevron with
 * the label of the page it returns to on the left, the page title centered, actions on the right, and a
 * hairline underneath. A page that shows a large title (see [SettingsScaffold]) leaves the bar's own
 * title empty and has no hairline.
 */
@Composable
fun MinisNavBar(
    title: String,
    onBack: (() -> Unit)?,
    backLabel: String,
    navigation: (@Composable () -> Unit)?,
    actions: (@Composable () -> Unit)?,
    inlineTitle: Boolean,
    background: Color = MaterialTheme.colorScheme.background,
) = MinisNavBar(
    titleSlot = if (inlineTitle) {
        {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        null
    },
    onBack = onBack,
    backLabel = backLabel,
    navigation = navigation,
    actions = actions,
    background = background,
)

/** Same bar with a composable title (two-line titles, tabs...). A null [titleSlot] leaves it empty. */
@Composable
fun MinisNavBar(
    titleSlot: (@Composable () -> Unit)?,
    onBack: (() -> Unit)?,
    backLabel: String,
    navigation: (@Composable () -> Unit)?,
    actions: (@Composable () -> Unit)?,
    background: Color = MaterialTheme.colorScheme.background,
) {
    val inlineTitle = titleSlot != null
    Column(modifier = Modifier.fillMaxWidth().background(background).statusBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Row(modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    navigation != null -> navigation()
                    onBack != null -> Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onBack)
                            .heightIn(min = 44.dp)
                            .padding(start = 4.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.back),
                            // Neutral, not the accent: the accent is for things to act on, not for chrome.
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(30.dp),
                        )
                        Text(
                            backLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 78.dp),
                        )
                    }
                }
            }
            if (titleSlot != null) {
                Box(modifier = Modifier.align(Alignment.Center).padding(horizontal = 122.dp)) { titleSlot() }
            }
            Row(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                actions?.invoke()
            }
        }
        if (inlineTitle) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Drop-in for Material's TopAppBar on pages that run their own Scaffold: the same title, back and
 * actions slots, drawn as the board's bar (chevron + "Back", centered title, hairline).
 */
@Composable
fun MinisTopBar(
    title: @Composable () -> Unit,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    MinisNavBar(
        titleSlot = title,
        onBack = onBack,
        backLabel = backLabel ?: stringResource(R.string.back),
        navigation = navigation,
        actions = { Row(verticalAlignment = Alignment.CenterVertically) { actions() } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    // [T-android-settings-ui-md3] #11 onBack is nullable so an EDIT screen can
    // suppress the back arrow and use an explicit Cancel/Save action pair instead
    // (a back arrow + a "Cancel" action that both pop the screen is redundant and
    // semantically muddy). Non-edit screens keep passing a non-null onBack and get
    // the usual back button.
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    // [T-android-modeldetail-savecancel-ios-parity] Optional custom
    // navigation slot — e.g. a leading Cancel text action on modal-style
    // edit screens. When null, the slot falls back to the back button iff
    // onBack is set, so every existing caller renders unchanged.
    navigation: @Composable (() -> Unit)? = null,
    // Kept for source compatibility: every title is centered in the iOS-style bar now.
    @Suppress("UNUSED_PARAMETER") centerTitle: Boolean = false,
    floatingActionButton: @Composable (() -> Unit)? = null,
    /** A bar pinned under the scrolling content (e.g. the team member page's Continue / New topic). */
    bottomBar: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    /** Name of the page the back button returns to; "Back" when the caller does not know it. */
    backLabel: String? = null,
    /** Top-level pages (Settings, its categories, Files) show the title large under the bar. */
    largeTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            MinisNavBar(
                title = title,
                onBack = onBack,
                backLabel = backLabel ?: stringResource(R.string.back),
                navigation = navigation,
                actions = actions,
                inlineTitle = !largeTitle,
                background = settingsPageBackground(),
            )
        },
        floatingActionButton = { floatingActionButton?.invoke() },
        bottomBar = { bottomBar?.invoke() },
        containerColor = settingsPageBackground(),
    ) { padding ->
        // T183: imePadding() shrinks the scroll container by the IME's
        // height while the keyboard is up, giving Modifier.bringIntoView()
        // (used by `bringIntoViewOnFocus`) a meaningful "above the
        // keyboard" rect to scroll a focused TextField into. Without it,
        // adjustResize + edge-to-edge leaves the scrollable column at
        // full height behind the IME and bringIntoView is a no-op.
        val baseMod = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
        Column(
            modifier = if (scrollable) baseMod.verticalScroll(rememberScrollState()) else baseMod,
        ) {
            if (largeTitle) {
                Text(
                    text = title,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = SettingsMetrics.CardMarginHorizontal + 4.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                )
            }
            content()
            // [T-android-settings-metrics] The scroll container owns the bottom breathing room;
            // screens used to add 24 or 32dp of their own, and the ones that forgot ended flush
            // against the navigation bar.
            if (scrollable) Spacer(Modifier.height(SettingsMetrics.ScreenBottomPadding))
        }
    }
}

// ─── Switch ────────────────────────────────────────────────────────────────────

/** The app's switch (board look). Use this instead of Material's Switch anywhere outside a settings row. */
@Composable
fun MinisSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.SwitchColors = minisSwitchColors(),
) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, colors = colors)
}


/** The board's switch: iOS green when on, the fill grey when off, a white thumb either way. */
@Composable
fun minisSwitchColors(): androidx.compose.material3.SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = if (ChatColors.isDark) Color(0xFF30D158) else Color(0xFF34C759),
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = if (ChatColors.isDark) Color(0xFF39393D) else Color(0xFFE5E5EA),
    uncheckedBorderColor = Color.Transparent,
)

/**
 * A segmented control (design: theme, launch target, schedule type...). One row of labels on the fill
 * grey; the selected one sits on a raised white pill. Text only, no icons.
 */
@Composable
fun SettingsSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = if (ChatColors.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val raised = if (ChatColors.isDark) Color(0xFF636366) else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(track)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val pill = RoundedCornerShape(7.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (selected) Modifier.shadow(1.dp, pill).background(raised, pill) else Modifier)
                    .clip(pill)
                    .clickable(role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/**
 * Match the upstream settings-row switch measurement. The whole row is the
 * touch target, so the switch does not need to impose Material's extra
 * 48dp minimum on the surrounding row.
 */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.SwitchColors = minisSwitchColors(),
) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
        )
    }
}

// ─── Section ───────────────────────────────────────────────────────────────────

/**
 * A grouped section — optional small-caps header + rounded card + optional footer caption.
 * Children (SettingsRow / SettingsSwitchRow / …) appear inside the card; dividers auto-inset.
 */
@Composable
fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // [T-android-settings-ui-md3] Section vertical rhythm normalized to the 4dp
    // grid (fix_android_settings_ui.md #13): 24dp between sections instead of the
    // off-grid 20dp. Applied as top padding so the first section under a TopAppBar
    // keeps a consistent gap too.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = SettingsMetrics.SectionSpacing),
    ) {
        if (header != null) {
            Text(
                text = header.uppercaseForDisplay(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                // [T-android-settings-ui-md3] #5 header→card gap = 8dp (was 6dp,
                // off-grid). Horizontal stays 32dp to align the header text with
                // the inset card's content.
                modifier = Modifier.padding(
                    start = SettingsMetrics.SectionTextInset,
                    end = SettingsMetrics.SectionTextInset,
                    bottom = 8.dp,
                ),
            )
        }
        // [T-android-settings-section-symmetry] The card carries NO vertical
        // padding of its own: rows already supply 12dp top AND bottom, so any
        // tail here makes the gap below the content read larger than the gap
        // above it. An unconditional 8dp bottom pad used to live on this
        // Column to stop a trailing divider sitting flush against the rounded
        // edge — but every section either suppresses its last divider
        // (showDivider = false, or an `index < size - 1` guard) or follows it
        // with more content, so nothing actually needed the clearance. The
        // asymmetry it cost was invisible in a tall multi-row card and glaring
        // in a single-row one ("Status / Enabled", "Voice Services", the
        // thinking-rules note).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsMetrics.CardMarginHorizontal)
                .groupedCard(RoundedCornerShape(SettingsMetrics.SectionCorner)),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // [T-android-settings-ui-md3] #6 explanatory footer: 8dp below the
                // card, 4dp before the next section (the parent's 24dp top padding
                // already provides separation, so keep the footer's own bottom
                // tight at 4dp).
                modifier = Modifier.padding(
                    start = SettingsMetrics.SectionTextInset,
                    end = SettingsMetrics.SectionTextInset,
                    top = 8.dp,
                    bottom = 4.dp,
                ),
                lineHeight = 16.sp,
            )
        }
    }
}

// ─── Row primitives ────────────────────────────────────────────────────────────

/**
 * Generic row: left icon (optional colored circle) + title/subtitle + trailing slot + optional chevron.
 * Pass `showDivider = false` on the last row of a section.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    // [T-android-settings-ui-md3] #8 single-line List Item is 56dp; a caller with
    // two-line content (e.g. the model list: name + id) passes 72dp for the MD3
    // double-line height. Default keeps every other row at the single-line 56dp.
    minHeight: Dp = SettingsMetrics.RowMinHeight,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // [T-android-settings-ui-md3] #1/#8 fixed List Item height so every
                // toggle/value/nav row in a section is uniform (was content-driven
                // → 24/54/72dp mix). heightIn(min) not height() so an unexpectedly
                // tall row can still grow; the symmetric 12dp vertical padding (was
                // effectively asymmetric once the 0.5dp divider was added/removed)
                // is what made a no-subtitle last row read ~50px shorter.
                .heightIn(min = minHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(
                    horizontal = SettingsMetrics.RowPaddingHorizontal,
                    vertical = SettingsMetrics.RowPaddingVertical,
                ),
            // #10 keep the trailing control (Switch/value) vertically centered
            // against the title — already centered, kept explicit.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(SettingsMetrics.IconChipSize)
                        .background(iconColor, RoundedCornerShape(SettingsMetrics.IconChipCorner)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(SettingsMetrics.IconGap))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }

            if (showChevron) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (showDivider) {
            SettingsDivider(
                insetStart = if (icon != null) {
                    SettingsMetrics.DividerInsetWithIcon
                } else {
                    SettingsMetrics.RowPaddingHorizontal
                },
            )
        }
    }
}

/**
 * [T-android-settings-metrics] The one divider every settings list uses.
 *
 * It used to be spelled out per primitive with four different start insets (14 for a plain row, 58
 * with an icon, 16 in a choice row, 14/38 elsewhere) and an end inset that stopped 2dp short of the
 * card's content edge, which is what made a list of mixed row types look misaligned.
 */
@Composable
internal fun SettingsDivider(insetStart: Dp = SettingsMetrics.RowPaddingHorizontal) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = insetStart, end = SettingsMetrics.RowPaddingHorizontal)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** Title + Switch row. */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            SettingsSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/** Title + right-aligned value text (tap opens picker/detail). */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showChevron = onClick != null,
        showDivider = showDivider,
        trailing = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** Single-choice row (tap to select; shows check on selected). Used for radio-style lists. */
@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // [T-android-settings-ui-md3] #1 match SettingsRow's 56dp min so
                // choice/radio rows line up with toggle/value rows in mixed lists.
                .heightIn(min = SettingsMetrics.RowMinHeight)
                .clickable(onClick = onSelect)
                .padding(
                    horizontal = SettingsMetrics.RowPaddingHorizontal,
                    vertical = SettingsMetrics.RowPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.common_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            SettingsDivider()
        }
    }
}

/**
 * Container for non-row content (sliders, segmented pickers, custom composables)
 * that still wants the grouped-card background.
 */
@Composable
fun SettingsCardBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(
                horizontal = SettingsMetrics.CardMarginHorizontal,
                vertical = SettingsMetrics.RowPaddingVertical,
            )
            .fillMaxWidth(),
        content = content,
    )
}


// ─── Text input ────────────────────────────────────────────────────────────────

/** The board's search field: filled grey pill with a magnifier, no border. */
@Composable
fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(ChatColors.secondaryBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** A settings row whose value is typed in place: label on the left, editable text on the right. */
@Composable
fun SettingsInlineTextRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    showDivider: Boolean = true,
    numeric: Boolean = false,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        showDivider = showDivider,
        trailing = {
            Box(modifier = modifier.widthIn(min = if (numeric) 56.dp else 80.dp, max = if (numeric) 96.dp else 220.dp), contentAlignment = Alignment.CenterEnd) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    keyboardOptions = if (numeric) {
                        androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    } else {
                        androidx.compose.foundation.text.KeyboardOptions.Default
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        },
    )
}

/** A borderless multi-line text area that sits inside a settings card. */
@Composable
fun SettingsTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    monospace: Boolean = false,
    minHeight: Dp = 160.dp,
    maxHeight: Dp = Dp.Unspecified,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .padding(horizontal = SettingsMetrics.RowPaddingHorizontal, vertical = 12.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty() && placeholder != null) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
