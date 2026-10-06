package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.pet.PetControlActivity
import com.openminis.app.ui.theme.AccentColor
import com.openminis.app.data.repository.AppIconRepository
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.SharedPreferences
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Style
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.ui.theme.ChatColors
import kotlin.math.roundToInt

// -- Preference Keys --
const val PREF_APPEARANCE = "appearance_prefs"
const val KEY_THEME_MODE = "theme_mode"            // 0=System, 1=Light, 2=Dark
// [T-android-accent-color] Accent the user picked; index into AccentColor (0 = the app's own).
const val KEY_ACCENT_COLOR = "accent_color"
const val KEY_UI_STYLE = "ui_style"                // 0=Classic, 1=Glass (Liquid Glass)
const val KEY_LAUNCH_SESSION = "launch_session"    // 0=Auto, 1=LastSession, 2=NewChat, 3=Home
// iOS-aligned key names — match `@AppStorage("returnKeyBehavior")` and
// `@AppStorage("keepScreenAwakeDuringTasks")` in ContentView.swift so
// future cross-platform sync (if it ever lands) reads the same values.
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"  // Int 0=Newline (default), 1=Send
const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeDuringTasks"  // Boolean, default false
const val KEY_TOOL_PREVIEW = "tool_preview"        // Boolean, default true
// [T-android-tool-status-bar-toggle] The floating tool status bar above the composer (tool name,
// result, CPU/MEM). KEY_TOOL_PREVIEW only governs its thumbnail, so a user who wants the strip
// itself gone had no switch - this is that switch. Boolean, default true.
const val KEY_TOOL_STATUS_BAR = "tool_status_bar"
// [T-keyboard-auto-pop default flip] Default ON — most users want the
// composer ready for a follow-up immediately after the model finishes.
// Key name mirrors iOS `@AppStorage("chat.autoFocusAfterReply")` so a
// future cross-platform sync reads the same pref.
const val KEY_AUTO_FOCUS_AFTER_REPLY = "chat.autoFocusAfterReply"  // Boolean, default true
// T-chat-title-pill: shows a sticky session-title pill above the chat list
// while the user scrolls back through history. Cross-platform key name
// (matches iOS @AppStorage("appearance.show_chat_title")) so future config
// sync reads the same value.
const val KEY_SHOW_CHAT_TITLE = "appearance.show_chat_title"  // Boolean, default true
// [T-thinking-auto-expand-toggle] When true (default, historical behavior) a
// NEW streaming thinking block auto-expands while the model reasons; when false
// it stays collapsed until tapped. Key name mirrors iOS
// `@AppStorage("chat.autoExpandThinking")` so future config sync reads the same
// value. Read at block-mount time in ThinkingBlock.
const val KEY_AUTO_EXPAND_THINKING = "chat.autoExpandThinking"  // Boolean, default true
// [T-android-auto-grouping] When a chat's title is first generated, also file
// it into a matching EXISTING group. Rides the title-generation call — no
// second round-trip. Key name matches iOS `autoGroupingEnabled` so a future
// config sync reads the same value.
//
// Default ON (both platforms, per product decision 2026-08-16). iOS originally
// shipped this opt-in on the reasoning that it "moves user data without being
// asked"; that concern is bounded here because the feature only ever files a
// chat into a group the user already created, only when the model is confident,
// only once per chat, and never over a hand-filed session (setFolderIfUnfiled).
const val KEY_AUTO_GROUPING = "autoGroupingEnabled"  // Boolean, default true
const val KEY_FONT_CHAT_INPUT = "font_chat_input"  // Int scale level -2..3
const val KEY_FONT_MESSAGE = "font_message"        // Int scale level -2..3
const val KEY_FONT_APP_BASE = "font_app_base"      // Int scale level -2..3
const val KEY_LANGUAGE = "app_language"             // "" = system, "en", "zh", "zh-Hant", "ja", "ko", "fr", "de", "ru"

/** True when Enter (without Shift) should send the message. iOS calls this
 *  `returnKeyBehavior == 1`. Default 0 = Enter inserts a newline (matches
 *  iOS shipping default + most desktop chat clients). */
