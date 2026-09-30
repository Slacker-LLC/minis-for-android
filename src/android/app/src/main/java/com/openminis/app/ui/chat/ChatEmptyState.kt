package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.openminis.app.ui.theme.ChatColors
import java.util.Calendar

/** Time-of-day slot for the empty-chat greeting (board: "早上好" + "今天想做点什么？"). */
enum class GreetingSlot { MORNING, AFTERNOON, EVENING }

/** 00:00-11:59 morning, 12:00-17:59 afternoon, 18:00-23:59 evening. */
fun greetingSlotFor(hourOfDay: Int): GreetingSlot = when (hourOfDay) {
    in 0..11 -> GreetingSlot.MORNING
    in 12..17 -> GreetingSlot.AFTERNOON
    else -> GreetingSlot.EVENING
}

/**
 * The empty conversation: a greeting and four quick actions (board: new chat empty state).
 * Tapping a card puts its prompt in the composer; it never sends on its own, because some of
 * these actions drive other apps and the user should see the words first.
 */
@Composable
fun ChatEmptyState(
    onPickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slot = remember { greetingSlotFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val accent = MaterialTheme.colorScheme.primary
    val cards = listOf(
        QuickCard(Icons.Outlined.Visibility, R.string.chat_quick_screen_title, R.string.chat_quick_screen_sub, R.string.chat_quick_screen_prompt),
        QuickCard(Icons.Outlined.ChatBubbleOutline, R.string.chat_quick_wechat_title, R.string.chat_quick_wechat_sub, R.string.chat_quick_wechat_prompt),
        QuickCard(Icons.Outlined.Language, R.string.chat_quick_browse_title, R.string.chat_quick_browse_sub, R.string.chat_quick_browse_prompt),
        QuickCard(Icons.Outlined.Memory, R.string.chat_quick_memory_title, R.string.chat_quick_memory_sub, R.string.chat_quick_memory_prompt),
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
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
        Text(
            text = stringResource(R.string.chat_empty_prompt),
            fontSize = 16.sp,
            color = ChatColors.secondaryText,
        )
        Spacer(Modifier.height(24.dp))
        cards.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { card ->
                    val prompt = stringResource(card.prompt)
                    QuickActionCard(
                        card = card,
                        onClick = { onPickPrompt(prompt) },
                        modifier = Modifier.weight(1f),
                    )
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
