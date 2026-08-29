package io.github.slackerllc.minis.voicecall.bloub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.slackerllc.minis.voicecall.model.BloubState
import kotlin.math.min

/**
 * Bloub 渲染器 —— 忠实还原项目提供的 Bloub SVG 各状态。
 *
 * 所有状态：近圆绿色主体 #3ecf8e、白色眼、无嘴、透明背景。
 * 各状态仅通过“眼睛的几何/表情”区分，几何数据取自 assets/moods 下的 SVG 表情文件与 transform：
 *  - LISTENING(attentif) ：两只竖直胶囊眼，位于上部、彼此靠近。
 *  - THINKING(curieux)   ：眼睛上飘/倾斜（思考眼神）。
 *  - SPEAKING(excite)    ：大而宽的椭圆眼（兴奋）。
 *  - MUTED(somnolent)    ：眼睛压扁至 45% 高度（困倦）。
 *  - CONFUSED(confus)    ：一眼竖直、一眼横置并倾斜（没听清/疑惑）。
 *  - SUCCESS(fier)       ：两只横置短胖眼、抬高（得意/弯月 ^^）。
 *
 * 仅保留“角色本体”，不加渐变/玻璃/3D 质感；保留轻微漂浮 + 轻柔眨眼（部分状态不眨眼）。
 *
 * 实现要点：
 * - 不用 Compose `Modifier.shadow()`（在当前「kyant0 backdrop」窗口下会渲染成白色八边形遮挡层），
 *   改用前景 Canvas 绘制。
 * - 直接在画布中心绘制，避免 `withTransform(translate+scale)` 顺序导致球体偏到盒子角落。
 * - 尺寸无关：以盒子短边为基准按 viewBox(100) 归一化，LARGE/SMALL/MEDIUM/FLOATING 共用。
 * - 上层接口 BloubAvatar(state, size) 不变。
 */
@Composable
internal fun BloubRenderer(state: BloubState, modifier: Modifier = Modifier) {
    val face = faceFor(state)
    val transition = rememberInfiniteTransition(label = "bloub")
    val bob by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2250, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob"
    )
    val blink01 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1450, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )
    // 轻柔眨眼：<1 时闭眼程度
    val blinkScaleY = 1f - 0.72f * smoothStep(0.85f, 1f, blink01)
    val eyeScaleY = if (face.blink) blinkScaleY else 1f

    Box(
        modifier.offset { IntOffset(0, (bob * 8f - 4f).dp.roundToPx()) }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dim = min(size.width, size.height)
            // 球体半径：占盒子短边的 40%（直径 80%），接近 LARGE 时的观感
            val bodyR = dim * 0.40f
            val u = bodyR / 100f // viewBox(100) -> px 归一化因子

            drawPath(kappaBody(cx, cy, bodyR), color = Color(0xFF3ECF8E))
            drawEyes(cx, cy, u, eyeScaleY, face)
        }
    }
}

private fun DrawScope.drawEyes(cx: Float, cy: Float, u: Float, scaleY: Float, face: BloubFace) {
    face.eyes.forEach { eye ->
        val ex = cx + eye.cx * u
        val ey = cy + eye.cy * u
        val ew = eye.w * u
        val eh = eye.h * u * eye.scaleY * scaleY
        rotate(eye.rot, pivot = Offset(ex, ey)) {
            drawRoundRect(
                color = Color(0xFFFFFFFF),
                topLeft = Offset(ex - ew / 2f, ey - eh / 2f),
                size = Size(ew, eh),
                cornerRadius = CornerRadius(min(ew, eh) / 2f, min(ew, eh) / 2f)
            )
        }
    }
}

/** 近圆有机主体：以 ([cx],[cy]) 为圆心、[r] 为半径的近圆 kappa 轮廓，闭合、不自交。 */
private fun kappaBody(cx: Float, cy: Float, r: Float): Path = Path().apply {
    val k = 0.5523f * r
    moveTo(cx, cy - r)
    cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    close()
}

/** 单只眼睛：在 viewBox(100) 内的中心、宽、高、倾角与纵向压缩。 */
private data class BloubEye(
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val rot: Float, val scaleY: Float = 1f
)

private data class BloubFace(val eyes: List<BloubEye>, val blink: Boolean = true)

/** 各状态脸部几何（取自 assets/moods 下的 SVG 表情文件）。 */
private fun faceFor(state: BloubState): BloubFace = when (state) {
    // attentif：两只竖直胶囊眼，上部、靠近。
    BloubState.LISTENING -> BloubFace(listOf(
        BloubEye(-15f, -10f, 21f, 44f, -6f),
        BloubEye(39f, -13f, 21f, 44f, -2f)
    ))
    // curieux：眼睛上飘/倾斜（思考）。
    BloubState.THINKING -> BloubFace(listOf(
        BloubEye(5f, 19f, 24f, 46f, -20f),
        BloubEye(58f, 5f, 20f, 38f, -28f)
    ))
    // excite：大而宽的椭圆眼（兴奋）。
    BloubState.SPEAKING -> BloubFace(listOf(
        BloubEye(-18f, 20f, 40f, 56f, -6f),
        BloubEye(48f, 20f, 40f, 56f, 6f)
    ))
    // somnolent：压扁至 45% 高度的困倦眼，不眨眼。
    BloubState.MUTED -> BloubFace(listOf(
        BloubEye(-12f, 13f, 20f, 42f, -1f, scaleY = 0.45f),
        BloubEye(43f, 10f, 20f, 42f, -2f, scaleY = 0.45f)
    ), blink = false)
    // confus：一眼竖直、一眼横置并倾斜（没听清/疑惑）。
    BloubState.CONFUSED -> BloubFace(listOf(
        BloubEye(-45f, -12f, 20f, 44f, -13f),
        BloubEye(10f, -4f, 28f, 17f, 23f)
    ))
    // fier：两只横置短胖眼、抬高（得意 ^^），不眨眼。
    BloubState.SUCCESS -> BloubFace(listOf(
        BloubEye(-16f, -31f, 30f, 15f, 12f, scaleY = 0.93f),
        BloubEye(42f, -31f, 30f, 15f, -13f, scaleY = 0.93f)
    ), blink = false)
    // 兜底：同 attentif。
    else -> BloubFace(listOf(
        BloubEye(-15f, -10f, 21f, 44f, -6f),
        BloubEye(39f, -13f, 21f, 44f, -2f)
    ))
}

private fun smoothStep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
