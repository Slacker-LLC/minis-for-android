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

/** Time-of-day slot for the empty-chat greeting (board: "早上好" + "今天想做点什么？"). */
enum class GreetingSlot { MORNING, AFTERNOON, EVENING }

/** 00:00-11:59 morning, 12:00-17:59 afternoon, 18:00-23:59 evening. */
fun greetingSlotFor(hourOfDay: Int): GreetingSlot = when (hourOfDay) {
    in 0..11 -> GreetingSlot.MORNING
    in 12..17 -> GreetingSlot.AFTERNOON
    else -> GreetingSlot.EVENING
}

/** Cards shown per screen. */
private const val VISIBLE_SUGGESTIONS = 4

/**
 * Which suggestions to show: a window of [count] from a pool of [poolSize], ordered by a shuffle
 * that depends only on [seed]. Each [page] moves to the next window and wraps, so "Shuffle" walks
 * through the whole pool before repeating, and a given chat keeps the same cards while it is open.
 */
internal fun suggestionWindow(poolSize: Int, seed: Int, page: Int, count: Int = VISIBLE_SUGGESTIONS): List<Int> {
    if (poolSize <= 0) return emptyList()
    val order = (0 until poolSize).shuffled(Random(seed))
    val take = minOf(count, poolSize)
    val start = Math.floorMod(page * take, poolSize)
    return List(take) { order[(start + it) % poolSize] }
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

/** Size of the pool, for the tests that keep it and [suggestionWindow] consistent. */
internal val SuggestionPoolSize: Int get() = SuggestionPool.size

/**
 * The empty conversation: a greeting and a few things the agent can do right now. Tapping a card
 * sends its prompt. The cards come from a pool of [SuggestionPool] entries, four at a time, and a
 * new chat starts on a different window, so the screen is not the same four tiles every time.
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
    var page by rememberSaveable { mutableIntStateOf(0) }
    val cards = suggestionWindow(SuggestionPool.size, seed, page).map { SuggestionPool[it] }
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
            text = stringResource(
                when (slot) {
                    GreetingSlot.MORNING -> R.string.chat_empty_greeting_morning
                    GreetingSlot.AFTERNOON -> R.string.chat_empty_greeting_afternoon
                    GreetingSlot.EVENING -> R.string.chat_empty_greeting_evening
                },
            ),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = ChatColors.primaryText,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.chat_empty_prompt),
                fontSize = 16.sp,
                color = ChatColors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            MinisTextButton(onClick = { page += 1 }) {
                Text(stringResource(R.string.chat_quick_shuffle), fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        cards.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { card ->
                    val prompt = stringResource(card.prompt)
                    QuickActionCard(
                        card = card,
                        onClick = { onPick(prompt) },
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
