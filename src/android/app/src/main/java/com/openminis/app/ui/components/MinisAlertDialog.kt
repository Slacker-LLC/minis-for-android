package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.glass.minisGlassBlurAvailable
import com.openminis.app.ui.glass.minisGlassScrim
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle

/**
 * App-wide confirmation dialog. Refined milky-white card styling with
 * a guaranteed semi-transparent scrim mask backdrop across all devices.
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
     * the buttons stack vertically instead of sitting in a row.
     */
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Full-screen backdrop mask: guarantees a visible dark scrim across all OEM ROMs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            GlassSheetWindowBlur(radius = 40.dp)
            val isGlass = LocalUiStyle.current == UiStyle.GLASS
            val blurredGlass = isGlass && minisGlassBlurAvailable()
            val dialogShape = RoundedCornerShape(18.dp)

            Surface(
                modifier = (if (isGlass && !blurredGlass) {
                    Modifier.glassSheetSurface(dialogShape)
                } else {
                    Modifier
                })
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}, // Prevent taps on dialog from dismissing
                    ),
                shape = dialogShape,
                color = when {
                    blurredGlass -> minisGlassScrim().copy(alpha = 0.85f)
                    isGlass -> Color.Transparent
                    ChatColors.isDark -> MaterialTheme.colorScheme.surface
                    else -> Color(0xFFFAF9F6)
                },
                tonalElevation = 0.dp,
                border = if (!isGlass) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                shadowElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp,
                        ),
                        color = ChatColors.primaryText,
                    )
                    if (text != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.5.sp,
                                lineHeight = 21.sp,
                            ),
                            color = ChatColors.secondaryText,
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    val confirmColor = if (isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    if (neutralText != null && onNeutral != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End,
                        ) {
                            MinisTextButton(onClick = onDismiss) {
                                Text(dismissText, color = ChatColors.secondaryText, fontSize = 14.sp)
                            }
                            MinisTextButton(onClick = onConfirm) {
                                Text(text = confirmText, color = confirmColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                            MinisTextButton(onClick = onNeutral) {
                                Text(text = neutralText, color = confirmColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MinisTextButton(onClick = onDismiss) {
                                Text(dismissText, color = ChatColors.secondaryText, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            MinisTextButton(onClick = onConfirm) {
                                Text(text = confirmText, color = confirmColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
