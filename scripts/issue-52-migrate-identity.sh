#!/usr/bin/env bash
set -euo pipefail

OLD_PACKAGE='com.openminis.app'
NEW_PACKAGE='io.github.slackerllc.minis'
OLD_PATH='com/openminis/app'
NEW_PATH='io/github/slackerllc/minis'
OLD_JNI='Java_com_openminis_app'
NEW_JNI='Java_io_github_slackerllc_minis'
OLD_APP_ID='dev.openminispet.android'
NEW_APP_ID='io.github.slackerllc.minis'

cd "$(git rev-parse --show-toplevel)"

echo 'Issue #52 pre-migration active identity references:'
git grep -n -E 'dev\.openminispet\.android|com\.openminis\.app|com/openminis/app|Java_com_openminis_app' -- \
  src/android src/native scripts .github 2>/dev/null || true

for source_set in main test androidTest; do
  old="src/android/app/src/${source_set}/java/${OLD_PATH}"
  new="src/android/app/src/${source_set}/java/${NEW_PATH}"
  if [[ -d "$old" ]]; then
    if [[ -e "$new" ]]; then
      echo "refusing to merge source roots: $new already exists" >&2
      exit 41
    fi
    mkdir -p "$(dirname "$new")"
    mv "$old" "$new"
  fi
done

python3 - <<'PY'
from pathlib import Path

old_package = 'com.openminis.app'
new_package = 'io.github.slackerllc.minis'
old_path = 'com/openminis/app'
new_path = 'io/github/slackerllc/minis'
old_jni = 'Java_com_openminis_app'
new_jni = 'Java_io_github_slackerllc_minis'
old_app = 'dev.openminispet.android'
new_app = 'io.github.slackerllc.minis'

roots = [Path('src/android'), Path('src/native'), Path('scripts'), Path('.github/workflows')]
skip = {
    Path('scripts/issue-52-migrate-identity.sh'),
    Path('.github/workflows/issue-52-migrate.yml'),
}

for root in roots:
    if not root.exists():
        continue
    files = [root] if root.is_file() else root.rglob('*')
    for path in files:
        if not path.is_file() or path in skip:
            continue
        try:
            raw = path.read_bytes()
            text = raw.decode('utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        updated = text.replace(old_package, new_package)
        updated = updated.replace(old_path, new_path)
        updated = updated.replace(old_jni, new_jni)
        updated = updated.replace(old_app, new_app)
        if updated != text:
            path.write_text(updated, encoding='utf-8')

gradle = Path('src/android/app/build.gradle.kts')
text = gradle.read_text(encoding='utf-8')
needle = '    namespace = "io.github.slackerllc.minis"\n'
if needle not in text:
    raise SystemExit('canonical namespace missing after migration')
if 'testNamespace = ' not in text:
    text = text.replace(needle, needle + '    testNamespace = "io.github.slackerllc.minis.test"\n', 1)
else:
    import re
    text = re.sub(r'testNamespace\s*=\s*"[^"]+"', 'testNamespace = "io.github.slackerllc.minis.test"', text, count=1)
gradle.write_text(text, encoding='utf-8')
PY

# The old package tree must not survive as an active source root.
for source_set in main test androidTest; do
  test ! -e "src/android/app/src/${source_set}/java/${OLD_PATH}" || {
    echo "legacy source root still exists for $source_set" >&2
    exit 42
  }
done

echo 'Issue #52 post-migration active identity references (temporary migration files excluded):'
remaining="$(git grep -n -E 'dev\.openminispet\.android|com\.openminis\.app|com/openminis/app|Java_com_openminis_app' -- \
  src/android src/native scripts .github \
  ':!scripts/issue-52-migrate-identity.sh' \
  ':!.github/workflows/issue-52-migrate.yml' 2>/dev/null || true)"
if [[ -n "$remaining" ]]; then
  printf '%s\n' "$remaining" >&2
  exit 43
fi

git status --short
