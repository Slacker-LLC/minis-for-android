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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
        header = "Expose Minis",
        footer = "Streamable HTTP on 127.0.0.1 only. LAN/TLS exposure is not enabled here. " +
            "New Settings tokens start with only MCP_ALLOWED tools; you can explicitly add or remove exposed tools below.",
    ) {
        SettingsSwitchRow(
            title = "Local MCP server",
            subtitle = when {
                status.running -> "Running · ${status.endpoint}"
                status.enabled && status.lastError != null -> status.lastError
                status.configured -> "Stopped · ${status.endpoint}"
                else -> "Create an access token before enabling"
            },
            checked = status.enabled,
            enabled = status.configured,
            onCheckedChange = { enabled ->
                val ok = MCPServerManager.setEnabled(enabled)
                refresh()
                if (!ok) {
                    Toast.makeText(
                        context,
                        MCPServerManager.status().lastError ?: "MCP server could not start",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        SettingsRow(
            title = "Endpoint",
            subtitle = status.endpoint,
            onClick = { copy(status.endpoint, "Endpoint copied") },
            showChevron = false,
            trailing = { Text("Copy", color = MaterialTheme.colorScheme.primary) },
        )
        SettingsRow(
            title = "Access token",
            subtitle = if (managedToken == null) {
                "Not configured"
            } else {
                "Configured · ${managedToken!!.scope.size} scoped tools"
            },
            onClick = {
                val token = MCPServerManager.createOrRotateManagedToken()
                refresh()
                if (token != null) {
                    copy(token.token, "New access token copied")
                } else {
                    Toast.makeText(context, "Could not create MCP access token", Toast.LENGTH_SHORT).show()
                }
            },
            showChevron = false,
            trailing = {
                Text(
                    if (managedToken == null) "Generate" else "Rotate",
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        SettingsRow(
            title = "Exposed tools",
            subtitle = if (managedToken == null) {
                "Generate a token first"
            } else {
                "${managedToken!!.scope.size} selected · tap to edit"
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
            title = "Copy connection config",
            subtitle = "Claude Desktop / Cursor-compatible mcpServers JSON",
            onClick = managedToken?.let { token ->
                {
                    copy(MCPServerManager.connectionConfig(token), "Connection config copied")
                }
            },
            showChevron = false,
            trailing = if (managedToken != null) {
                { Text("Copy", color = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
            showDivider = managedToken == null,
        )
        if (managedToken != null) {
            SettingsRow(
                title = "Revoke Settings token",
                subtitle = if (status.tokenCount > 1) {
                    "Revokes this token only; ${status.tokenCount - 1} other token(s) remain"
                } else {
                    "Server is disabled automatically when no token remains"
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
            title = { Text("Exposed tools") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "At least one tool must remain selected. Tools marked MCP_CONFIRM by the central policy still require approval when selected.",
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
                ) { Text("Save") }
            },
            dismissButton = {
                MinisTextButton(onClick = { showScopeDialog = false }) { Text("Cancel") }
            },
        )
    }
}
