from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
UP = "4ef29002e88db1e20e462ec2ff46916e8a7dcb45"
ANDROID = Path("src/android/app/src/main")


def path(rel: str) -> Path:
    return ROOT / rel


def read(rel: str) -> str:
    return path(rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    p = path(rel)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text.rstrip() + "\n", encoding="utf-8")


def upstream(rel: str) -> str:
    return subprocess.check_output(
        ["git", "show", f"{UP}:{rel}"], cwd=ROOT, text=True, encoding="utf-8"
    )


def replace_once(s: str, old: str, new: str, label: str) -> str:
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 anchor, got {n}")
    return s.replace(old, new, 1)


def add_import(s: str, import_line: str, after_prefix: str = "package ") -> str:
    if import_line in s:
        return s
    # Insert after the last import in the first import block if possible.
    imports = list(re.finditer(r"(?m)^import .+$", s))
    if imports:
        pos = imports[-1].end()
        return s[:pos] + "\n" + import_line + s[pos:]
    m = re.search(r"(?m)^package .+$", s)
    if not m:
        raise SystemExit(f"cannot add import {import_line}")
    return s[:m.end()] + "\n\n" + import_line + s[m.end():]


# ---------------------------------------------------------------------------
# A) Live compaction progress + cancel.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
s = read(rel)

if "data class CompactProgress(" not in s:
    anchor = "    private val _isCompacting = MutableStateFlow(false)\n"
    block = '''    data class CompactProgress(
        val startedAtMs: Long,
        val depth: Int = 0,
        val callsIssued: Int = 0,
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        val timeoutSeconds: Int = 0,
    )

    private val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

'''
    if anchor not in s:
        raise SystemExit("ChatViewModel: isCompacting anchor missing")
    s = s.replace(anchor, block + anchor, 1)

if "private var compactJob: Job?" not in s:
    anchor = "    private val compactCallsIssued = AtomicInteger(0)\n"
    if anchor not in s:
        raise SystemExit("ChatViewModel: compactCallsIssued anchor missing")
    extra = '''    private val compactCallsIssued = AtomicInteger(0)

    /** Job for the active manual/context compaction run. */
    private var compactJob: Job? = null

    /** Cancel an in-flight compaction. No-op when nothing is running. */
    fun cancelCompact() {
        val job = compactJob ?: return
        if (!job.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        job.cancel(CancellationException("compact cancelled by user"))
    }
'''
    s = s.replace(anchor, extra, 1)

# Publish progress before the launch and retain the Job.
if "_compactProgress.value = CompactProgress(" not in s:
    pat = re.compile(
        r"(?P<i>\s*)markStarted\(\)\n"
        r"(?P=i)_isCompacting\.value = true\n"
        r"(?P=i)compactCallsIssued\.set\(0\)\n"
        r"(?P=i)val compactTimeoutMs = compactTimeoutMsFor\(\n"
        r"(?P=i)    buildConversationTextForSummary\(toCompact\)\.length,\n"
        r"(?P=i)\)\n"
        r"(?P=i)viewModelScope\.launch\(Dispatchers\.IO\) \{"
    )
    m = pat.search(s)
    if not m:
        raise SystemExit("ChatViewModel: compact launch anchor missing")
    i = m.group("i")
    repl = (
        f"{i}markStarted()\n"
        f"{i}_isCompacting.value = true\n"
        f"{i}compactCallsIssued.set(0)\n"
        f"{i}val transcriptChars = buildConversationTextForSummary(toCompact).length\n"
        f"{i}val compactTimeoutMs = compactTimeoutMsFor(transcriptChars)\n"
        f"{i}_compactProgress.value = CompactProgress(\n"
        f"{i}    startedAtMs = System.currentTimeMillis(),\n"
        f"{i}    depth = 0,\n"
        f"{i}    callsIssued = 0,\n"
        f"{i}    callBudget = MAX_COMPACT_LLM_CALLS,\n"
        f"{i}    timeoutSeconds = (compactTimeoutMs / 1000L).toInt(),\n"
        f"{i})\n"
        f"{i}compactJob = viewModelScope.launch(Dispatchers.IO) {{"
    )
    s = pat.sub(lambda _: repl, s, count=1)

