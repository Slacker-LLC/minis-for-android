package com.openminis.app.ui.settings

import android.content.Intent
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.SafMountHelper
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.theme.minisSheetColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisModalBottomSheet

/**
 * Settings → Mount External Folders. Mirrors iOS MountedFoldersSettingsView.
 *
 * Lists user-picked SAF tree URIs that map to a real POSIX path on the host
 * (only `com.android.externalstorage.documents` is accepted at picker time).
 * Each entry shows an R/W / Locked / Read-only badge, the linux mount path,
 * and the resolved host path. Tap → MountDetailScreen. Swipe → remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountedFoldersScreen(
    store: MountedFoldersStore,
    onBack: () -> Unit,
    onMountClick: (mountId: String) -> Unit,
    onOpenSystemPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val isAtCapacity = entries.size >= MountedFoldersStore.MAX_MOUNTS

    var pendingPickedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDefaultName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    // [T-android-mount-add-reasons] The refusal that has a fix on this screen gets a button,
    // not just an explanation.
    var addErrorNeedsAllFilesAccess by remember { mutableStateOf(false) }

    // [T-android-mount-picker-landing] Shown once before handing off to the
    // system picker, explaining that Android forbids mounting the storage root.
    // See showPicker() below for why this is needed.
    var showPickerIntro by remember { mutableStateOf(false) }

    // T219 follow-up: re-read all-files-access state on every ON_RESUME so
    // returning from the system Settings page (where the user toggles the
    // permission) clears the banner without an app restart.
    var hasAllFilesAccess by remember { mutableStateOf(checkAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = checkAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 10 (Q) base ROMs grant storage via the normal runtime flow — request
    // READ + WRITE directly rather than pushing the user to a Settings page that
    // (on HarmonyOS/EMUI) may not exist. WRITE is required for two things the read
    // grant alone doesn't cover: probeWritable's write test (drives the R/W vs
    // read-only badge) and the agent's Direct Ubuntu writes into the bound folder —
    // without it those writes report success but land in a shadow FUSE view instead
    // of shared storage. Re-check on grant so the banner clears.
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->

        if (grants.values.any { it }) {
            hasAllFilesAccess = checkAllFilesAccess(context)
            scope.launch(Dispatchers.IO) { store.refreshWritability() }
        }
    }

    // [T-android-mount-add-reasons] The All Files Access page, shared by the banner and the
    // refusal dialog that names it as the reason.
    fun openAllFilesAccess() {
    when {
        // Android 11+: the grant lives in System & permissions like every other one.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> onOpenSystemPermissions()
        // Android 10: request the legacy storage runtime permissions
        // (READ for readdir, WRITE for the badge + agent writes).
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            legacyStorageLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
    }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Only on-device storage maps to a POSIX path Direct Ubuntu can bind. Reject
        // cloud providers (Drive, Dropbox, OneDrive) with a clear toast —
        // the spec defers Option B mirror-copy to a follow-up.
        if (uri.authority != "com.android.externalstorage.documents") {
            Toast.makeText(
                context,
                context.getString(R.string.mount_only_on_device_supported),
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (!SafMountHelper.handlePickerResult(context, uri)) {
            return@rememberLauncherForActivityResult
        }
        pendingPickedUri = uri
        pendingDefaultName = defaultMountName(uri)
    }

    SettingsScaffold(
        title = stringResource(R.string.mount_folders_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_section_files),
        actions = {
            IconButton(
                onClick = {
                    // On Android 10, a folder can readdir but still EACCES on
                    // write unless WRITE_EXTERNAL_STORAGE is granted at runtime
                    // (the ROM's single storage toggle often only grants READ).
                    // Request it up front so the mount probes as writable and
                    // the "Allow writes" toggle isn't stuck disabled.
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                        context.checkSelfPermission(
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        legacyStorageLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ),
                        )
                    } else {
                        showPickerIntro = true
                    }
                },
                enabled = !isAtCapacity,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        if (!hasAllFilesAccess) {
            AllFilesAccessBanner(onClick = { openAllFilesAccess() })
        }

        if (entries.isEmpty()) {
            EmptyState()
        } else {
            SettingsSection(
                header = stringResource(R.string.mount_folders_header),
                footer = stringResource(R.string.mount_folders_info_banner) +
                    if (isAtCapacity) "\n" + stringResource(R.string.mount_folders_limit_reached) else "",
            ) {
                entries.forEachIndexed { index, entry ->
                    MountRow(
                        entry = entry,
                        showDivider = index < entries.size - 1,
                        onClick = { onMountClick(entry.id) },
                    )
                }
            }
        }
    }

    val pickedUri = pendingPickedUri
    if (pickedUri != null) {
        AddMountSheet(
            sourceUri = pickedUri,
            initialName = pendingDefaultName,
            onDismiss = { pendingPickedUri = null },
                            onConfirm = { name, allowWrite ->
                scope.launch(Dispatchers.IO) {
                    val result = store.add(pickedUri, name, allowWrite)
                    withContext(Dispatchers.Main.immediate) {
                        if (result is MountedFoldersStore.AddResult.Rejected) {
                            addError = context.getString(result.failure.messageRes())
                            addErrorNeedsAllFilesAccess =
                                result.failure == MountedFoldersStore.AddFailure.MISSING_ALL_FILES_ACCESS
                        }
                        pendingPickedUri = null
                    }
                }
            },
        )
    }

    addError?.let { msg ->
        val offersAccess = addErrorNeedsAllFilesAccess
        fun dismiss() {
            addError = null
            addErrorNeedsAllFilesAccess = false
        }
        MinisAlertDialog(
            onDismissRequest = { dismiss() },
            confirmButton = {
                if (offersAccess) {
                    // [T-android-mount-add-reasons] The refusal names All Files Access as the
                    // reason, so the fix is one tap from the dialog instead of a hunt through
                    // system settings.
                    MinisTextButton(onClick = {
                        dismiss()
                        openAllFilesAccess()
                    }) {
                        Text(stringResource(R.string.mount_add_grant_action))
                    }
                } else {
                    MinisTextButton(onClick = { dismiss() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            },
            dismissButton = if (offersAccess) {
                {
                    MinisTextButton(onClick = { dismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else {
                null
            },
            text = { Text(msg) },
        )
    }

    // [T-android-mount-picker-landing] GH#93: the system picker used to open at
    // whatever location it liked — on the reporter's Xiaomi that was the shared
    // storage ROOT, where Android shows "无法使用此文件夹 / can't use this folder",
    // greys out "Use this folder", and (being already the top level) offers no
    // way back. The user reads that as being trapped.
    //
    // Two halves to the fix; this is the half we control from our side of the
    // hand-off. The picker's own chrome belongs to the system + OEM, so we
    // cannot add a back button to it — what we CAN do is (a) explain the
    // restriction in our own words before the user leaves our UI, and
    // (b) land them somewhere selectable (see initialPickerUri()).
    if (showPickerIntro) {
        MinisAlertDialog(
            onDismissRequest = { showPickerIntro = false },
            title = { Text(stringResource(R.string.mount_picker_intro_title)) },
            text = { Text(stringResource(R.string.mount_picker_intro_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showPickerIntro = false
                    pickerLauncher.launch(initialPickerUri())
                }) {
                    Text(stringResource(R.string.mount_picker_intro_continue))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showPickerIntro = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * [T-android-mount-picker-landing] Where the system folder picker should open.
 *
 * Returning null (the old behaviour) lets the picker choose, and on some ROMs
 * that is the shared-storage ROOT — the one place Android refuses to grant
 * (blocked since Android 11, alongside Android/data and Android/obb). The user
 * lands on a greyed-out "Use this folder" with no parent left to go back to,
 * which is exactly what GH#93 reported.
 *
 * `Documents` is chosen because it satisfies all three constraints at once:
 *  • NOT on Android's blocked list, so it is actually selectable;
 *  • always present on a real device (a non-existent initial URI is silently
 *    ignored, which would drop the user back at the broken default);
 *  • one level below the root, so the breadcrumb still lets them navigate
 *    anywhere else — this positions the user without deciding for them.
 *
 * Media dirs (DCIM/Pictures/Music/Movies) were rejected as too narrow in
 * meaning, and Download as more "transient scratch" than the long-lived folder
 * a mount implies.
 */
