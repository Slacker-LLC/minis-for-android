package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.glass.minisGlassBlurAvailable
import com.openminis.app.ui.glass.minisGlassScrim
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import com.openminis.app.ui.theme.minisOverlayScrim

// UI design language §7 (docs/design/UI-DESIGN-LANGUAGE.md): alert = centred, opaque
// white, 14dp corners; title + optional message, then text-only actions separated
// by hairlines (side by side for two, stacked for three); destructive in red.

private val DialogActionHeight = 48.dp

/**
 * Shared dialog shell: full-screen scrim, centred opaque card, content column.
 * [content] is laid out inside the card; the caller adds its own actions.
 */
@Composable
private fun MinisDialogShell(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Full-screen backdrop mask: guarantees a visible scrim across all OEM ROMs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(minisOverlayScrim(ChatColors.isDark))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (properties.dismissOnClickOutside) onDismissRequest() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            GlassSheetWindowBlur(radius = 40.dp)
            val isGlass = LocalUiStyle.current == UiStyle.GLASS
            val blurredGlass = isGlass && minisGlassBlurAvailable()
            val dialogShape = RoundedCornerShape(14.dp)
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

            Surface(
                modifier = (if (isGlass && !blurredGlass) {
                    Modifier.glassSheetSurface(dialogShape)
                } else {
                    Modifier
                })
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .heightIn(max = maxHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}, // Prevent taps on dialog from dismissing
                    ),
                shape = dialogShape,
                color = when {
                    blurredGlass -> minisGlassScrim().copy(alpha = 0.85f)
                    isGlass -> Color.Transparent
                    else -> MaterialTheme.colorScheme.surface
                },
                tonalElevation = 0.dp,
                border = if (!isGlass) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                shadowElevation = 10.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

@Composable
private fun DialogHairline() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DialogActionLabel(
    text: String,
    color: Color,
    bold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MinisTextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = DialogActionHeight),
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * App-wide confirmation dialog (design language §7). Opaque white card over a
 * light scrim; actions are text-only.
 */
@Composable
fun MinisAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismissText: String = stringResource(R.string.cancel),
    isDestructive: Boolean = false,
    onDismiss: () -> Unit = onDismissRequest,
    /**
     * Optional third action, rendered between dismiss and confirm. When set,
     * the buttons stack vertically instead of sitting side by side.
     */
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    MinisDialogShell(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                ),
                color = ChatColors.primaryText,
                textAlign = TextAlign.Center,
            )
            if (text != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    color = ChatColors.secondaryText,
                    textAlign = TextAlign.Center,
                )
            }
        }
        val accent = MaterialTheme.colorScheme.primary
        val confirmColor = if (isDestructive) MaterialTheme.colorScheme.error else accent
        DialogHairline()
        if (neutralText != null && onNeutral != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                DialogActionLabel(confirmText, confirmColor, bold = true, onClick = onConfirm, modifier = Modifier.fillMaxWidth())
                DialogHairline()
                DialogActionLabel(neutralText, accent, bold = false, onClick = onNeutral, modifier = Modifier.fillMaxWidth())
                DialogHairline()
                DialogActionLabel(dismissText, accent, bold = false, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                DialogActionLabel(dismissText, accent, bold = false, onClick = onDismiss, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                DialogActionLabel(confirmText, confirmColor, bold = true, onClick = onConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Slot-based overload with the same shape as Material3's `AlertDialog`, for
 * dialogs whose title/body/actions are composable content (text fields, lists,
 * progress). It exists so those call sites share the design-language shell
 * instead of the Material default; it deliberately offers no colour, shape or
 * elevation overrides. Put text buttons (`MinisTextButton` / `MinisButton`) in
 * [confirmButton] and [dismissButton]; the confirm slot sits on the right.
 */
@Composable
fun MinisAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    MinisDialogShell(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        if (title != null || text != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
            ) {
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides ChatColors.primaryText) {
                        ProvideTextStyle(
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                            ),
                        ) { title() }
                    }
                }
                if (text != null) {
                    if (title != null) Spacer(modifier = Modifier.height(8.dp))
                    CompositionLocalProvider(LocalContentColor provides ChatColors.secondaryText) {
                        ProvideTextStyle(
                            MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        ) { text() }
                    }
                }
            }
        }
        DialogHairline()
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dismissButton != null) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { dismissButton() }
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { confirmButton() }
        }
    }
}
