package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * A quoted piece of text as a card: a bar on the left, the text clipped to a few lines. Above the composer it
 * carries a remove button; in a sent message it is the plain card. (No intrinsic measuring: the bar is drawn
 * behind the row, which stays safe next to the sub-composed blocks the chat is full of.)
 */
@Composable
internal fun QuoteCard(
    text: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val bar = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .drawBehind { drawRect(bar, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 13.dp, top = 6.dp, bottom = 6.dp, end = if (onRemove != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = ChatColors.secondaryText,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onRemove != null) {
            Box(
                modifier = Modifier.padding(start = 4.dp).size(28.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
