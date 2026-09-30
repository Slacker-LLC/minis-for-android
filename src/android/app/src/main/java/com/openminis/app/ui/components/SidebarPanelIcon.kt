package com.openminis.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "open sidebar" glyph of Claude's apps: a rounded rectangle with a narrow panel split off on
 * the left. Drawn rather than taken from the Material set, whose view-sidebar has the panel on the
 * right.
 */
@Composable
fun SidebarPanelIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = this.size.minDimension * (2f / 24f)
        val left = this.size.width * (3f / 24f)
        val top = this.size.height * (4f / 24f)
        val w = this.size.width * (18f / 24f)
        val h = this.size.height * (16f / 24f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(this.size.minDimension * (3f / 24f)),
            style = Stroke(width = stroke),
        )
        val dividerX = left + w * 0.36f
        drawLine(
            color = tint,
            start = Offset(dividerX, top),
            end = Offset(dividerX, top + h),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
        )
    }
}
