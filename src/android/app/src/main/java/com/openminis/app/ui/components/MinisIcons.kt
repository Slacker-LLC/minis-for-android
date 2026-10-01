package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's line icons, one family: 24 x 24, 2 px stroke, round joins and caps, no fill, the way the
 * sidebar-panel glyph is drawn. Material's filled icons next to these read as a different set (heavier,
 * different corners), so chat chrome, the drawer and the reply actions use these instead. Tint them through
 * `Icon(tint = ...)`.
 */
object MinisIcons {
    private const val STROKE = 2f

    private fun line(name: String, filledDots: Boolean = false, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(
                fill = if (filledDots) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = build,
            )
            .build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        horizontalLineTo(x + w - r)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        verticalLineTo(y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        horizontalLineTo(x + r)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        verticalLineTo(y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    val Copy: ImageVector by lazy {
        line("copy") {
            roundRect(9f, 9f, 11f, 11f, 2.5f)
            moveTo(15f, 9f)
            verticalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 12.5f, 4f)
            horizontalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 4f, 6.5f)
            verticalLineTo(12.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 6.5f, 15f)
            horizontalLineTo(9f)
        }
    }

    val Check: ImageVector by lazy {
        line("check") {
            moveTo(5f, 12.5f)
            lineTo(9.8f, 17.3f)
            lineTo(19f, 7.5f)
        }
    }

    val Refresh: ImageVector by lazy {
        line("refresh") {
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, true, true, 17.6f, 6.3f)
            moveTo(20f, 4.5f)
            verticalLineTo(9f)
            horizontalLineTo(15.5f)
        }
    }

    val Volume: ImageVector by lazy {
        line("volume") {
            moveTo(11f, 5f)
            lineTo(6.5f, 9f)
            horizontalLineTo(3.5f)
            verticalLineTo(15f)
            horizontalLineTo(6.5f)
            lineTo(11f, 19f)
            close()
            moveTo(15.5f, 8.8f)
            arcTo(4.6f, 4.6f, 0f, false, true, 15.5f, 15.2f)
            moveTo(18.6f, 5.8f)
            arcTo(8.8f, 8.8f, 0f, false, true, 18.6f, 18.2f)
        }
    }

    val StopCircle: ImageVector by lazy {
        line("stop") {
            circle(12f, 12f, 9f)
            roundRect(9f, 9f, 6f, 6f, 1.2f)
        }
    }

    val Branch: ImageVector by lazy {
        line("branch") {
            circle(6f, 5.5f, 2.4f)
            circle(6f, 18.5f, 2.4f)
            circle(18f, 8f, 2.4f)
            moveTo(6f, 7.9f)
            verticalLineTo(16.1f)
            moveTo(18f, 10.4f)
            curveTo(18f, 14.6f, 12.5f, 13.4f, 7.6f, 16.9f)
        }
    }

    /** Three dots: filled, so the fill colour is the tint too. */
    val More: ImageVector by lazy {
        line("more", filledDots = true) {
            circle(5f, 12f, 1.3f)
            circle(12f, 12f, 1.3f)
            circle(19f, 12f, 1.3f)
        }
    }

    val Share: ImageVector by lazy {
        line("share") {
            moveTo(12f, 15f)
            verticalLineTo(4f)
            moveTo(8f, 8f)
            lineTo(12f, 4f)
            lineTo(16f, 8f)
            moveTo(5f, 13f)
            verticalLineTo(17.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 7.5f, 20f)
            horizontalLineTo(16.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 19f, 17.5f)
            verticalLineTo(13f)
        }
    }

    val Trash: ImageVector by lazy {
        line("trash") {
            moveTo(4f, 7f)
            horizontalLineTo(20f)
            moveTo(9f, 7f)
            verticalLineTo(5f)
            arcTo(1f, 1f, 0f, false, true, 10f, 4f)
            horizontalLineTo(14f)
            arcTo(1f, 1f, 0f, false, true, 15f, 5f)
            verticalLineTo(7f)
            moveTo(6f, 7f)
            lineTo(7f, 18.5f)
            arcTo(1.6f, 1.6f, 0f, false, false, 8.6f, 20f)
            horizontalLineTo(15.4f)
            arcTo(1.6f, 1.6f, 0f, false, false, 17f, 18.5f)
            lineTo(18f, 7f)
            moveTo(10f, 11f)
            verticalLineTo(16f)
            moveTo(14f, 11f)
            verticalLineTo(16f)
        }
    }