# Always clear progress when compaction releases the lock.
if "_compactProgress.value = null" not in s:
    s, n = re.subn(
        r"(?m)^(\s*)_isCompacting\.value = false\s*$",
        lambda m: m.group(0) + "\n" + m.group(1) + "_compactProgress.value = null",
        s,
        count=1,
    )
    if n != 1:
        raise SystemExit("ChatViewModel: compact finally anchor missing")

# Publish split depth/call number immediately before the provider request.
if "_compactProgress.value = _compactProgress.value?.copy(" not in s:
    pat = re.compile(
        r"(?P<i>\s*)val callNumber = compactCallsIssued\.incrementAndGet\(\)\n"
        r"(?P=i)if \(callNumber > MAX_COMPACT_LLM_CALLS\) \{\n"
        r"(?P=i)    throw CompactCallBudgetExceeded\(\)\n"
        r"(?P=i)\}"
    )
    m = pat.search(s)
    if not m:
        raise SystemExit("ChatViewModel: split call counter anchor missing")
    i = m.group("i")
    repl = (
        f"{i}val callNumber = compactCallsIssued.incrementAndGet()\n"
        f"{i}if (callNumber > MAX_COMPACT_LLM_CALLS) {{\n"
        f"{i}    throw CompactCallBudgetExceeded()\n"
        f"{i}}}\n"
        f"{i}_compactProgress.value = _compactProgress.value?.copy(\n"
        f"{i}    depth = depth,\n"
        f"{i}    callsIssued = callNumber,\n"
        f"{i})"
    )
    s = pat.sub(lambda _: repl, s, count=1)

write(rel, s)

# Chat indicator component: copy only the upstream compact-progress function,
# not the whole file (Fork owns other indicator UI).
rel = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatIndicators.kt"
s = read(rel)
u = upstream(rel)
if "fun CompactProgressIndicator(" not in s:
    start = u.index("@Composable\ninternal fun CompactProgressIndicator(")
    # Function is followed by the next top-level KDoc/Composable declaration.
    tail = u[start:]
    m = re.search(r"\n(?=/\*\*|@Composable\n(?:internal|private|fun))", tail[1:])
    if m:
        fn = tail[: m.start() + 1]
    else:
        fn = tail
    # Extracting by first next declaration can be fragile; verify body marker.
    if "compact_progress_split" not in fn or "onCancel" not in fn:
        # Fall back to brace-balanced extraction.
        sig = start
        brace = u.index("{", sig)
        depth = 0
        end = None
        for idx in range(brace, len(u)):
            if u[idx] == "{": depth += 1
            elif u[idx] == "}":
                depth -= 1
                if depth == 0:
                    end = idx + 1
                    break
        if end is None:
            raise SystemExit("ChatIndicators: cannot extract compact indicator")
        fn = u[start:end]
    # Add any imports used by the upstream function that Fork doesn't already have.
    for imp in [
        "import androidx.compose.runtime.LaunchedEffect",
        "import androidx.compose.runtime.getValue",
        "import androidx.compose.runtime.mutableLongStateOf",
        "import androidx.compose.runtime.remember",
        "import androidx.compose.runtime.setValue",
        "import kotlinx.coroutines.delay",
    ]:
        if imp in u:
            s = add_import(s, imp)
    s = s.rstrip() + "\n\n" + fn.strip() + "\n"
write(rel, s)

# ChatScreen wiring.
rel = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt"
s = read(rel)
if "val compactProgress by viewModel.compactProgress.collectAsState()" not in s:
    anchor = "    val canResume by viewModel.canResume.collectAsState()\n"
    if anchor not in s:
        raise SystemExit("ChatScreen: canResume anchor missing")
    s = s.replace(anchor, anchor + "    val compactProgress by viewModel.compactProgress.collectAsState()\n", 1)
if "key = \"__compact_progress__\"" not in s:
    anchor = "                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) {\n"
    if anchor not in s:
        raise SystemExit("ChatScreen: resume banner anchor missing")
    block = '''                    compactProgress?.let { progress ->
                        item(key = "__compact_progress__", contentType = "compact_progress") {
                            CompactProgressIndicator(
                                progress = progress,
                                onCancel = { viewModel.cancelCompact() },
                            )
                        }
                    }
'''
    s = s.replace(anchor, block + anchor, 1)
