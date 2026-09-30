package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent: iOS-style blue that meets WCAG AA. [T-ui-white-accent-aa]
//
// The earlier desaturated blue (#528AD2 light / #6A94CE dark) measured 3.54:1
// on white, below AA's 4.5:1 for text, and every action is now a text button
// (docs/design/UI-DESIGN-LANGUAGE.md §6), so the accent carries the label.
//   light  #0068D6  5.31:1 on #FFFFFF, 4.76:1 on #F2F2F7
//   dark   #0A84FF  5.76:1 on #000000, 4.66:1 on #1C1C1E
// Dark text on the lifted #26262A chat fill measures 4.13:1; keep text
// buttons on page/sheet/card surfaces, not on that fill.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
private val TealPrimary = Color(0xFF0068D6)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFB2DFDB)
private val TealOnPrimaryContainer = Color(0xFF00332F)
private val TealSecondary = Color(0xFF4A6360)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFCCE8E4)
private val TealOnSecondaryContainer = Color(0xFF05201D)
private val TealTertiary = Color(0xFF46617A)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFCDE5FF)
private val TealOnTertiaryContainer = Color(0xFF001D32)
private val TealBackground = Color(0xFFF5FAFA)
private val TealOnBackground = Color(0xFF000000)
private val TealSurface = Color(0xFFF5FAFA)
private val TealOnSurface = Color(0xFF000000)
private val TealSurfaceVariant = Color(0xFFDAE5E2)
private val TealOnSurfaceVariant = Color(0xFF3F4947)
private val TealOutline = Color(0xFF6F7977)

private val TealDarkPrimary = Color(0xFF0A84FF)
private val TealDarkOnPrimary = Color(0xFF003737)
private val TealDarkPrimaryContainer = Color(0xFF1A6B6B)
private val TealDarkOnPrimaryContainer = Color(0xFFB2DFDB)
private val TealDarkSecondary = Color(0xFFB1CCC8)
private val TealDarkOnSecondary = Color(0xFF1C3532)
private val TealDarkSecondaryContainer = Color(0xFF334B48)
private val TealDarkOnSecondaryContainer = Color(0xFFCCE8E4)
private val TealDarkBackground = Color(0xFF0E1514)
private val TealDarkOnBackground = Color(0xFFDEE4E2)
private val TealDarkSurface = Color(0xFF0E1514)
private val TealDarkOnSurface = Color(0xFFDEE4E2)
private val TealDarkSurfaceVariant = Color(0xFF3F4947)
private val TealDarkOnSurfaceVariant = Color(0xFFBEC9C6)
private val TealDarkOutline = Color(0xFF899390)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
// Light: page = #FFFFFF, every card/container level = the single fill grey #F2F2F7
//        (docs/design/UI-DESIGN-LANGUAGE.md §2-3: one white, one grey).
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFFFFFFF)
private val NeutralGroupedCard = Color(0xFFF2F2F7)
private val NeutralGroupedCardElevated = Color(0xFFF2F2F7)
private val NeutralOutline = Color(0xFFE5E5EA)

private val NeutralDarkGroupedBg = Color(0xFF000000)
private val NeutralDarkGroupedCard = Color(0xFF1C1C1E)
private val NeutralDarkGroupedCardElevated = Color(0xFF2C2C2E)
private val NeutralDarkOutline = Color(0xFF38383A)

internal val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedBg,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
    // Design language §3 "bad": Material's default error (#B3261E / #F2B8B5) is a
    // different red, and the dark default reads pink on black.
    error = Color(0xFFD70015),
    onError = Color(0xFFFFFFFF),
)

internal val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
    error = Color(0xFFFF453A),
    onError = Color(0xFF000000),
)

// The page background painted into the GlassHost backdrop layer. Must stay
// in sync with Theme.kt's NeutralGroupedBg / NeutralDarkGroupedBg (which are
// private to Theme.kt).
fun minisPageBackground(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF000000) else Color(0xFFFFFFFF)

/**
 * Opaque surface for sheets, dialogs and popovers (design language §3 "sheet"):
 * white in light, the raised `#1C1C1E` in dark. The dark colour scheme's own
 * `surface` is the page black, so overlays must not use it directly.
 */
@Composable
@ReadOnlyComposable
fun minisSheetColor(): Color =
    if (ChatColors.isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface

// Scrim behind dialogs / sheets / menus: black 18% on the white page, 55% on
// the black page (docs/design/UI-DESIGN-LANGUAGE.md §3, §7).
fun minisOverlayScrim(darkTheme: Boolean): Color =
    Color.Black.copy(alpha = if (darkTheme) 0.55f else 0.18f)

// Two parallel UI styles, switchable live in 设置 → 外观. Both share the same
// palette and shapes; the difference lives in the surface treatment (glass
// components sample the GlassHost backdrop instead of painting flat surfaces).
enum class UiStyle { CLASSIC, GLASS }

val LocalUiStyle = staticCompositionLocalOf { UiStyle.CLASSIC }

// App-wide FAB accent color (the app accent; see ChatPalette.fabAccent).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

/**
 * [T-android-accent-color] The accent the user picked, applied through colorScheme.primary.
 *
 * Index 0 is the app's own accent (an iOS-style blue chosen to meet WCAG AA, >= 4.5:1 on white and
 * on black; see docs/design/UI-DESIGN-LANGUAGE.md §3); the other six keep the hues of X's accent
 * palette (blue / yellow / pink / purple / orange / green), which is what the picker offers. Each
 * has a light and a dark value: the dark variants are lifted so they keep contrast on dark
 * surfaces, and the light variants are darkened (hue kept) to >= 4.5:1 on white and on #F2F2F7,
 * because actions are text buttons and the accent is now the label colour.
 */
enum class AccentColor(val index: Int, val light: Color, val dark: Color) {
    DEFAULT(0, Color(0xFF0068D6), Color(0xFF0A84FF)),
    BLUE(1, Color(0xFF0C72B7), Color(0xFF4FB3F0)),
    YELLOW(2, Color(0xFF846B00), Color(0xFFFFD400)),
    PINK(3, Color(0xFFD80667), Color(0xFFF95C9F)),
    PURPLE(4, Color(0xFF704CFF), Color(0xFF957CFF)),
    ORANGE(5, Color(0xFFB05400), Color(0xFFFF9A40)),
    GREEN(6, Color(0xFF007E51), Color(0xFF00BA7C)),
    ;

    companion object {
        fun fromIndex(value: Int): AccentColor = entries.firstOrNull { it.index == value } ?: DEFAULT
    }
}

/** Re-colours the parts of the scheme an accent owns; the rest of the palette stays put. */
private fun androidx.compose.material3.ColorScheme.withAccent(accent: AccentColor, dark: Boolean): androidx.compose.material3.ColorScheme {
    val primary = if (dark) accent.dark else accent.light
    return copy(
        primary = primary,
        onPrimary = if (primary.luminance() > 0.5f) Color(0xFF101010) else Color.White,
        primaryContainer = primary.copy(alpha = 0.22f).compositeOver(surface),
        onPrimaryContainer = if (dark) accent.light else accent.dark,
        inversePrimary = if (dark) accent.light else accent.dark,
        surfaceTint = primary,
    )
}

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    uiStyle: UiStyle = UiStyle.CLASSIC,
    accent: AccentColor = AccentColor.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme = (if (darkTheme) DarkColorScheme else LightColorScheme).withAccent(accent, darkTheme)
    val typography = scaledTypography(fontScale)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette,
            LocalUiStyle provides uiStyle,
            content = content,
        )
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

private fun scaledTypography(factor: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
