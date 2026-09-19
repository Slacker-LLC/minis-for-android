package com.openminis.app.ui.settings

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    // [T-android-settings-ui-md3] #11 onBack is nullable so an EDIT screen can
    // suppress the back arrow and use an explicit Cancel/Save action pair instead
    // (a back arrow + a "Cancel" action that both pop the screen is redundant and
    // semantically muddy). Non-edit screens keep passing a non-null onBack and get
    // the usual back arrow — unchanged.
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    // [T-android-modeldetail-savecancel-ios-parity] Optional custom
    // navigation slot — e.g. a leading Cancel text action on modal-style
    // edit screens. When null, the slot falls back to the back arrow iff
    // onBack is set, so every existing caller renders unchanged.
    navigation: @Composable (() -> Unit)? = null,
    // [T-android-modeldetail-savecancel-ios-parity] Center the title
    // (CenterAlignedTopAppBar) for iOS-modal-style edit screens. Default
    // keeps the start-aligned TopAppBar.
    centerTitle: Boolean = false,
    floatingActionButton: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            val titleSlot: @Composable () -> Unit = {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val navigationSlot: @Composable () -> Unit = {
                when {
                    navigation != null -> navigation()
                    onBack != null -> IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            }
            if (centerTitle) {
                CenterAlignedTopAppBar(
                    title = titleSlot,
                    navigationIcon = navigationSlot,
                    actions = { actions?.invoke() },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            } else {
                TopAppBar(
                    title = titleSlot,
                    navigationIcon = navigationSlot,
                    actions = { actions?.invoke() },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        floatingActionButton = { floatingActionButton?.invoke() },
        containerColor = MaterialTheme.colorScheme.background,
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
            content()
            // [T-android-settings-metrics] The scroll container owns the bottom breathing room;
            // screens used to add 24 or 32dp of their own, and the ones that forgot ended flush
            // against the navigation bar.
            if (scrollable) Spacer(Modifier.height(SettingsMetrics.ScreenBottomPadding))
        }
    }
}

// ─── Switch ────────────────────────────────────────────────────────────────────

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
    colors: androidx.compose.material3.SwitchColors = SwitchDefaults.colors(),
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
                .clip(RoundedCornerShape(SettingsMetrics.SectionCorner))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
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
                colors = SwitchDefaults.colors(),
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