write(rel, s)

# Add compact progress strings from upstream where translations exist.
for target in sorted((ROOT / "src/android/app/src/main/res").glob("values*/strings.xml")):
    relp = target.relative_to(ROOT).as_posix()
    t = target.read_text(encoding="utf-8")
    if 'name="compact_progress"' in t:
        continue
    try:
        us = upstream(relp)
    except subprocess.CalledProcessError:
        continue
    entries = []
    for name in ("compact_progress", "compact_progress_split"):
        m = re.search(rf"(?m)^\s*<string name=\"{name}\"[^>]*>.*?</string>\s*$", us)
        if m:
            entries.append(m.group(0).strip())
    if entries:
        t = t.replace("</resources>", "    " + "\n    ".join(entries) + "\n</resources>")
        target.write_text(t.rstrip() + "\n", encoding="utf-8")

# ---------------------------------------------------------------------------
# B) Standalone bottom-sheet text-field glyph padding.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/ui/components/SectionTextField.kt"
s = read(rel)
if "contentHorizontalPadding: Dp = 0.dp" not in s:
    s = add_import(s, "import androidx.compose.ui.unit.Dp")
    s = replace_once(
        s,
        "    containerColor: Color? = null,\n) {",
        "    containerColor: Color? = null,\n    contentHorizontalPadding: Dp = 0.dp,\n) {",
        "SectionTextField parameter",
    )
    s = replace_once(
        s,
        "        horizontal = 0.dp,\n        vertical = SectionDesign.RowVerticalPadding,",
        "        horizontal = contentHorizontalPadding,\n        vertical = SectionDesign.RowVerticalPadding,",
        "SectionTextField padding",
    )
write(rel, s)

rel = "src/android/app/src/main/java/com/openminis/app/ui/sessions/GroupPickerSheet.kt"
s = read(rel)
if s.count("contentHorizontalPadding = 16.dp") < 2:
    # Exactly two standalone SectionTextFields use the contrasting sheet fill.
    needle = "                containerColor = SectionDesign.screenBackgroundColor(),\n"
    count = s.count(needle)
    if count < 2:
        raise SystemExit(f"GroupPickerSheet: expected >=2 text field anchors, got {count}")
    s = s.replace(needle, needle + "                contentHorizontalPadding = 16.dp,\n", 2)
write(rel, s)

# ---------------------------------------------------------------------------
# C) Locale-aware display uppercase (Turkish dotted-I correctness).
# ---------------------------------------------------------------------------
helper_rel = "src/android/app/src/main/java/com/openminis/app/i18n/LocaleAwareCase.kt"
if not path(helper_rel).exists():
    write(helper_rel, upstream(helper_rel))

case_files = [
    "src/android/app/src/main/java/com/openminis/app/ui/components/SettingsSection.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/chat/TokenUsageSheet.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/chat/MemoryDetailScreens.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsScreen.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsComponents.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/settings/SkillsManagementScreen.kt",
]
for rel in case_files:
    p = path(rel)
    if not p.exists():
        continue
    s = p.read_text(encoding="utf-8")
    u = upstream(rel)
    changed = False
    # Only convert expressions for which upstream explicitly uses the display helper.
    for m in re.finditer(r"([A-Za-z_][A-Za-z0-9_?.()]*)\.uppercaseForDisplay\(\)", u):
        expr = m.group(1)
        old = f"{expr}.uppercase()"
        new = f"{expr}.uppercaseForDisplay()"
        if old in s:
            s = s.replace(old, new)
            changed = True
    if changed:
        s = add_import(s, "import com.openminis.app.i18n.uppercaseForDisplay")
        write(rel, s)

# ---------------------------------------------------------------------------
# D1) Memory list date never wraps.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt"
s = read(rel)
if "T-android-memory-row-date-wrap" not in s:
    old = '''                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
'''
    new = '''                // [T-android-memory-row-date-wrap] Let the name/size group
                // flex and truncate; the fixed date remains one line.
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
'''
    s = replace_once(s, old, new, "MemoryManagement row date")
