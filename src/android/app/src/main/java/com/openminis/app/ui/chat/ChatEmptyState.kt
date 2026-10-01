package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import java.util.Calendar
import kotlin.random.Random

/** Time-of-day slot for the empty-chat greeting. */
enum class GreetingSlot { DAWN, MORNING, NOON, AFTERNOON, EVENING, NIGHT }

/** 00-04 dawn, 05-10 morning, 11-13 noon, 14-17 afternoon, 18-22 evening, 23 late night. */
fun greetingSlotFor(hourOfDay: Int): GreetingSlot = when (hourOfDay) {
    in 0..4 -> GreetingSlot.DAWN
    in 5..10 -> GreetingSlot.MORNING
    in 11..13 -> GreetingSlot.NOON
    in 14..17 -> GreetingSlot.AFTERNOON
    in 18..22 -> GreetingSlot.EVENING
    else -> GreetingSlot.NIGHT
}

/** Two wordings per time of day; [variant] (any int, e.g. a per-chat seed) picks one. */
internal fun greetingRes(slot: GreetingSlot, variant: Int): Int {
    val pair = when (slot) {
        GreetingSlot.DAWN -> R.string.chat_empty_greeting_dawn to R.string.chat_empty_greeting_dawn_b
        GreetingSlot.MORNING -> R.string.chat_empty_greeting_morning to R.string.chat_empty_greeting_morning_b
        GreetingSlot.NOON -> R.string.chat_empty_greeting_noon to R.string.chat_empty_greeting_noon_b
        GreetingSlot.AFTERNOON -> R.string.chat_empty_greeting_afternoon to R.string.chat_empty_greeting_afternoon_b
        GreetingSlot.EVENING -> R.string.chat_empty_greeting_evening to R.string.chat_empty_greeting_evening_b
        GreetingSlot.NIGHT -> R.string.chat_empty_greeting_night to R.string.chat_empty_greeting_night_b
    }
    return if (Math.floorMod(variant, 2) == 0) pair.first else pair.second
}

private val PromptVariants = listOf(
    R.string.chat_empty_prompt,
    R.string.chat_empty_prompt_b,
    R.string.chat_empty_prompt_c,
    R.string.chat_empty_prompt_d,
    R.string.chat_empty_prompt_e,
    R.string.chat_empty_prompt_f,
)

/** The line under the greeting; [variant] picks one of several wordings. */
internal fun promptRes(variant: Int): Int = PromptVariants[Math.floorMod(variant, PromptVariants.size)]

/** Cards shown per screen. */
private const val VISIBLE_SUGGESTIONS = 4

/** How many of the cards come from what the user taps most; the rest are random. */
private const val HABIT_SLOTS = 2

/**
 * Which suggestions to show: up to [HABIT_SLOTS] of the ones used most ([uses] = taps per pool
 * index), then random ones to fill [count]. The random part depends only on [seed], so a chat keeps
 * the same cards while it is open and a new chat starts on a different set. Nothing the user has
 * not used is ever ranked, so with no history it is simply a random set.
 */
internal fun pickSuggestions(poolSize: Int, seed: Int, uses: Map<Int, Int>, count: Int = VISIBLE_SUGGESTIONS): List<Int> {
    if (poolSize <= 0) return emptyList()
    val take = minOf(count, poolSize)
    val order = (0 until poolSize).shuffled(Random(seed))
    val favourites = order
        .filter { (uses[it] ?: 0) > 0 }
        .sortedByDescending { uses[it] ?: 0 }   // stable: ties keep the seeded order
        .take(minOf(HABIT_SLOTS, take))
    val rest = order.filter { it !in favourites }.take(take - favourites.size)
    return (favourites + rest).shuffled(Random(seed + 1))
}

private const val QUICK_PREFS = "chat_quick_actions"
private const val QUICK_USES_KEY = "uses"

/** Stored as "index:count,index:count"; anything malformed is ignored. */
internal fun parseUses(raw: String?): Map<Int, Int> = raw.orEmpty().split(',').mapNotNull { part ->
    val (i, n) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
    val index = i.toIntOrNull() ?: return@mapNotNull null
    val count = n.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    index to count
}.toMap()

internal fun formatUses(uses: Map<Int, Int>): String = uses.entries.joinToString(",") { "${it.key}:${it.value}" }

private fun readUses(context: android.content.Context): Map<Int, Int> = runCatching {
    parseUses(context.getSharedPreferences(QUICK_PREFS, android.content.Context.MODE_PRIVATE).getString(QUICK_USES_KEY, null))
}.getOrDefault(emptyMap())

private fun recordUse(context: android.content.Context, index: Int) {
    runCatching {
        val prefs = context.getSharedPreferences(QUICK_PREFS, android.content.Context.MODE_PRIVATE)
        val uses = parseUses(prefs.getString(QUICK_USES_KEY, null)).toMutableMap()
        uses[index] = (uses[index] ?: 0) + 1
        prefs.edit().putString(QUICK_USES_KEY, formatUses(uses)).apply()
    }
}

