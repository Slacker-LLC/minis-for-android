#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
BASE='src/android/app/src'
OLD='com/openminis/app/sandbox'
NEW='com/openminis/app/runtime'

move_dir() {
  local source="$1" target="$2"
  if [[ -d "$source" ]]; then
    mkdir -p "$(dirname "$target")"
    git mv "$source" "$target"
  fi
}
move_file() {
  local source="$1" target="$2"
  if [[ -f "$source" ]]; then
    mkdir -p "$(dirname "$target")"
    git mv "$source" "$target"
  fi
}

# Active runtime responsibilities.
move_dir "$BASE/main/java/$OLD/minisd" "$BASE/main/java/$NEW/minisd"
move_dir "$BASE/main/java/$OLD/ubuntu" "$BASE/main/java/$NEW/ubuntu"
move_dir "$BASE/main/java/$OLD/offload" "$BASE/main/java/$NEW/guest"
move_file "$BASE/main/java/$OLD/NativeOffload.kt" "$BASE/main/java/$NEW/guest/NativeOffload.kt"
move_file "$BASE/main/java/$OLD/ShellTimeoutPolicy.kt" "$BASE/main/java/$NEW/terminal/ShellTimeoutPolicy.kt"
move_file "$BASE/main/java/$OLD/TerminalSanitizer.kt" "$BASE/main/java/$NEW/terminal/TerminalSanitizer.kt"
move_file "$BASE/main/java/$OLD/ExecutionCoordinator.kt" "$BASE/main/java/$NEW/ExecutionCoordinator.kt"
move_file "$BASE/main/java/$OLD/MountedFolderCoordinator.kt" "$BASE/main/java/$NEW/ExternalMountCoordinator.kt"
move_file "$BASE/main/java/$OLD/MinisKernel.kt" "$BASE/main/java/$NEW/RuntimePathRegistry.kt"

# Explicit legacy allowlist. These compatibility shells are intentionally NOT
# presented as active Ubuntu/minisd runtime components. Their deletion/replacement
# belongs to the issues that own that behavior.
move_file "$BASE/main/java/$NEW/RootfsManager.kt" "$BASE/main/java/$OLD/RootfsManager.kt"
move_file "$BASE/main/java/$NEW/terminal/TerminalSession.kt" "$BASE/main/java/$OLD/TerminalSession.kt"

# Tests follow active responsibilities; compatibility-parser coverage stays legacy.
move_dir "$BASE/test/java/$OLD/minisd" "$BASE/test/java/$NEW/minisd"
move_dir "$BASE/test/java/$OLD/ubuntu" "$BASE/test/java/$NEW/ubuntu"
move_dir "$BASE/test/java/$OLD/offload" "$BASE/test/java/$NEW/guest"
move_file "$BASE/test/java/$OLD/OffloadReplySweepTest.kt" "$BASE/test/java/$NEW/guest/OffloadReplySweepTest.kt"
move_file "$BASE/test/java/$OLD/TerminalSanitizerTest.kt" "$BASE/test/java/$NEW/terminal/TerminalSanitizerTest.kt"
move_file "$BASE/test/java/$NEW/TarExtractionTest.kt" "$BASE/test/java/$OLD/TarExtractionTest.kt"

move_dir "$BASE/androidTest/java/$OLD/minisd" "$BASE/androidTest/java/$NEW/minisd"
move_dir "$BASE/androidTest/java/$OLD/ubuntu" "$BASE/androidTest/java/$NEW/ubuntu"
move_dir "$BASE/androidTest/java/$OLD/offload" "$BASE/androidTest/java/$NEW/guest"
move_file "$BASE/androidTest/java/$OLD/TerminalSanitizerInstrumentedTest.kt" "$BASE/androidTest/java/$NEW/terminal/TerminalSanitizerInstrumentedTest.kt"

# Finalize semantic renames from the intermediate migration commit if present.
move_file "$BASE/main/java/$NEW/MinisKernel.kt" "$BASE/main/java/$NEW/RuntimePathRegistry.kt"
move_file "$BASE/main/java/$NEW/MountedFolderCoordinator.kt" "$BASE/main/java/$NEW/ExternalMountCoordinator.kt"

python3 - <<'PY'
from pathlib import Path
import re

root = Path('src/android')
text_suffixes = {'.kt', '.kts', '.java', '.xml', '.c', '.cc', '.cpp', '.h', '.hpp', '.md', '.txt'}