write(rel, s)

# ---------------------------------------------------------------------------
# D2) Web preview status-bar state: restore from theme, not captured sibling state.
# ---------------------------------------------------------------------------
for rel in [
    "src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewBottomSheet.kt",
    "src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewFullscreenScreen.kt",
]:
    s = read(rel)
    s = s.replace("import androidx.compose.foundation.isSystemInDarkTheme\n", "")
    s = add_import(s, "import com.openminis.app.ui.theme.ChatColors")
    s = s.replace("    val darkTheme = isSystemInDarkTheme()", "    val darkTheme = ChatColors.isDark")
    if rel.endswith("WebPreviewBottomSheet.kt"):
        s = s.replace("            val previous = controller.isAppearanceLightStatusBars\n", "")
        s = s.replace(
            "                controller.isAppearanceLightStatusBars = previous\n",
            "                controller.isAppearanceLightStatusBars = !darkTheme\n",
        )
    else:
        s = s.replace("            val previousLightStatus = controller.isAppearanceLightStatusBars\n", "")
        s = s.replace("            val previousLightNav = controller.isAppearanceLightNavigationBars\n", "")
        s = s.replace(
            "                controller.isAppearanceLightStatusBars = previousLightStatus\n                controller.isAppearanceLightNavigationBars = previousLightNav\n",
            "                controller.isAppearanceLightStatusBars = !darkTheme\n                controller.isAppearanceLightNavigationBars = !darkTheme\n",
        )
    write(rel, s)

# ---------------------------------------------------------------------------
# D3) Model modality badges wrap as whole chips, not characters.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/ui/components/ModelEntryPicker.kt"
s = read(rel)
if "FlowRow(" not in s:
    s = add_import(s, "import androidx.compose.foundation.layout.Arrangement")
    s = add_import(s, "import androidx.compose.foundation.layout.ExperimentalLayoutApi")
    s = add_import(s, "import androidx.compose.foundation.layout.FlowRow")
    s = s.replace(
        "fun LazyListScope.modelEntryPickerItems(",
        "@OptIn(ExperimentalLayoutApi::class)\nfun LazyListScope.modelEntryPickerItems(",
        1,
    )
    old = '''                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        entry.model.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    // [T-android-provider-voice] Modality chips
                                    // (iOS entryRow modalityBadges: img / audio /
                                    // video / pdf and the -out variants).
                                    modalityBadges(entry.model).forEach { badge ->
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    RoundedCornerShape(3.dp),
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
'''
    new = '''                                // [T-android-modality-chip] Whole chips wrap to the
                                // next line; a plain Row squeezes long labels into
                                // character-by-character vertical columns.
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        entry.model.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    modalityBadges(entry.model).forEach { badge ->
                                        ModalityBadge(badge)
                                    }
                                }
'''
    s = replace_once(s, old, new, "ModelEntryPicker modality row")
write(rel, s)

# ---------------------------------------------------------------------------
# D4) Move-to picker: New Chat target.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/ui/chat/MoveToSessionSheet.kt"
s = read(rel)
if "T-android-moveto-new-chat" not in s:
    s = add_import(s, "import androidx.compose.material.icons.outlined.Add")
    anchor = '''            Text(
                stringResource(R.string.move_to_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
'''
    s = replace_once(
        s,
        anchor,
        anchor + '''            // [T-android-moveto-new-chat] A fresh draft is a first-class target.
            NewChatRow(
                onClick = { onSelect("__new__${java.util.UUID.randomUUID()}") },
            )
''',
        "MoveTo new chat row call",
    )
    insert_before = "/**\n * Single row in the Move-to picker."
    idx = s.find(insert_before)
    if idx < 0:
        raise SystemExit("MoveTo: picker row KDoc anchor missing")
    fn = '''/** [T-android-moveto-new-chat] Fresh-draft destination above existing sessions. */
@Composable
private fun NewChatRow(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color = MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = accent.copy(alpha = 0.18f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(R.string.new_chat),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
}

'''
    s = s[:idx] + fn + s[idx:]
write(rel, s)

