package com.openminis.app.ui.glass

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.openminis.app.ui.theme.LocalChatPalette
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle

/**
 * GlassKit — Liquid-Glass surface kit for the two-parallel-UI system.
 *
 * Wraps the vendored com.kyant.backdrop library (Apache-2.0, tag 2.0.0) behind
 * one entry point so the rest of the app never imports the library directly.
 *
 * Capability ladder (decided by device, not by code path):
 *  - API 33-35: vibrancy + blur + lens refraction (full liquid glass)
 *  - API 31-32: vibrancy + blur only (lens is RuntimeShader-gated, no-ops)
 *  - API 36+: stable frosted fallback (HyperOS Android 16 RenderThread crash)
 *  - API < 31: no RenderEffect at all → fallbackScrim (opaque, classic look)
 *  - Popup windows: backdrop layer is recorded in the activity window, so a
 *    Popup's coordinates can't sample it → call sites pass forceFallback.
 *
 * ponytail: no per-device tunables UI yet; add a settings knob only if the
 * blur cost shows up on real devices.
 */

/** The shared backdrop layer recorded by [GlassHost]; null when no host. */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Mounts once at the app root (around the NavHost). On the verified full-effect
 * range only, records page content into a [LayerBackdrop] so glass surfaces can
 * sample it. Classic and fallback paths bypass the recorder entirely.
 */
@Composable
fun GlassHost(
    backgroundColor: Color,
    content: @Composable () -> Unit,
) {
    if (LocalUiStyle.current != UiStyle.GLASS || !canUseBlurredGlass()) {
        content()
        return
    }
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            content()
        }
    }
}

/** Translucent tint drawn over the blurred backdrop (iOS-style). */
@Composable
fun minisGlassScrim(): Color =
    if (LocalChatPalette.current.isDark) Color.Black.copy(alpha = 0.45f)
    else Color.White.copy(alpha = 0.55f)

/** Opaque surface for devices/paths without backdrop sampling. */
@Composable
fun minisGlassFallbackScrim(): Color = LocalChatPalette.current.background

// HyperOS Android 16 (API 36) and the API-34 AVD RenderThread reproducibly
// SIGSEGV in the backdrop/blur compositor path. Keep the native path off
// emulators; they use the existing frosted fallback instead.
private fun canUseBlurredGlass(): Boolean =
    !isEmulator() && isRenderEffectSupported() && Build.VERSION.SDK_INT < 36

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true)

private fun Modifier.frostedFallback(shape: Shape, scrim: Color): Modifier {
    val base = scrim.copy(alpha = 1f)
    val top = Color.White.copy(alpha = 0.08f).compositeOver(base)
    val bottom = Color.Black.copy(alpha = 0.04f).compositeOver(base)
    val border = if (base.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.35f)
    } else {
        Color.Black.copy(alpha = 0.12f)
    }
    return shadow(
        elevation = 4.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.10f),
        spotColor = Color.Black.copy(alpha = 0.10f),
    )
        .background(Brush.verticalGradient(listOf(top, bottom)), shape)
        .border(0.75.dp, border, shape)
}

/** True when this device can use the verified native blur path. */
fun minisGlassBlurAvailable(): Boolean = canUseBlurredGlass()

/**
 * Sheet/dialog surfaces live in their OWN window (material3 ModalBottomSheet
 * is a Dialog), so they can't sample the in-activity [GlassHost] backdrop.
 * Instead: translucent scrim + the platform's native window background blur
 * (see [GlassSheetWindowBlur]). Unsupported/API 36+ paths use a safe frosted fallback.
 */
@Composable
fun Modifier.glassSheetSurface(
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
): Modifier {
    if (LocalUiStyle.current != UiStyle.GLASS) return this
    val scrim = minisGlassScrim()
    val fallback = minisGlassFallbackScrim()
    return if (canUseBlurredGlass()) {
        background(scrim, shape)
    } else {
        frostedFallback(shape, fallback)
    }
}

/**
 * Enables the platform window background blur (verified API 31-35) for the
 * enclosing Dialog window, so translucent sheet/dialog surfaces read as glass.
 * No-op on classic style, unsupported/API 36+ paths, and when no dialog window is found
 * (silent — the surface falls back to its opaque scrim).
 */
@Composable
fun GlassSheetWindowBlur(radius: Dp = 30.dp) {
    if (LocalUiStyle.current != UiStyle.GLASS) return
    if (!canUseBlurredGlass()) return
    val view = LocalView.current
    val provider = remember(view) {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            if (parent is DialogWindowProvider) return@remember parent
            parent = (parent as? android.view.View)?.parent
        }
        null
    }
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(provider, radiusPx) {
        val window = provider?.window ?: return@DisposableEffect onDispose {}
        val hadFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        try {
            window.setBackgroundBlurRadius(radiusPx)
        } catch (_: Throwable) {
            // Some OEM windows reject blur; surface falls back to its scrim.
        }
        onDispose {
            try {
                window.setBackgroundBlurRadius(0)
            } catch (_: Throwable) {
            }
            if (!hadFlag) window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}

@Composable
fun Modifier.glassSurface(
    shape: Shape,
    glassScrim: Color,
    fallbackScrim: Color,
    blurRadius: Dp = 20.dp,
    refraction: Dp = 24.dp,
    forceFallback: Boolean = false,
): Modifier {
    if (LocalUiStyle.current != UiStyle.GLASS) return this
    if (forceFallback || !isRenderEffectSupported()) {
        return this.background(fallbackScrim, shape)
    }
    // Real-device QC, 2026-08-24: this HyperOS Android 16 (API 36) SIGSEGVs
    // its RenderThread when com.kyant.backdrop samples a GraphicsLayer. Do not
    // enter drawBackdrop there; a gradient frosted surface + hairline remains
    // visually distinct without exercising the broken compositor path.
    if (!canUseBlurredGlass()) return frostedFallback(shape, fallbackScrim)
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null) return frostedFallback(shape, fallbackScrim)
    val runtimeShaderSafe = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (runtimeShaderSafe) lens(refraction.toPx(), refraction.toPx())
        },
        highlight = if (runtimeShaderSafe) ({ Highlight.Default }) else null,
        onDrawSurface = { drawRect(glassScrim) },
    )
}