subs = [
    ('com.openminis.app.sandbox.minisd', 'com.openminis.app.runtime.minisd'),
    ('com.openminis.app.sandbox.ubuntu', 'com.openminis.app.runtime.ubuntu'),
    ('com.openminis.app.sandbox.offload', 'com.openminis.app.runtime.guest'),
    ('com.openminis.app.sandbox.NativeOffload', 'com.openminis.app.runtime.guest.NativeOffload'),
    ('com.openminis.app.sandbox.ShellTimeoutPolicy', 'com.openminis.app.runtime.terminal.ShellTimeoutPolicy'),
    ('com.openminis.app.sandbox.TerminalSanitizer', 'com.openminis.app.runtime.terminal.TerminalSanitizer'),
    ('com.openminis.app.runtime.RootfsManager', 'com.openminis.app.sandbox.RootfsManager'),
    ('com.openminis.app.runtime.RootfsInstallState', 'com.openminis.app.sandbox.RootfsInstallState'),
    ('com.openminis.app.runtime.terminal.TerminalSession', 'com.openminis.app.sandbox.TerminalSession'),
    ('com.openminis.app.sandbox.ExecutionCoordinator', 'com.openminis.app.runtime.ExecutionCoordinator'),
    ('com.openminis.app.sandbox.MinisKernel', 'com.openminis.app.runtime.RuntimePathRegistry'),
    ('com.openminis.app.runtime.MinisKernel', 'com.openminis.app.runtime.RuntimePathRegistry'),
    ('com.openminis.app.sandbox.MountedFolderCoordinator', 'com.openminis.app.runtime.ExternalMountCoordinator'),
    ('com.openminis.app.runtime.MountedFolderCoordinator', 'com.openminis.app.runtime.ExternalMountCoordinator'),
    ('com.openminis.app.sandbox.ReadOnlyMountException', 'com.openminis.app.runtime.ReadOnlyMountException'),
]

for p in root.rglob('*'):
    if not p.is_file() or p.suffix not in text_suffixes:
        continue
    try:
        text = p.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    updated = text
    for old, new in subs:
        updated = updated.replace(old, new)
    updated = re.sub(r'\bMinisKernel\b', 'RuntimePathRegistry', updated)
    updated = re.sub(r'\bMountedFolderCoordinator\b', 'ExternalMountCoordinator', updated)
    updated = updated.replace('RuntimePathRegistry.boot(', 'RuntimePathRegistry.initialize(')
    updated = re.sub(r'\bisBooted\b', 'isInitialized', updated)
    updated = re.sub(r'\bmarkBooted\b', 'markInitialized', updated)
    if updated != text:
        p.write_text(updated, encoding='utf-8')

# Package declarations are controlled by destination.
package_roots = {
    Path('src/android/app/src/main/java/com/openminis/app/runtime'): 'com.openminis.app.runtime',
    Path('src/android/app/src/test/java/com/openminis/app/runtime'): 'com.openminis.app.runtime',
    Path('src/android/app/src/androidTest/java/com/openminis/app/runtime'): 'com.openminis.app.runtime',
}
for base, pkg in package_roots.items():
    if not base.exists():
        continue
    for p in base.rglob('*.kt'):
        rel = p.parent.relative_to(base)
        suffix = '.'.join(rel.parts)
        expected = pkg + ('.' + suffix if suffix else '')
        text = p.read_text(encoding='utf-8')
        text = re.sub(r'^package\s+[^\n]+', f'package {expected}', text, count=1, flags=re.M)
        p.write_text(text, encoding='utf-8')

for p, expected in [
    (Path('src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt'), 'com.openminis.app.sandbox'),
    (Path('src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt'), 'com.openminis.app.sandbox'),
    (Path('src/android/app/src/test/java/com/openminis/app/sandbox/TarExtractionTest.kt'), 'com.openminis.app.sandbox'),
]:
    if p.exists():
        text = p.read_text(encoding='utf-8')
        text = re.sub(r'^package\s+[^\n]+', f'package {expected}', text, count=1, flags=re.M)
        p.write_text(text, encoding='utf-8')

# RuntimePathRegistry: path/mount ownership, not a generic sandbox/kernel lifecycle.
p = Path('src/android/app/src/main/java/com/openminis/app/runtime/RuntimePathRegistry.kt')
if p.exists():
    text = p.read_text(encoding='utf-8')
    text = text.replace('object MinisKernel', 'object RuntimePathRegistry')
    text = text.replace('private const val TAG = "MinisKernel"', 'private const val TAG = "RuntimePathRegistry"')
    text = text.replace('fun boot(context: Context)', 'fun initialize(context: Context)')
    text = text.replace('private set\n\n    internal fun markInitialized()', 'private set\n\n    internal fun markInitialized()')
    old_doc = '''/**\n * P2: PRoot removed. This object is the Minis sandbox path/mount registry:\n * guest `/workspace` ↔ App filesDir workspaces, SAF external mounts, and the\n * POSIX TZ helper. No proot process management; execution goes through\n * [com.openminis.app.runtime.ubuntu.UbuntuRuntime] / minisd.\n */'''
    new_doc = '''/**\n * Android-side registry for host/guest path resolution and bind-mount inputs.\n * Ubuntu process and mount-namespace lifecycle is owned by minisd; this object\n * only maintains the app-visible path registry, SAF mount snapshots, and host\n * environment helpers consumed while constructing runtime requests.\n */'''
    text = text.replace(old_doc, new_doc)
    text = text.replace('/** True once the sandbox (Ubuntu) has been initialized by the App. */', '/** True once the Android-side path registry has been initialized. */')
    text = text.replace('"sandbox path registry seeded bindMounts=', '"runtime path registry seeded bindMounts=')
    p.write_text(text, encoding='utf-8')

