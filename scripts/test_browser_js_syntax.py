#!/usr/bin/env python3
"""Guard: the JavaScript we hand the WebView is still parseable.

The agent browser builds its injected scripts as Kotlin raw strings, so the
Kotlin compiler never looks inside them: a missing brace or a stray quote ships
as a silently failing action and only shows up on a device. This extracts every
script from the browser sources, resolves the Kotlin templates the way the
compiler would, and asks node to parse the result.

Skips with a notice when node is not installed.
"""

from __future__ import annotations

import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]

TARGETS = (
    "src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt",
    "src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt",
)

# Restored only after the generic passes: the '$' this template yields can sit in
# front of a real JavaScript identifier ('${'$'}forceUpdate' in the Vue shim),
# which would otherwise look like an unresolved Kotlin template and be eaten.
DOLLAR_MARKER = "\x00DOLLAR\x00"

# Kotlin templates whose value the guard knows; anything else is replaced by a
# valid JavaScript token so the parse can proceed (this check is about syntax,
# not about the values).
KNOWN_TEMPLATES = {
    "${'$'}": DOLLAR_MARKER,
    "${BrowserTextWindowPolicy.MAX_DOCUMENT_CHARS}": "200000",
}

BRACED_TEMPLATE = re.compile(r"\$\{[^}]*\}")
NAMED_TEMPLATE = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*")


def raw_strings(source: str) -> list[str]:
    """Every Kotlin raw string in the file (the scripts and their bodies)."""
    return source.split('"""')[1::2]


def resolve_templates(script: str) -> str:
    for template, value in KNOWN_TEMPLATES.items():
        script = script.replace(template, value)
    script = BRACED_TEMPLATE.sub("0", script)
    script = NAMED_TEMPLATE.sub("0", script)
    return script.replace(DOLLAR_MARKER, "$")


def standalone(script: str) -> str:
    """Wrap a bare statement body so it parses on its own."""
    stripped = script.lstrip()
    if stripped.startswith("(function") or stripped.startswith("(async function"):
        return script
    return "(function() {\n" + script + "\n})();\n"


def main() -> int:
    node = shutil.which("node") or shutil.which("nodejs")
    if node is None:
        print("node not installed - skipping the browser script syntax guard")
        return 0

    failures: list[str] = []
    checked = 0
    with tempfile.TemporaryDirectory() as tmp:
        for relative in TARGETS:
            path = REPO_ROOT / relative
            if not path.exists():
                failures.append(f"{relative}: missing")
                continue
            for index, block in enumerate(raw_strings(path.read_text(encoding="utf-8"))):
                if not block.strip():
                    continue
                target = pathlib.Path(tmp) / f"{path.stem}-{index}.js"
                target.write_text(standalone(resolve_templates(block)), encoding="utf-8")
                result = subprocess.run(
                    [node, "--check", str(target)],
                    capture_output=True,
                    text=True,
                )
                checked += 1
                if result.returncode != 0:
                    detail = (result.stderr or result.stdout).strip().splitlines()
                    failures.append(f"{relative} block {index}: " + " | ".join(detail[:3]))

    if failures:
        print("browser JavaScript failed to parse:")
        for failure in failures:
            print("  " + failure)
        return 1

    print(f"browser JavaScript parsed: {checked} script(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