fun returnKeySendsMessage(context: Context): Boolean =
    getAppearancePrefs(context).getInt(KEY_RETURN_KEY_BEHAVIOR, 0) == 1

fun keepScreenAwakeEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

/** [T-android-auto-grouping] Default ON — see [KEY_AUTO_GROUPING]. */
fun autoGroupingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_GROUPING, true)

/** Default ON — pill shows up on scroll for everyone unless explicitly disabled. */
fun showChatTitleEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_CHAT_TITLE, true)

/** [T-thinking-auto-expand-toggle] Default ON = historical behavior: a new
 *  streaming thinking block opens expanded. Off = it stays collapsed until the
 *  user taps it. */
fun autoExpandThinkingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_EXPAND_THINKING, true)

/** Font scale levels matching iOS: XS(-2) Small(-1) Default(0) Medium(1) Large(2) XL(3) */
private val fontScaleLabels = listOf("XS", "Small", "Default", "Medium", "Large", "XL")
private val fontScaleValues = listOf(-2, -1, 0, 1, 2, 3)
private val fontScaleMultipliers = listOf(0.88f, 0.94f, 1.0f, 1.06f, 1.12f, 1.21f)

private data class LanguageOption(val code: String, val flag: String, val label: String)
// "System" label is resolved at call-site via stringResource so it follows
// the user's chosen UI language. The remaining entries are language self-names
// and stay as literals \u2014 Chinese is always "\u7B80\u4F53\u4E2D\u6587", regardless of UI locale.
private val languageOptions = listOf(
    LanguageOption("", "\uD83C\uDF10", ""),
    LanguageOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
    LanguageOption("zh", "\uD83C\uDDE8\uD83C\uDDF3", "简体中文"),
    LanguageOption("zh-Hant", "\uD83C\uDDF9\uD83C\uDDFC", "繁體中文"),
    LanguageOption("ja", "\uD83C\uDDEF\uD83C\uDDF5", "日本語"),
    LanguageOption("ko", "\uD83C\uDDF0\uD83C\uDDF7", "한국어"),
    LanguageOption("fr", "\uD83C\uDDEB\uD83C\uDDF7", "Français"),
    LanguageOption("de", "\uD83C\uDDE9\uD83C\uDDEA", "Deutsch"),
    // ru: flag \uD83C\uDDF7\uD83C\uDDFA (RU), self-name \u0420\u0443\u0441\u0441\u043A\u0438\u0439 (Russkiy)
    LanguageOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
)

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

fun getThemeMode(context: Context): Int =
    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)

fun getFontScale(context: Context, key: String): Float =
    fontScaleForLevel(getAppearancePrefs(context).getInt(key, 0))

fun fontScaleForLevel(level: Int): Float {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleMultipliers.lastIndex)
    return fontScaleMultipliers[idx]
}