# ExternalMountCoordinator: describe SAF -> host -> minisd bind semantics, never proot -b.
p = Path('src/android/app/src/main/java/com/openminis/app/runtime/ExternalMountCoordinator.kt')
if p.exists():
    text = p.read_text(encoding='utf-8')
    text = text.replace('object MountedFolderCoordinator', 'object ExternalMountCoordinator')
    start = text.index('/**')
    end = text.index(' */', start) + 3
    text = text[:start] + '''/**\n * Maps user-selected SAF folders into the current Ubuntu runtime contract.\n * Resolved host paths become bind-mount inputs for the minisd-owned mount\n * namespace; guest paths live under `/var/minis/mounts/<name>`. This layer\n * also enforces the user-visible read-only policy before file tools write.\n */''' + text[end:]
    text = text.replace('Snapshot of bind-mount specs PRoot should inject.', 'Snapshot of bind-mount specs supplied to the Ubuntu/minisd runtime.')
    p.write_text(text, encoding='utf-8')

# Execution coordinator owns Android orchestration; minisd owns infrastructure.
p = Path('src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt')
if p.exists():
    text = p.read_text(encoding='utf-8')
    if 'import com.openminis.app.runtime.terminal.TerminalSanitizer' not in text:
        text = text.replace('import com.openminis.app.runtime.ubuntu.UbuntuRuntime\n', 'import com.openminis.app.runtime.ubuntu.UbuntuRuntime\nimport com.openminis.app.runtime.terminal.TerminalSanitizer\n')
    text = text.replace('''/**\n * Executes `shell_execute` in the on-device Ubuntu runtime (minisd chroot).\n * PRoot/PersistentShell paths removed at P2. Serialization per session is\n * kept via a simple mutex so same-session commands don't interleave.\n */''', '''/**\n * Android orchestration boundary for guest command execution. It serializes\n * commands per chat session and delegates runtime readiness plus execution to\n * [UbuntuRuntime]; minisd owns privileged broker, mount namespace and chroot\n * infrastructure.\n */''')
    p.write_text(text, encoding='utf-8')

# Lint baseline follows file moves only.
baseline = Path('src/android/app/lint-baseline.xml')
if baseline.exists():
    text = baseline.read_text(encoding='utf-8')
    replacements = [
        ('com/openminis/app/runtime/MinisKernel.kt', 'com/openminis/app/runtime/RuntimePathRegistry.kt'),
        ('com/openminis/app/runtime/MountedFolderCoordinator.kt', 'com/openminis/app/runtime/ExternalMountCoordinator.kt'),
        ('com/openminis/app/runtime/RootfsManager.kt', 'com/openminis/app/sandbox/RootfsManager.kt'),
        ('com/openminis/app/runtime/terminal/TerminalSession.kt', 'com/openminis/app/sandbox/TerminalSession.kt'),
        ('com/openminis/app/runtime/TarExtractionTest.kt', 'com/openminis/app/sandbox/TarExtractionTest.kt'),
    ]
    for old, new in replacements:
        text = text.replace(old, new)
    baseline.write_text(text, encoding='utf-8')
PY

# Final migration assertions. Only the two explicitly-owned compatibility shells
# (plus the RootfsManager parser test) may remain under the deprecated boundary.
allowed_main=(
  "$BASE/main/java/$OLD/RootfsManager.kt"
  "$BASE/main/java/$OLD/TerminalSession.kt"
)
if [[ -d "$BASE/main/java/$OLD" ]]; then
  while IFS= read -r f; do
    ok=false
    for allowed in "${allowed_main[@]}"; do [[ "$f" == "$allowed" ]] && ok=true; done
    if [[ "$ok" != true ]]; then
      echo "Unexpected active file remains under deprecated boundary: $f" >&2
      exit 54
    fi
  done < <(find "$BASE/main/java/$OLD" -type f -print | sort)
fi

[[ ! -e "$BASE/main/java/$NEW/RootfsManager.kt" ]]
[[ ! -e "$BASE/main/java/$NEW/terminal/TerminalSession.kt" ]]
[[ ! -e "$BASE/main/java/$NEW/MinisKernel.kt" ]]
[[ ! -e "$BASE/main/java/$NEW/MountedFolderCoordinator.kt" ]]
[[ -e "$BASE/main/java/$NEW/RuntimePathRegistry.kt" ]]
[[ -e "$BASE/main/java/$NEW/ExternalMountCoordinator.kt" ]]

# No old active runtime FQNs may survive outside the explicit compatibility imports.
remaining="$(git grep -n -E 'com\.openminis\.app\.sandbox\.(minisd|ubuntu|offload|NativeOffload|ShellTimeoutPolicy|TerminalSanitizer|ExecutionCoordinator|MinisKernel|MountedFolderCoordinator)' -- src/android 2>/dev/null || true)"
if [[ -n "$remaining" ]]; then
  printf '%s\n' "$remaining" >&2
  exit 55
fi

git status --short