    /** New chat: a square with a pencil leaving its corner. */
    val Compose: ImageVector by lazy {
        line("compose") {
            moveTo(11f, 4f)
            horizontalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 4f, 6.5f)
            verticalLineTo(17.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 6.5f, 20f)
            horizontalLineTo(17.5f)
            arcTo(2.5f, 2.5f, 0f, false, false, 20f, 17.5f)
            verticalLineTo(13f)
            moveTo(16.5f, 4.5f)
            lineTo(19.5f, 7.5f)
            moveTo(18f, 3f)
            arcTo(2.1f, 2.1f, 0f, false, true, 21f, 6f)
            lineTo(12f, 15f)
            lineTo(8f, 16f)
            lineTo(9f, 12f)
            close()
        }
    }

    val Users: ImageVector by lazy {
        line("users") {
            circle(9.5f, 8f, 3.5f)
            moveTo(3.5f, 20f)
            verticalLineTo(18.6f)
            arcTo(3.6f, 3.6f, 0f, false, true, 7.1f, 15f)
            horizontalLineTo(11.9f)
            arcTo(3.6f, 3.6f, 0f, false, true, 15.5f, 18.6f)
            verticalLineTo(20f)
            moveTo(15.8f, 4.7f)
            arcTo(3.5f, 3.5f, 0f, false, true, 15.8f, 11.3f)
            moveTo(20.5f, 20f)
            verticalLineTo(18.6f)
            arcTo(3.6f, 3.6f, 0f, false, false, 18f, 15.2f)
        }
    }

    val Clock: ImageVector by lazy {
        line("clock") {
            circle(12f, 12f, 9f)
            moveTo(12f, 7f)
            verticalLineTo(12f)
            lineTo(15.2f, 14f)
        }
    }

    val Terminal: ImageVector by lazy {
        line("terminal") {
            roundRect(3f, 4.5f, 18f, 15f, 3f)
            moveTo(7.5f, 9.8f)
            lineTo(10.5f, 12.3f)
            lineTo(7.5f, 14.8f)
            moveTo(13f, 15f)
            horizontalLineTo(16.5f)
        }
    }

    val Search: ImageVector by lazy {
        line("search") {
            circle(11f, 11f, 6.5f)
            moveTo(16f, 16f)
            lineTo(20.5f, 20.5f)
        }
    }

    /** A cog: eight flat-topped teeth around a ring, generated so the teeth stay even. */
    val Settings: ImageVector by lazy {
        line("settings") {
            val teeth = 8
            val outer = 9.2f
            val inner = 7.0f
            val step = (2 * PI / teeth).toFloat()
            val half = step / 2f
            val tooth = step * 0.28f
            for (i in 0 until teeth) {
                val a = i * step
                val points = listOf(
                    a - half + tooth * 0.4f to inner,
                    a - tooth to outer,
                    a + tooth to outer,
                    a + half - tooth * 0.4f to inner,
                )
                points.forEachIndexed { index, (angle, radius) ->
                    val x = 12f + radius * cos(angle - (PI / 2).toFloat())
                    val y = 12f + radius * sin(angle - (PI / 2).toFloat())
                    if (i == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            circle(12f, 12f, 2.8f)
        }
    }

    val Globe: ImageVector by lazy {
        line("globe") {
            circle(12f, 12f, 9f)
            moveTo(3f, 12f)
            horizontalLineTo(21f)
            moveTo(12f, 3f)
            arcTo(4.6f, 9f, 0f, false, true, 12f, 21f)
            arcTo(4.6f, 9f, 0f, false, true, 12f, 3f)
        }
    }

    val Folder: ImageVector by lazy {
        line("folder") {
            moveTo(3.5f, 7.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6f, 5f)
            horizontalLineTo(9.2f)
            lineTo(11.2f, 7.5f)
            horizontalLineTo(18f)
            arcTo(2.5f, 2.5f, 0f, false, true, 20.5f, 10f)
            verticalLineTo(17.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 18f, 20f)
            horizontalLineTo(6f)
            arcTo(2.5f, 2.5f, 0f, false, true, 3.5f, 17.5f)
            close()
        }
    }

    val Eye: ImageVector by lazy {
        line("eye") {
            moveTo(2.5f, 12f)
            curveTo(4.5f, 8f, 8f, 6f, 12f, 6f)
            curveTo(16f, 6f, 19.5f, 8f, 21.5f, 12f)
            curveTo(19.5f, 16f, 16f, 18f, 12f, 18f)
            curveTo(8f, 18f, 4.5f, 16f, 2.5f, 12f)
            close()
            circle(12f, 12f, 2.8f)
        }
    }

    val Close: ImageVector by lazy {
        line("close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    val Code: ImageVector by lazy {
        line("code") {
            moveTo(8.5f, 7f)
            lineTo(3.5f, 12f)
            lineTo(8.5f, 17f)
            moveTo(15.5f, 7f)
            lineTo(20.5f, 12f)
            lineTo(15.5f, 17f)
        }
    }

    val TextSelect: ImageVector by lazy {
        line("text-select") {
            moveTo(9f, 4f)
            horizontalLineTo(15f)
            moveTo(9f, 20f)
            horizontalLineTo(15f)
            moveTo(12f, 4f)
            verticalLineTo(20f)
        }
    }
}