private val SuggestionPool = listOf(
    QuickCard(Icons.Outlined.Visibility, R.string.chat_quick_screen_title, R.string.chat_quick_screen_sub, R.string.chat_quick_screen_prompt),
    QuickCard(Icons.Outlined.Language, R.string.chat_quick_browse_title, R.string.chat_quick_browse_sub, R.string.chat_quick_browse_prompt),
    QuickCard(Icons.Outlined.Memory, R.string.chat_quick_memory_title, R.string.chat_quick_memory_sub, R.string.chat_quick_memory_prompt),
    QuickCard(Icons.Outlined.BatteryFull, R.string.chat_quick_battery_title, R.string.chat_quick_battery_sub, R.string.chat_quick_battery_prompt),
    QuickCard(Icons.Outlined.PieChart, R.string.chat_quick_storage_title, R.string.chat_quick_storage_sub, R.string.chat_quick_storage_prompt),
    QuickCard(Icons.Outlined.FolderOpen, R.string.chat_quick_tidy_title, R.string.chat_quick_tidy_sub, R.string.chat_quick_tidy_prompt),
    QuickCard(Icons.Outlined.Description, R.string.chat_quick_script_title, R.string.chat_quick_script_sub, R.string.chat_quick_script_prompt),
    QuickCard(Icons.Outlined.Newspaper, R.string.chat_quick_news_title, R.string.chat_quick_news_sub, R.string.chat_quick_news_prompt),
    QuickCard(Icons.Outlined.Terminal, R.string.chat_quick_env_title, R.string.chat_quick_env_sub, R.string.chat_quick_env_prompt),
    QuickCard(Icons.Outlined.Schedule, R.string.chat_quick_schedule_title, R.string.chat_quick_schedule_sub, R.string.chat_quick_schedule_prompt),
    QuickCard(Icons.Outlined.Apps, R.string.chat_quick_apps_title, R.string.chat_quick_apps_sub, R.string.chat_quick_apps_prompt),
    QuickCard(Icons.Outlined.History, R.string.chat_quick_recap_title, R.string.chat_quick_recap_sub, R.string.chat_quick_recap_prompt),
)

/** Size of the pool, for the tests that keep it and [pickSuggestions] consistent. */
internal val SuggestionPoolSize: Int get() = SuggestionPool.size

/**
 * The empty conversation: a greeting and a few things the agent can do right now. Tapping a card
 * sends its prompt. The four cards are chosen automatically from [SuggestionPool]: what the user
 * taps most, plus random ones, so there is nothing to shuffle or configure.
 */
@Composable
fun ChatEmptyState(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    setup: FirstRunSetup? = null,
) {
    val slot = remember { greetingSlotFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val accent = MaterialTheme.colorScheme.primary
    if (setup != null) {
        FirstRunCard(setup, modifier)
        return
    }
    val seed = rememberSaveable { Random.nextInt() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val uses = remember { readUses(context) }
    val picks = remember(seed) { pickSuggestions(SuggestionPool.size, seed, uses) }
    val cards = picks.map { it to SuggestionPool[it] }
    // Centered when it fits; scrolls when it does not (keyboard up, small screen, large font).
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(greetingRes(slot, seed / 7)),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = ChatColors.primaryText,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(promptRes(seed / 3)),
            fontSize = 16.sp,
            color = ChatColors.secondaryText,
        )
        Spacer(Modifier.height(16.dp))
        cards.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (index, card) ->
                    val prompt = stringResource(card.prompt)
                    QuickActionCard(
                        card = card,
                        onClick = { recordUse(context, index); onPick(prompt) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    }
}

private data class QuickCard(val icon: ImageVector, val title: Int, val subtitle: Int, val prompt: Int)

@Composable
private fun QuickActionCard(card: QuickCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .heightIn(min = 120.dp)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(ChatColors.background)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(card.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(card.title),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(card.subtitle),
            fontSize = 12.5.sp,
            color = ChatColors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/**
 * What the first-run card needs: which of the two setup steps are done, and where to go for the
 * one that is not. The third step (start chatting) unlocks when both are.
 */
data class FirstRunSetup(
    val providerDone: Boolean,
    val modelDone: Boolean,
    val onOpenProvider: () -> Unit,
    val onOpenModel: () -> Unit,
)

/** The board's empty state for a fresh install: welcome, then three steps with the next one live. */
@Composable
private fun FirstRunCard(setup: FirstRunSetup, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.onboarding_welcome_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.primaryText,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.first_run_subtitle),
                fontSize = 16.sp,
                color = ChatColors.secondaryText,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChatColors.secondaryBg),
            ) {
                SetupStepRow(
                    number = 1,
                    title = stringResource(R.string.first_run_step_provider),
                    subtitle = stringResource(if (setup.providerDone) R.string.first_run_done else R.string.first_run_step_provider_desc),
                    done = setup.providerDone,
                    locked = false,
                    onClick = setup.onOpenProvider,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SetupStepRow(
                    number = 2,
                    title = stringResource(R.string.first_run_step_model),
                    subtitle = stringResource(if (setup.modelDone) R.string.first_run_done else R.string.first_run_step_model_desc),
                    done = setup.modelDone,
                    locked = !setup.providerDone,
                    onClick = setup.onOpenModel,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SetupStepRow(
                    number = 3,
                    title = stringResource(R.string.first_run_step_chat),
                    subtitle = stringResource(R.string.first_run_step_chat_locked),
                    done = false,
                    locked = !(setup.providerDone && setup.modelDone),
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    number: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!done && !locked) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> ChatColors.ok
                        locked -> MaterialTheme.colorScheme.outlineVariant
                        else -> accent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            } else {
                Text("$number", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (locked) ChatColors.secondaryText else ChatColors.primaryText,
            )
            Text(
                subtitle,
                fontSize = 13.sp,
                color = if (done) ChatColors.ok else ChatColors.secondaryText,
            )
        }
        if (!done && !locked) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(20.dp),
            )
        } else if (locked) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = ChatColors.secondaryText, modifier = Modifier.size(16.dp))
        }
    }
}