# ---------------------------------------------------------------------------
# D5) Predictive back opt-in.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/AndroidManifest.xml"
s = read(rel)
if 'android:enableOnBackInvokedCallback="true"' not in s:
    s = replace_once(
        s,
        '        android:supportsRtl="true"\n        android:theme="@style/Theme.Minis">',
        '        android:supportsRtl="true"\n        android:enableOnBackInvokedCallback="true"\n        android:theme="@style/Theme.Minis">',
        "AndroidManifest predictive back",
    )
write(rel, s)

# ---------------------------------------------------------------------------
# D6) Debug clipboard RPC, copied from upstream method/registry block.
# ---------------------------------------------------------------------------
rel = "src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt"
s = read(rel)
u = upstream(rel)
if '"debug.setClipboard"' not in s:
    # Imports used by the upstream method.
    for imp in [
        "import android.content.ClipData",
        "import android.content.ClipboardManager",
    ]:
        s = add_import(s, imp)
    s = replace_once(
        s,
        '            "debug.inputText" -> handleInputText(params)\n',
        '            "debug.inputText" -> handleInputText(params)\n            "debug.setClipboard" -> handleSetClipboard(params)\n',
        "DebugRPC clipboard dispatch",
    )
    start = u.index("    private suspend fun handleSetClipboard(")
    next_start = u.find("\n    private ", start + 10)
    if next_start < 0:
        raise SystemExit("DebugRPC: cannot bound handleSetClipboard")
    fn = u[start:next_start].rstrip() + "\n\n"
    # Place next to inputText implementation so helpers remain discoverable.
    input_start = s.index("    private suspend fun handleInputText(")
    after_input = s.find("\n    private ", input_start + 10)
    if after_input < 0:
        raise SystemExit("DebugRPC: cannot bound handleInputText")
    s = s[:after_input] + "\n" + fn + s[after_input:]
write(rel, s)

rel = "src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt"
s = read(rel)
u = upstream(rel)
if 'name = "debug.setClipboard"' not in s:
    marker = 'name = "debug.setClipboard"'
    at = u.index(marker)
    block_start = u.rfind("        MethodSpec(", 0, at)
    block_end = u.find("        MethodSpec(", at + len(marker))
    if block_start < 0 or block_end < 0:
        raise SystemExit("DebugMethodRegistry: cannot extract clipboard MethodSpec")
    block = u[block_start:block_end]
    target_marker = 'name = "debug.llmRequests"'
    target_at = s.index(target_marker)
    target_start = s.rfind("        MethodSpec(", 0, target_at)
    if target_start < 0:
        raise SystemExit("DebugMethodRegistry: llmRequests block missing")
    s = s[:target_start] + block + s[target_start:]
write(rel, s)

# Sanity checks for all promised deltas.
checks = {
    "compact state": ("src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt", "val compactProgress:"),
    "compact cancel": ("src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt", "fun cancelCompact()"),
    "compact UI": ("src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt", "__compact_progress__"),
    "section inset": ("src/android/app/src/main/java/com/openminis/app/ui/components/SectionTextField.kt", "contentHorizontalPadding"),
    "group inset": ("src/android/app/src/main/java/com/openminis/app/ui/sessions/GroupPickerSheet.kt", "contentHorizontalPadding = 16.dp"),
    "locale case": (helper_rel, "uppercaseForDisplay"),
    "memory row": ("src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt", "T-android-memory-row-date-wrap"),
    "preview theme": ("src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewBottomSheet.kt", "val darkTheme = ChatColors.isDark"),
    "model chips": ("src/android/app/src/main/java/com/openminis/app/ui/components/ModelEntryPicker.kt", "FlowRow("),
    "move new": ("src/android/app/src/main/java/com/openminis/app/ui/chat/MoveToSessionSheet.kt", "T-android-moveto-new-chat"),
    "predictive back": ("src/android/app/src/main/AndroidManifest.xml", "enableOnBackInvokedCallback"),
    "clipboard rpc": ("src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt", '"debug.setClipboard"'),
}
for label, (rel, needle) in checks.items():
    if needle not in read(rel):
        raise SystemExit(f"validation failed: {label}")

print("remaining audited sync applied")
