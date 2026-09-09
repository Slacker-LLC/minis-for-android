from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
UP = "4ef29002e88db1e20e462ec2ff46916e8a7dcb45"


def p(rel: str) -> Path:
    return ROOT / rel


def read(rel: str) -> str:
    return p(rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    p(rel).write_text(text.rstrip() + "\n", encoding="utf-8")


def add_import(rel: str, import_line: str) -> None:
    s = read(rel)
    if import_line in s:
        return
    imports = list(re.finditer(r"(?m)^import .+$", s))
    if not imports:
        raise SystemExit(f"{rel}: no import block")
    pos = imports[-1].end()
    s = s[:pos] + "\n" + import_line + s[pos:]
    write(rel, s)


# Compile errors surfaced by CI #721.
chat_indicators = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatIndicators.kt"
add_import(chat_indicators, "import androidx.compose.foundation.clickable")
add_import(chat_indicators, "import androidx.compose.runtime.mutableStateOf")

memory_screen = "src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt"
add_import(memory_screen, "import androidx.compose.foundation.layout.width")

# CI #716 showed the four newer-db strings were missing in these locales.
# Copy the locked-upstream strings verbatim rather than translating by hand.
locales = ["de", "ru", "ko", "ja", "fr"]
string_names = ["newer_db_title", "newer_db_body", "newer_db_versions", "newer_db_exit"]

for locale in locales:
    rel = f"src/android/app/src/main/res/values-{locale}/strings.xml"
    target = read(rel)
    if all(f'name="{name}"' in target for name in string_names):
        continue

    upstream = subprocess.check_output(
        ["git", "show", f"{UP}:{rel}"], cwd=ROOT, text=True, encoding="utf-8"
    )
    entries = []
    for name in string_names:
        # Android strings may contain escaped \n sequences but stay on one XML line.
        m = re.search(rf'(?m)^\s*<string name="{name}"[^>]*>.*?</string>\s*$', upstream)
        if not m:
            raise SystemExit(f"{rel}: upstream missing {name}")
        entries.append(m.group(0).strip())

    # Do not duplicate partially present entries.
    for name in string_names:
        target = re.sub(
            rf'(?m)^\s*<string name="{name}"[^>]*>.*?</string>\s*\n?',
            "",
            target,
        )

    block = "    " + "\n    ".join(entries) + "\n"
    if "</resources>" not in target:
        raise SystemExit(f"{rel}: malformed resources")
    target = target.replace("</resources>", block + "</resources>", 1)
    write(rel, target)

# Sanity checks.
assert "import androidx.compose.foundation.clickable" in read(chat_indicators)
assert "import androidx.compose.runtime.mutableStateOf" in read(chat_indicators)
assert "import androidx.compose.foundation.layout.width" in read(memory_screen)
for locale in locales:
    rel = f"src/android/app/src/main/res/values-{locale}/strings.xml"
    s = read(rel)
    for name in string_names:
        if f'name="{name}"' not in s:
            raise SystemExit(f"{rel}: validation missing {name}")

print("audit CI fixes applied")
