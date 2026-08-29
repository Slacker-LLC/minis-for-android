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

# Main source: split by current responsibility, not by a mechanical sandbox->runtime rename.
move_dir "$BASE/main/java/$OLD/minisd" "$BASE/main/java/$NEW/minisd"
move_dir "$BASE/main/java/$OLD/ubuntu" "$BASE/main/java/$NEW/ubuntu"
move_dir "$BASE/main/java/$OLD/offload" "$BASE/main/java/$NEW/guest"
move_file "$BASE/main/java/$OLD/NativeOffload.kt" "$BASE/main/java/$NEW/guest/NativeOffload.kt"
for f in ShellTimeoutPolicy.kt TerminalSanitizer.kt TerminalSession.kt; do
  move_file "$BASE/main/java/$OLD/$f" "$BASE/main/java/$NEW/terminal/$f"
done
for f in ExecutionCoordinator.kt MinisKernel.kt MountedFolderCoordinator.kt RootfsManager.kt; do
  move_file "$BASE/main/java/$OLD/$f" "$BASE/main/java/$NEW/$f"
done

# Unit tests follow the production responsibility they exercise.
move_dir "$BASE/test/java/$OLD/minisd" "$BASE/test/java/$NEW/minisd"
move_dir "$BASE/test/java/$OLD/ubuntu" "$BASE/test/java/$NEW/ubuntu"
move_dir "$BASE/test/java/$OLD/offload" "$BASE/test/java/$NEW/guest"
move_file "$BASE/test/java/$OLD/OffloadReplySweepTest.kt" "$BASE/test/java/$NEW/guest/OffloadReplySweepTest.kt"
move_file "$BASE/test/java/$OLD/TerminalSanitizerTest.kt" "$BASE/test/java/$NEW/terminal/TerminalSanitizerTest.kt"
move_file "$BASE/test/java/$OLD/TarExtractionTest.kt" "$BASE/test/java/$NEW/TarExtractionTest.kt"

# Instrumentation tests under the legacy boundary follow the same responsibility split.
move_dir "$BASE/androidTest/java/$OLD/minisd" "$BASE/androidTest/java/$NEW/minisd"
move_dir "$BASE/androidTest/java/$OLD/ubuntu" "$BASE/androidTest/java/$NEW/ubuntu"
move_dir "$BASE/androidTest/java/$OLD/offload" "$BASE/androidTest/java/$NEW/guest"
move_file "$BASE/androidTest/java/$OLD/TerminalSanitizerInstrumentedTest.kt" "$BASE/androidTest/java/$NEW/terminal/TerminalSanitizerInstrumentedTest.kt"

python3 - <<'PY'
from pathlib import Path

root = Path('src/android')
text_suffixes = {'.kt', '.kts', '.java', '.xml', '.c', '.cc', '.cpp', '.h', '.hpp', '.md', '.txt'}

# Ordered exact namespace substitutions. Specific responsibility classes run before
# the core fallback so their imports cannot accidentally land in runtime.*.
subs = [
    ('com.openminis.app.sandbox.minisd', 'com.openminis.app.runtime.minisd'),
    ('com.openminis.app.sandbox.ubuntu', 'com.openminis.app.runtime.ubuntu'),
    ('com.openminis.app.sandbox.offload', 'com.openminis.app.runtime.guest'),
    ('com.openminis.app.sandbox.NativeOffload', 'com.openminis.app.runtime.guest.NativeOffload'),
    ('com.openminis.app.sandbox.ShellTimeoutPolicy', 'com.openminis.app.runtime.terminal.ShellTimeoutPolicy'),
    ('com.openminis.app.sandbox.TerminalSanitizer', 'com.openminis.app.runtime.terminal.TerminalSanitizer'),
    ('com.openminis.app.sandbox.TerminalSession', 'com.openminis.app.runtime.terminal.TerminalSession'),
    ('com.openminis.app.sandbox.ExecutionCoordinator', 'com.openminis.app.runtime.ExecutionCoordinator'),
    ('com.openminis.app.sandbox.MinisKernel', 'com.openminis.app.runtime.MinisKernel'),
    ('com.openminis.app.sandbox.MountedFolderCoordinator', 'com.openminis.app.runtime.MountedFolderCoordinator'),
    ('com.openminis.app.sandbox.ReadOnlyMountException', 'com.openminis.app.runtime.ReadOnlyMountException'),
    ('com.openminis.app.sandbox.RootfsManager', 'com.openminis.app.runtime.RootfsManager'),
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
    if updated != text:
        p.write_text(updated, encoding='utf-8')

# Package declarations for moved files are determined by their destination.
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
        lines = text.splitlines(keepends=True)
        for i, line in enumerate(lines):
            if line.startswith('package '):
                newline = '\n' if line.endswith('\n') else ''
                lines[i] = f'package {expected}{newline}'
                break
        p.write_text(''.join(lines), encoding='utf-8')

# Lint baseline paths are path identity, not runtime behavior.
baseline = Path('src/android/app/lint-baseline.xml')
if baseline.exists():
    text = baseline.read_text(encoding='utf-8')
    replacements = [
        ('com/openminis/app/sandbox/minisd', 'com/openminis/app/runtime/minisd'),
        ('com/openminis/app/sandbox/ubuntu', 'com/openminis/app/runtime/ubuntu'),
        ('com/openminis/app/sandbox/offload', 'com/openminis/app/runtime/guest'),
        ('com/openminis/app/sandbox/NativeOffload.kt', 'com/openminis/app/runtime/guest/NativeOffload.kt'),
        ('com/openminis/app/sandbox/ShellTimeoutPolicy.kt', 'com/openminis/app/runtime/terminal/ShellTimeoutPolicy.kt'),
        ('com/openminis/app/sandbox/TerminalSanitizer.kt', 'com/openminis/app/runtime/terminal/TerminalSanitizer.kt'),
        ('com/openminis/app/sandbox/TerminalSession.kt', 'com/openminis/app/runtime/terminal/TerminalSession.kt'),
        ('com/openminis/app/sandbox/', 'com/openminis/app/runtime/'),
    ]
    for old, new in replacements:
        text = text.replace(old, new)
    baseline.write_text(text, encoding='utf-8')
PY

# Fail closed if an unexpected active class/test was left behind.
for source_set in main test androidTest; do
  old="$BASE/$source_set/java/$OLD"
  if [[ -d "$old" ]] && find "$old" -type f -print -quit | grep -q .; then
    echo "Unexpected active file remains under deprecated boundary: $old" >&2
    find "$old" -type f -print >&2
    exit 54
  fi
done

# Exact old FQNs are forbidden in active Android source/build inputs after migration.
remaining="$(git grep -n -E 'com\.openminis\.app\.sandbox([.;]|$)' -- src/android 2>/dev/null || true)"
if [[ -n "$remaining" ]]; then
  printf '%s\n' "$remaining" >&2
  exit 55
fi

git status --short
