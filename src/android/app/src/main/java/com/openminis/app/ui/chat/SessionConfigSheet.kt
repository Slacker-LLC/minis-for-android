package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

@Composable
fun SessionConfigSheet(
    onDismiss: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcps: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    StandardChatSheet(
        title = "会话配置",
        onDismiss = onDismiss,
        heightFraction = 0.52f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SessionConfigItem(
                title = "会话提示词",
                subtitle = "自定义当前会话的系统指令与偏好",
                icon = Icons.Default.EditNote,
                onClick = {
                    onDismiss()
                    onOpenPrompt()
                },
            )
            SessionConfigItem(
                title = "会话技能",
                subtitle = "管理当前会话启用的技能扩展",
                icon = Icons.Default.Build,
                onClick = {
                    onDismiss()
                    onOpenSkills()
                },
            )
            SessionConfigItem(
                title = "会话中的 MCP",
                subtitle = "管理当前会话连接的 MCP 工具服务",
                icon = Icons.Default.Extension,
                onClick = {
                    onDismiss()
                    onOpenMcps()
                },
            )
            SessionConfigItem(
                title = "会话中的记忆",
                subtitle = "查看与管理当前会话沉淀的记忆",
                icon = Icons.Default.Psychology,
                onClick = {
                    onDismiss()
                    onOpenMemory()
                },
            )
        }
    }
}

@Composable
private fun SessionConfigItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ChatColors.secondaryBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = ChatColors.secondaryText,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = ChatColors.tertiaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}