@Composable
private fun AccentColor.label(): String = stringResource(
    when (this) {
        AccentColor.DEFAULT -> R.string.appearance_accent_default
        AccentColor.BLUE -> R.string.appearance_accent_blue
        AccentColor.YELLOW -> R.string.appearance_accent_yellow
        AccentColor.PINK -> R.string.appearance_accent_pink
        AccentColor.PURPLE -> R.string.appearance_accent_purple
        AccentColor.ORANGE -> R.string.appearance_accent_orange
        AccentColor.GREEN -> R.string.appearance_accent_green
    },
)

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onThemeChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
    var accentIndex by remember { mutableIntStateOf(prefs.getInt(KEY_ACCENT_COLOR, 0)) }
    var uiStyle by remember { mutableIntStateOf(prefs.getInt(KEY_UI_STYLE, 0)) }
    var chatInputLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_CHAT_INPUT, 0)) }
    var messageLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_MESSAGE, 0)) }
    var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
    var selectedLanguage by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, "") ?: "") }
    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.current(context)) }

    val fontsModified = chatInputLevel != 0 || messageLevel != 0 || appBaseLevel != 0

    val tilePurple = Color(0xFF5856D6)
    val tileBlue = Color(0xFF007AFF)
    val tileOrange = ChatColors.warn

    SettingsScaffold(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_title),
        largeTitle = true,
    ) {

        // -- Theme --
        // Each row carries its own leading icon + tile colour, mirroring the
        // Provider / Permissions screens. Earlier only the first row had an
        // icon and rows 2–3 fell through to a 24dp Spacer, which read as a
        // visual hiccup at the section boundary.
        SettingsSection(
            header = stringResource(R.string.appearance_section_theme),
            footer = stringResource(R.string.appearance_theme_footer),
        ) {
            SettingsCardBlock {
                SettingsSegmented(
                    options = listOf(
                        stringResource(R.string.appearance_theme_system),
                        stringResource(R.string.appearance_theme_light),
                        stringResource(R.string.appearance_theme_dark),
                    ),
                    selectedIndex = themeMode,
                    onSelect = { idx ->
                        themeMode = idx
                        prefs.edit().putInt(KEY_THEME_MODE, idx).apply()
                        onThemeChanged(idx)
                    },
                )
            }
        }

        // -- Accent colour --
        // [T-android-accent-color] Sets colorScheme.primary for the whole app: buttons, switches,
        // links, the running work-row label. Index 0 is the app's own blue so nothing changes for
        // an install that never opens this row.
        SettingsSection(
            header = stringResource(R.string.appearance_section_accent),
            footer = stringResource(R.string.appearance_accent_footer),
        ) {
            // The in-app palette, not the system setting: the two disagree whenever the user has
            // overridden the theme here (guarded by InAppThemeSourceGuardTest).
            val swatchDark = com.openminis.app.ui.theme.ChatColors.isDark
            SettingsCardBlock {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentColor.entries.forEach { accent ->
                        val selected = accentIndex == accent.index
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (swatchDark) accent.dark else accent.light)
                                .clickable(onClickLabel = accent.label()) {
                                    accentIndex = accent.index
                                    prefs.edit().putInt(KEY_ACCENT_COLOR, accent.index).apply()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                contentDescription = accent.label(),
                                tint = if (selected) Color.White else Color.Transparent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // -- UI Style --
        // Two parallel UIs: Classic (Material surface look) and Glass
        // (liquid-glass surfaces over the page content). Switching applies
        // instantly via LocalUiStyle — no restart.
        SettingsSection(
            header = stringResource(R.string.appearance_ui_style_title),
        ) {
            SettingsCardBlock {
                SettingsSegmented(
                    options = listOf(stringResource(R.string.appearance_style_classic), stringResource(R.string.appearance_style_glass)),
                    selectedIndex = uiStyle,
                    onSelect = { idx ->
                        uiStyle = idx
                        prefs.edit().putInt(KEY_UI_STYLE, idx).apply()
                    },
                )
            }
        }

        // -- Font Size --
        SettingsSection(
            header = stringResource(R.string.appearance_section_font_size),
        ) {
            SettingsRow(
                icon = Icons.Outlined.FormatSize,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_font_scale_title),
                subtitle = stringResource(R.string.appearance_font_scale_subtitle),
                onClick = null,
                showChevron = false,
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_chat_input),
                level = chatInputLevel,
                onLevelChange = {
                    chatInputLevel = it
                    prefs.edit().putInt(KEY_FONT_CHAT_INPUT, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_message),
                level = messageLevel,
                onLevelChange = {
                    messageLevel = it
                    prefs.edit().putInt(KEY_FONT_MESSAGE, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_app_base),
                level = appBaseLevel,
                onLevelChange = {
                    appBaseLevel = it
                    prefs.edit().putInt(KEY_FONT_APP_BASE, it).apply()
                },
                showDivider = fontsModified,
            )
            if (fontsModified) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MinisTextButton(onClick = {
                        chatInputLevel = 0; messageLevel = 0; appBaseLevel = 0
                        prefs.edit()
                            .putInt(KEY_FONT_CHAT_INPUT, 0)
                            .putInt(KEY_FONT_MESSAGE, 0)
                            .putInt(KEY_FONT_APP_BASE, 0)
                            .apply()
                    }) {
                        Text(stringResource(R.string.appearance_font_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // -- App Icon (T-android-dynamic-app-icon) --
        // Grid picker mirrors the iOS Settings → Appearance → App Icon
        // section but uses a 3-column grid layout per spec. Each tile is
        // an adaptive-icon preview (loaded as Bitmap via ResourcesCompat
        // since painterResource can't decode mipmap-anydpi XMLs);
        // the currently-selected tile gets a checkmark badge in the
        // top-right corner. Tapping a tile flips the corresponding
        // activity-alias enabled state via PackageManager — the launcher
        // refreshes its icon cache within a few seconds.
        SettingsSection(
            header = stringResource(R.string.appearance_section_app_icon),
        ) {
            data class IconOption(
                val variant: AppIconRepository.Variant,
                val titleRes: Int,
                val mipmapRes: Int,
            )
            val iconOptions = listOf(
                IconOption(
                    AppIconRepository.Variant.Auto,
                    R.string.appearance_app_icon_auto,
                    R.mipmap.ic_launcher,
                ),
                IconOption(
                    AppIconRepository.Variant.ClassicLight,
                    R.string.appearance_app_icon_light,
                    R.mipmap.ic_launcher_classic_light,
                ),
                IconOption(
                    AppIconRepository.Variant.ClassicDark,
                    R.string.appearance_app_icon_dark,
                    R.mipmap.ic_launcher_classic_dark,
                ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (option in iconOptions) {
                    val isSelected = selectedAppIcon == option.variant
                    val iconPainter: Painter = remember(option.mipmapRes) {
                        // painterResource() can't decode adaptive-icon
                        // XML drawables (mipmap-anydpi), so rasterize
                        // the drawable into a Bitmap first.
                        val drawable = ResourcesCompat.getDrawable(
                            context.resources,
                            option.mipmapRes,
                            context.theme,
                        )
                        if (drawable != null) {
                            BitmapPainter(
                                drawable.toBitmap(width = 192, height = 192).asImageBitmap()
                            )
                        } else {
                            BitmapPainter(
                                android.graphics.Bitmap
                                    .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                                    .asImageBitmap()
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (selectedAppIcon != option.variant) {
                                    selectedAppIcon = option.variant
                                    AppIconRepository.apply(context, option.variant)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                ),
                        ) {
                            Image(
                                painter = iconPainter,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .background(
                                            color = Color.White,
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(option.titleRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        // -- Language --
        SettingsSection(
            header = stringResource(R.string.appearance_section_language),
        ) {
            languageOptions.forEachIndexed { idx, lang ->
                SettingsChoiceRow(
                    title = if (lang.code.isEmpty()) stringResource(R.string.appearance_theme_system) else lang.label,
                    selected = selectedLanguage == lang.code,
                    onSelect = {
                        selectedLanguage = lang.code
                        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val lm = context.getSystemService(LocaleManager::class.java)
                            lm?.applicationLocales = if (lang.code.isEmpty()) {
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(lang.code)
                            }
                        } else {
                            // T-n01-andmenu-l10n: pre-Tiramisu has no
                            // LocaleManager. The new language is read
                            // from SharedPreferences by
                            // [LocaleWrap.wrap] on the next
                            // attachBaseContext call, so recreate the
                            // Activity so its base Configuration picks
                            // up the change immediately.
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    leading = {
                        Text(lang.flag, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                    },
                    showDivider = idx < languageOptions.size - 1,
                )
            }
        }

        // -- Desktop pet --
        // Lived one level up in the old "Appearance" category page; the design has no such page, so
        // it sits at the bottom of Appearance itself.
        SettingsSection {
            SettingsRow(
                icon = Icons.Outlined.Palette,
                iconColor = Color(0xFF4D6BFE),
                title = stringResource(R.string.settings_pet),
                subtitle = stringResource(R.string.settings_pet_subtitle),
                onClick = { context.startActivity(android.content.Intent(context, PetControlActivity::class.java)) },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleValues.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(idx.toFloat()) }
    val currentLabel = fontScaleLabels.getOrElse(sliderPos.roundToInt()) { "Default" }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val newIdx = sliderPos.roundToInt().coerceIn(0, fontScaleValues.lastIndex)
                    sliderPos = newIdx.toFloat()
                    onLevelChange(fontScaleValues[newIdx])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}
