package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.ChatColors

// UI design language (docs/design/UI-DESIGN-LANGUAGE.md §6): every action is a
// text button. There are no filled, pill, tinted or outlined buttons.
//
//   normal      accent text, regular weight   -> MinisOutlinedButton / MinisTextButton
//   primary     accent text, semi-bold        -> MinisButton (at most one per page)
//   destructive error text                    -> pass destructive = true
//
// The filled/outlined-named variants are kept so call sites don't churn; they
// render as text buttons and deliberately expose no colour/shape/border/
// elevation overrides. A source guard test (MinisButtonUsageGuardTest) rejects
// the Material Button / OutlinedButton / FilledTonalButton / ElevatedButton
// composables anywhere outside this file.

// Material3 ButtonDefaults.MinHeight = 40dp; tuned to 48dp for touch
// ergonomics on phones. IconButton family is unaffected (icon-sized).
val MinisButtonHeight = 48.dp

// Compact button height for actions embedded inside section cards
// (e.g. "Sign out" inside a credentials card, "Set Bearer Token" inside
// a token section). Visually subordinate to MinisButtonHeight (48dp)
// which remains the size for primary screen actions ("Add Custom Model",
// TopAppBar Save, AlertDialog confirm).
val MinisSmallButtonHeight = 32.dp

private val SmallButtonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)

@Composable
private fun minisActionColors(destructive: Boolean): ButtonColors {
    val content = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    return ButtonDefaults.textButtonColors(
        contentColor = content,
        disabledContentColor = ChatColors.secondaryText.copy(alpha = 0.4f),
    )
}

@Composable
private fun MinisActionButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    destructive: Boolean,
    emphasized: Boolean,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = minisActionColors(destructive),
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
    ) {
        ProvideTextStyle(
            LocalTextStyle.current.copy(
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            ),
        ) {
            content()
        }
    }
}

/** Primary action: accent text, semi-bold. At most one per page or dialog. */
@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisActionButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = MinisButtonHeight),
    enabled = enabled,
    destructive = destructive,
    emphasized = true,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

/** Normal action: accent text, regular weight. */
@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisActionButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = MinisButtonHeight),
    enabled = enabled,
    destructive = destructive,
    emphasized = false,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

// defaultMinSize is also pinned at MinisSmallButtonHeight so Material3's
// internal 40dp floor (ButtonDefaults.MinHeight) doesn't override the
// heightIn modifier and keep the button at 40dp.
/** Compact primary action: accent text, semi-bold. */
@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisActionButton(
    onClick = onClick,
    modifier = modifier
        .heightIn(min = MinisSmallButtonHeight)
        .defaultMinSize(minHeight = MinisSmallButtonHeight),
    enabled = enabled,
    destructive = destructive,
    emphasized = true,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

/** Compact normal action: accent text, regular weight. */
@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisActionButton(
    onClick = onClick,
    modifier = modifier
        .heightIn(min = MinisSmallButtonHeight)
        .defaultMinSize(minHeight = MinisSmallButtonHeight),
    enabled = enabled,
    destructive = destructive,
    emphasized = false,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}
