package com.openminis.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisOverlayScrim
import com.openminis.app.ui.theme.minisSheetColor

/**
 * Bottom sheet shell (docs/design/UI-DESIGN-LANGUAGE.md §7): opaque page-colour
 * container, light scrim (black 18% / 55% dark), Material's 28dp top corners
 * from the app shapes. Call sites that need the glass treatment pass
 * [containerColor] themselves; nothing else about the look is overridable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinisModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = minisSheetColor(),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        scrimColor = minisOverlayScrim(ChatColors.isDark),
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}
