package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.mcp.server.MCPServerManager
import com.openminis.app.ui.components.MinisTextButton

/** Product surface for the existing local-only on-device MCP server. */
@Composable
internal fun MCPServerExposureSection() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf(MCPServerManager.status()) }
    var managedToken by remember { mutableStateOf(MCPServerManager.managedToken()) }
    var showScopeDialog by remember { mutableStateOf(false) }
    var scopeDraft by remember { mutableStateOf(managedToken?.scope ?: emptySet()) }

    fun refresh() {
        status = MCPServerManager.status()
        managedToken = MCPServerManager.managedToken()
    }

    fun copy(text: String, toast: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    SettingsSection(
        header = stringResource(R.string.mcp_expose_header),
        footer = stringResource(R.string.mcp_exposure_footer),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.mcp_server_local_title),
            subtitle = when {
                status.running -> stringResource(R.string.mcp_status_running, status.endpoint)
                status.enabled && status.lastError != null -> status.lastError
                status.configured -> stringResource(R.string.mcp_status_stopped, status.endpoint)
                else -> stringResource(R.string.mcp_create_token_first)
            },
            checked = status.enabled,
            enabled = status.configured,
            onCheckedChange = { enabled ->
                val ok = MCPServerManager.setEnabled(enabled)
                refresh()
                if (!ok) {
                    Toast.makeText(
                        context,
                        MCPServerManager.status().lastError ?: context.getString(R.string.mcp_start_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        SettingsRow(
            title = stringResource(R.string.voice_service_endpoint),
            subtitle = status.endpoint,
            onClick = { copy(status.endpoint, context.getString(R.string.mcp_endpoint_copied)) },
            showChevron = false,
            trailing = { Text(stringResource(R.string.common_copy), color = MaterialTheme.colorScheme.primary) },
        )
        SettingsRow(
            title = stringResource(R.string.mcp_access_token_label),
            subtitle = if (managedToken == null) {
                stringResource(R.string.mcp_not_configured)
            } else {
                stringResource(R.string.mcp_configured_scoped, managedToken!!.scope.size)
            },
            onClick = {
                val token = MCPServerManager.createOrRotateManagedToken()
                refresh()
                if (token != null) {
                    copy(token.token, context.getString(R.string.mcp_new_token_copied))
                } else {
                    Toast.makeText(context, context.getString(R.string.mcp_token_create_failed), Toast.LENGTH_SHORT).show()
                }
            },
            showChevron = false,
            trailing = {
                Text(
                    if (managedToken == null) stringResource(R.string.mcp_generate) else stringResource(R.string.mcp_rotate),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        SettingsRow(
            title = stringResource(R.string.mcp_exposed_tools_title),
            subtitle = if (managedToken == null) {
                stringResource(R.string.mcp_generate_token_first)
            } else {
                stringResource(R.string.mcp_tools_selected, managedToken!!.scope.size)
            },
            onClick = managedToken?.let { token ->
                {
                    scopeDraft = token.scope
                    showScopeDialog = true
                }
            },
            showChevron = managedToken != null,
        )
        SettingsRow(
            title = stringResource(R.string.mcp_copy_connection_config),
            subtitle = stringResource(R.string.mcp_copy_config_subtitle),
            onClick = managedToken?.let { token ->
                {
                    copy(MCPServerManager.connectionConfig(token), context.getString(R.string.mcp_config_copied))
                }
            },
            showChevron = false,
            trailing = if (managedToken != null) {
                { Text(stringResource(R.string.common_copy), color = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
            showDivider = managedToken == null,
        )
        if (managedToken != null) {
            SettingsRow(
                title = stringResource(R.string.mcp_revoke_settings_token),
                subtitle = if (status.tokenCount > 1) {
                    stringResource(R.string.mcp_revoke_others, status.tokenCount - 1)
                } else {
                    stringResource(R.string.mcp_revoke_last_note)
                },
                onClick = {
                    MCPServerManager.revokeManagedToken()
                    refresh()
                },
                showChevron = false,
                showDivider = false,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    val token = managedToken
    if (showScopeDialog && token != null) {
        val available = remember(showScopeDialog) { MCPServerManager.availableToolsForManagedToken() }
        AlertDialog(
            onDismissRequest = { showScopeDialog = false },
            title = { Text(stringResource(R.string.mcp_exposed_tools_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.mcp_scope_min_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    for (tool in available) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = tool in scopeDraft,
                                onCheckedChange = { checked ->
                                    scopeDraft = if (checked) scopeDraft + tool else scopeDraft - tool
                                },
                            )
                            Text(tool, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                MinisTextButton(
                    enabled = scopeDraft.isNotEmpty(),
                    onClick = {
                        if (MCPServerManager.updateManagedTokenScope(scopeDraft)) {
                            refresh()
                            showScopeDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showScopeDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