private fun initialPickerUri(): Uri? = runCatching {
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Documents",
    )
}.getOrNull()

@Composable
private fun AllFilesAccessBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ChatColors.warn.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = ChatColors.warn,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.mount_all_files_access_required),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.mount_all_files_access_required_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Whether the app currently has enough storage access for Direct Ubuntu to readdir a
 * mounted external folder's real contents.
 *
 * - Android 11+ (R): needs MANAGE_EXTERNAL_STORAGE. It's a special permission
 *   granted only via a system Settings page, never the runtime flow.
 * - Android 10 (Q) base ROMs — including HarmonyOS 2.x / EMUI 11 (Mate 40
 *   ELS-AN00 etc.) that never expose the "All Files Access" page: legacy
 *   storage is what feeds the Direct Ubuntu bind view, and that
 *   requires READ_EXTERNAL_STORAGE to actually be granted. Returning true
 *   unconditionally here (the old behaviour) hid the banner even when the
 *   user hadn't granted "允许访问文件", leaving them stuck on an empty mount.
 * - Below Q: legacy storage is always on, no opt-in needed.
 */
private fun checkAllFilesAccess(context: android.content.Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderShared,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.mount_folders_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.mount_folders_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MountRow(
    entry: MountedFoldersStore.Entry,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    // The URI-derived volume/segments stay in the persisted mount contract;
    // the settings screen displays the provider's stable label.
    val source = entry.sourceDisplayName.ifEmpty { stringResource(R.string.mount_path_unavailable) }
    SettingsRow(
        title = entry.name,
        subtitle = "/var/minis/mounts/${entry.name} ← $source",
        icon = Icons.Outlined.Folder,
        iconColor = ChatColors.ok,
        onClick = onClick,
        showDivider = showDivider,
        trailing = { AccessBadge(entry = entry) },
        minHeight = 64.dp,
    )
}

/**
 * Access pill: R/W (green) when fully writable, Locked (purple) when the OS
 * permits writes but the user toggled them off, Read-only (orange) when the
 * grant itself is read-only.
 */
@Composable
private fun AccessBadge(entry: MountedFoldersStore.Entry) {
    val (text, color) = when {
        !entry.isWritable -> stringResource(R.string.mount_badge_readonly) to ChatColors.warn
        !entry.userAllowWrite -> stringResource(R.string.mount_badge_locked) to Color(0xFFAF52DE)
        else -> stringResource(R.string.mount_badge_rw) to ChatColors.ok
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMountSheet(
    sourceUri: Uri,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, allowWrite: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initialName) }
    var allowWrite by remember { mutableStateOf(true) }

    MinisModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (LocalUiStyle.current == UiStyle.GLASS) Color.Transparent else minisSheetColor(),
    ) {
        GlassSheetWindowBlur()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSheetSurface()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), fontSize = 17.sp)
                }
                Text(
                    text = stringResource(R.string.mount_add_dialog_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                MinisTextButton(
                    onClick = { onConfirm(name.trim(), allowWrite) },
                    enabled = isValidMountName(name),
                ) {
                    Text(stringResource(R.string.mount_add_confirm), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            SettingsSection(modifier = Modifier.padding(top = 8.dp)) {
                SettingsRow(
                    title = stringResource(R.string.mount_add_source_path),
                    showDivider = false,
                    trailing = {
                        Text(
                            text = SafMountHelper.treeDisplayPath(sourceUri),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    },
                )
            }
            SettingsSection(footer = stringResource(R.string.mount_add_name_hint)) {
                SettingsRow(
                    title = stringResource(R.string.mount_add_name_label),
                    trailing = {
                        androidx.compose.foundation.text.BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.widthIn(min = 80.dp, max = 200.dp),
                        )
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.mount_add_allow_writes),
                    subtitle = stringResource(R.string.mount_add_allow_writes_desc),
                    checked = allowWrite,
                    onCheckedChange = { allowWrite = it },
                    showDivider = false,
                )
            }
        }
    }
}

internal fun isValidMountName(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
    if (trimmed.contains('/') || trimmed.contains(' ')) return false
    return true
}

/**
 * Default mount name from a SAF tree URI. Special-cases the
 * `Android/data/<pkg>/files` shape so the suggested name is the owning
 * package rather than the meaningless `files`. Otherwise the leaf segment
 * after the volume's `:` separator is used.
 */
internal fun defaultMountName(uri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
    val rel = docId.substringAfter(':', docId)
    val segments = rel.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "mount"

    // Android/data/<pkg>/files → <pkg>
    val androidIdx = segments.indexOf("Android")
    if (androidIdx >= 0 && androidIdx + 2 < segments.size && segments[androidIdx + 1] == "data") {
        return sanitize(segments[androidIdx + 2])
    }
    return sanitize(segments.last())
}

private fun sanitize(raw: String): String {
    val cleaned = raw.trim().replace('/', '-').replace(' ', '_')
    return cleaned.ifEmpty { "mount" }
}

/** [T-android-mount-add-reasons] One sentence per refusal, instead of one sentence for all of them. */
private fun MountedFoldersStore.AddFailure.messageRes(): Int = when (this) {
    MountedFoldersStore.AddFailure.NAME_UNUSABLE -> R.string.mount_add_failed_name_unusable
    MountedFoldersStore.AddFailure.NAME_TAKEN -> R.string.mount_add_failed_name_taken
    MountedFoldersStore.AddFailure.AT_CAPACITY -> R.string.mount_add_failed_at_capacity
    MountedFoldersStore.AddFailure.MISSING_ALL_FILES_ACCESS -> R.string.mount_add_failed_all_files_access
    MountedFoldersStore.AddFailure.MISSING_READ_GRANT -> R.string.mount_add_failed_read_grant
    MountedFoldersStore.AddFailure.UNSUPPORTED_URI -> R.string.mount_add_failed_unsupported_uri
    MountedFoldersStore.AddFailure.UNRESOLVABLE_PATH -> R.string.mount_add_failed_unresolvable_path
    MountedFoldersStore.AddFailure.COMMIT_FAILED -> R.string.mount_add_failed_commit
}
