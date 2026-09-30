package com.openminis.app.ui.chat

import androidx.compose.ui.res.stringResource
import com.openminis.app.R

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/**
 * One session settings panel (docs/design/UI-DESIGN-LANGUAGE.md §9): what used to be split
 * between this sheet (prompt, skills, MCPs, memory) and a separate session-info sheet (token
 * usage, auto compact, fast mode — which had no entry point) now lives in a single place.
 */
@Composable
fun SessionConfigSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcps: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenTokenUsage: () -> Unit,
) {
    val autoCompactOn by viewModel.autoCompactEnabled.collectAsState()
    val showFastMode by viewModel.showFastModeToggle.collectAsState()
    val fastModeOn by viewModel.fastModeEnabled.collectAsState()

    StandardChatSheet(
        title = stringResource(R.string.session_config_title),
        onDismiss = onDismiss,
        heightFraction = 0.72f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SessionConfigItem(
                title = stringResource(R.string.session_advanced_settings_title),
                subtitle = stringResource(R.string.session_config_prompt_footer),
                icon = Icons.Default.EditNote,
                onClick = {
                    onDismiss()
                    onOpenPrompt()
                },
            )
            SessionConfigItem(
                title = stringResource(R.string.session_config_skills),
                subtitle = stringResource(R.string.session_config_skills_footer),
                icon = Icons.Default.Build,
                onClick = {
                    onDismiss()
                    onOpenSkills()
                },
            )
            SessionConfigItem(
                title = stringResource(R.string.session_mcps_title),
                subtitle = stringResource(R.string.session_config_mcps_footer),
                icon = Icons.Default.Extension,
                onClick = {
                    onDismiss()
                    onOpenMcps()
                },
            )
            SessionConfigItem(
                title = stringResource(R.string.session_memory_title),
                subtitle = stringResource(R.string.session_config_memory_footer),
                icon = Icons.Default.Psychology,
                onClick = {
                    onDismiss()
                    onOpenMemory()
                },
            )
            SessionConfigItem(
                title = stringResource(R.string.settings_token_usage),
                subtitle = stringResource(R.string.session_info_token_usage_footer),
                icon = Icons.Default.DataUsage,
                onClick = {
                    onDismiss()
                    onOpenTokenUsage()
                },
            )
            SessionConfigItem(
                title = stringResource(R.string.settings_auto_compact),
                subtitle = stringResource(R.string.session_info_auto_compact_footer),
                icon = Icons.Default.Compress,
                onClick = { viewModel.setAutoCompactEnabled(!autoCompactOn) },
                trailing = {
                    Switch(
                        checked = autoCompactOn,
                        onCheckedChange = { viewModel.setAutoCompactEnabled(it) },
                    )
                },
            )
            if (showFastMode) {
                SessionConfigItem(
                    title = stringResource(R.string.session_info_fast_mode),
                    subtitle = stringResource(R.string.session_info_fast_mode_footer),
                    icon = Icons.Default.Bolt,
                    onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                    trailing = {
                        Switch(
                            checked = fastModeOn,
                            onCheckedChange = { viewModel.setFastModeEnabled(it) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionConfigItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
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
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = ChatColors.tertiaryText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
