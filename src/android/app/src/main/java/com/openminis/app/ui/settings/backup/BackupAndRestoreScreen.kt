package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsSegmented
import com.openminis.app.backup.BackupCategory
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.theme.ChatColors


/**
 * [T-android-backup-ui] Backup & Restore — the Android peer of iOS
 * `BackupAndRestoreView`: a segmented [Backup | Restore] container over two
 * sub-forms. Reached from Settings → Storage.
 */
@Composable
fun BackupAndRestoreScreen(
    onBack: () -> Unit,
    onManageDestinations: () -> Unit = {},
    // [T-android-restore-server-list] Open the "Restore from Server" list —
    // the servers you can RESTORE FROM, plus an Add Server entry. Distinct
    // from [onManageDestinations], which is the editable destinations screen
    // (rename/remove/enable) reached from the Backup tab: same servers, a
    // different verb, so they are deliberately different screens.
    onChooseRestoreServer: () -> Unit = {},
    onOpenHistoryRecord: (String) -> Unit = {},
    onBrowseDestination: (String) -> Unit = {},
) {
    val vm: BackupViewModel = viewModel()
    // 0 = Backup, 1 = Restore. Saveable, not plain `remember`: navigating to
    // the Add Server form takes this screen off the back stack, and coming
    // back on plain `remember` reset the picker to Backup — dropping a user
    // who cancelled out of "Choose from Server…" onto the wrong tab, one step
    // further from where they started than when they left. A tab index is not
    // a secret, so unlike the passphrases below it is safe to persist.
    var tab by rememberSaveable { mutableStateOf(0) }
    // Hoisted out of the tabs so switching tabs does not discard them — see
    // [T-restore-keep-tab-state] below.
    var backupPassphrase by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }

    SettingsScaffold(
        title = stringResource(R.string.backup_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_section_files),
    ) {
        SettingsSegmented(
            options = listOf(
                stringResource(R.string.backup_tab_backup),
                stringResource(R.string.backup_tab_restore),
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // [T-restore-keep-tab-state] Only ONE tab is composed at a time, so the
        // other leaves the tree and plain `remember` state dies with it. The
        // picked package survives regardless — it lives in the ViewModel — but
        // a half-typed passphrase did not, so glancing at the other tab
        // mid-restore silently emptied the field.
        //
        // Hoisted to the container, which outlives both tabs. NOT
        // `rememberSaveable`: that would write the passphrase into the
        // saved-instance-state bundle, persisting a secret to disk to save the
        // user a few keystrokes.
        if (tab == 0) {
            BackupTab(
                vm, onManageDestinations, onOpenHistoryRecord,
                passphrase = backupPassphrase,
                onPassphraseChange = { backupPassphrase = it },
                confirm = backupConfirm,
                onConfirmChange = { backupConfirm = it },
            )
        } else {
            RestoreTab(
                vm, onBrowseDestination,
                onChooseRestoreServer = onChooseRestoreServer,
                passphrase = restorePassphrase,
                onPassphraseChange = { restorePassphrase = it },
            )
        }
    }
}


// ─── Backup tab ──────────────────────────────────────────────────────────

@Composable
internal fun BackupTab(
    vm: BackupViewModel,
    onManageDestinations: () -> Unit = {},
    onOpenHistoryRecord: (String) -> Unit = {},
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected by vm.selected.collectAsState()
    val encrypt by vm.encrypt.collectAsState()
    val maxFileSizeMB by vm.maxFileSizeMB.collectAsState()
    val running by vm.isRunning.collectAsState()
    val status by vm.statusText.collectAsState()
    val error by vm.errorText.collectAsState()
    val destinations by vm.destinations.collectAsState()
    val deviceEnabled by vm.deviceEnabled.collectAsState()
    val historyRecords by vm.historyRecords.collectAsState()
    val lastResult by vm.lastResult.collectAsState()

    // Re-read destinations every time this tab appears: the user may have just
    // added one via "Manage Destinations…" and navigated back, and a stale
    // empty list would keep the Start button disabled with no way to recover.
    LaunchedEffect(Unit) {
        // [T-android-backup-transient-success] Drop a PREVIOUS visit's success
        // card on the way IN, not on the way out. Clearing on dispose would
        // yank it away the moment the user tapped into the run they were
        // reading about; by the time this fires again, the visit that produced
        // the result has ended, which is exactly when the card stops being
        // useful. The same facts remain in Backup History.
        //
        // Only a settled, fully-delivered run is dropped — a failure is the
        // one result worth coming back to.
        vm.clearSettledSuccess()
        vm.refreshDestinations()
        vm.refreshHistory()
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshDestinations()
                vm.refreshHistory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val passphraseValid = !encrypt || (passphrase.isNotEmpty() && passphrase == confirm)
    val hasFileTree = selected.any { it.carriesFileTree }

    // -- Include --
    // Footer changes with the Max Per-File Size selection, but only when a
    // file-carrying category is selected — otherwise the cap is meaningless
    // (mirrors iOS BackupSettingsView).
    val includeFooter = when {
        !hasFileTree -> stringResource(R.string.backup_include_footer)
        maxFileSizeMB == MAX_FILE_NO_FILES ->
            stringResource(R.string.backup_file_footer_no_files)
        maxFileSizeMB != MAX_FILE_UNLIMITED ->
            stringResource(R.string.backup_file_footer_limited, maxFileSizeMB)
        else -> stringResource(R.string.backup_file_footer_unlimited)
    }
    SettingsSection(
        header = stringResource(R.string.backup_section_include),
        footer = includeFooter,
    ) {
        val cats = BackupCategory.backupable
        cats.forEachIndexed { i, cat ->
            CategorySwitchRow(
                title = stringResource(categoryNameRes(cat)),
                icon = categoryIcon(cat),
                iconColor = categoryTint(cat),
                checked = cat in selected,
                onCheckedChange = { vm.toggleCategory(cat, it) },
                enabled = !running,
                showDivider = i < cats.lastIndex || hasFileTree,
            )
        }
        // Max Per-File Size picker — only when a file-carrying category is on.
        if (hasFileTree) {
            MaxFileSizeRow(
                current = maxFileSizeMB,
                enabled = !running,
                onSelect = { vm.setMaxFileSizeMB(it) },
            )
        }
    }

    // -- Encryption --
    SettingsSection(
        header = stringResource(R.string.backup_section_encryption),
        footer = if (encrypt) {
            stringResource(R.string.backup_encrypt_footer_on)
        } else if (BackupCategory.PROVIDERS in selected ||
            BackupCategory.ENVIRONMENT_VARIABLES in selected ||
            // [T-backup-credentials-without-encryption] MCP belongs here for a
            // reason that is easy to miss: mcp_servers.json is copied VERBATIM
            // (BackupExporter.exportMcpServers), so a user-set Authorization
            // header travels inside it — a secret, in an unencrypted package,
            // whether or not PROVIDERS was selected. iOS lists the same three.
            BackupCategory.MCP_SERVERS in selected
        ) {
            stringResource(R.string.backup_encrypt_footer_off_creds)
        } else {
            stringResource(R.string.backup_encrypt_footer_off)
        },
    ) {
        CategorySwitchRow(
            title = stringResource(R.string.backup_encrypt_backup),
            icon = Icons.Outlined.Lock,
            iconColor = ChatColors.ok,
            checked = encrypt,
            onCheckedChange = { vm.setEncrypt(it) },
            enabled = !running,
            showDivider = encrypt,
        )
        if (encrypt) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = onConfirmChange,
                    label = { Text(stringResource(R.string.backup_confirm_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirm.isNotEmpty() && confirm != passphrase,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirm.isNotEmpty() && confirm != passphrase) {
                    Text(
                        stringResource(R.string.backup_passphrase_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    // -- Destinations --
    // [T-android-backup-destination-gate] Inline list, mirroring iOS's
    // `destinationSection`. Android previously offered only a "Manage
    // Destinations…" button, so the backup screen never showed WHETHER a
    // destination existed — which is how "back up with none configured"
    // stayed invisible.
    val hasDestination = deviceEnabled || destinations.any { it.enabled }
    DestinationsSection(
        destinations = destinations,
        deviceEnabled = deviceEnabled,
        enabled = !running,
        onManage = onManageDestinations,
        onToggle = vm::setDestinationEnabled,
        onToggleDevice = vm::setDeviceEnabled,
    )

    // -- Action --
    Column(Modifier.padding(16.dp)) {
        // [T-android-backup-stop] ONE button that toggles. While a backup runs
        // it says "Stop Backup" and stops it — nothing else about the row
        // changes.
        //
        // It used to turn into a disabled spinner captioned with the live
        // status, which put the progress report inside the control: the row a
        // user reaches for moved and greyed out at the very moment they wanted
        // to act, and there was no way to stop a running backup at all.
        // Progress belongs in the run's own entry in Backup History below,
        // which shows the live status line and the whole log; the control
        // stays a control. Same reasoning, and the same shape, as iOS.
        MinisButton(
            onClick = {
                if (running) vm.stopExport()
                else vm.startExport(passphrase.takeIf { encrypt })
            },
            // The start-time requirements gate STARTING only. Applying them
            // while running would disable the button mid-run and leave no way
            // to stop the backup from this screen.
            //
            // A package with no destination reaches only our own sandbox and
            // dies with the app it protects — that is not a backup, so the
            // button refuses rather than producing one (iOS parity).
            enabled = running || (
                selected.isNotEmpty() && passphraseValid && hasDestination
                ),
            destructive = running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Stop is the destructive counterpart, so it gets the stop glyph
            // in red rather than the same cloud-upload icon in a
            // different colour.
            PrimaryActionContent(
                icon = if (running) Icons.Outlined.Stop else Icons.Outlined.CloudUpload,
                label = stringResource(
                    if (running) R.string.backup_stop else R.string.backup_start,
                ),
                // The backup button reports progress in Backup History below,
                // not in the control, so it keeps its icon while running.
                busy = false,
            )
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Why the button is disabled. Destination first: it is the requirement
        // a new user is most likely to be missing, since the categories arrive
        // already selected (same ordering and rationale as iOS).
        if (!running) {
            val hint = when {
                !hasDestination -> stringResource(R.string.backup_needs_destination)
                selected.isEmpty() -> stringResource(R.string.backup_needs_category)
                encrypt && passphrase.isEmpty() -> stringResource(R.string.backup_needs_passphrase)
                else -> null
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }


    // -- Just finished --
    // Under the button, as on iOS: a transient report of the run the user just
    // watched, gone by their next visit.
    lastResult?.let { r ->
        if (!running) {
            SettingsSection(
                header = stringResource(R.string.backup_ready),
                footer = backupResultFooter(r),
            ) {
                DetailValueRow(
                    label = stringResource(R.string.backup_history_size),
                    value = humanBytes(r.totalBytes),
                    showDivider = r.skippedFiles > 0 || r.destinations.isNotEmpty(),
                )
                if (r.skippedFiles > 0) {
                    DetailValueRow(
                        // "Too large" would be wrong when the user's own cap
                        // said not to include files at all — nothing exceeded
                        // anything then.
                        label = stringResource(
                            if (maxFileSizeMB == MAX_FILE_NO_FILES) R.string.backup_result_files_not_included
                            else R.string.backup_result_excluded_too_large,
                        ),
                        value = stringResource(R.string.backup_history_file_count, r.skippedFiles),
                        showDivider = r.destinations.isNotEmpty(),
                    )
                }
                // Per destination, not one combined status: "2 of 3 saved" is
                // only actionable if the user can see WHICH one failed.
                r.destinations.forEachIndexed { i, d ->
                    ResultDestinationRow(
                        outcome = d,
                        showDivider = i < r.destinations.lastIndex,
                    )
                }
            }
        }
    }

    // -- History --
    BackupHistorySection(
        records = historyRecords,
        onOpen = onOpenHistoryRecord,
    )
}

























