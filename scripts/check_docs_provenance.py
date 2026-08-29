#!/usr/bin/env python3
"""Fail if current project documentation regresses into historical product framing."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOP_LEVEL_ACTIVE = {"README.md", "README.zh-CN.md", "BUILDING.md", "BUILDING.zh-CN.md", "CONTRIBUTING.md", "CONTRIBUTORS.md"}
ALLOWLIST_FILES = {"PROVENANCE.md", "THIRD_PARTY_LICENSES.md", "CHANGELOG.md", "LICENSE"}
ALLOWLIST_PREFIXES = ("docs/archive/",)
BANNED_PATTERNS = {
    "OpenMinis product framing": re.compile(r"\bOpenMinis(?:Pet)?\b"),
    "PRoot runtime framing": re.compile(r"\bPRoot\b"),
    "Alpine runtime framing": re.compile(r"\bAlpine\b"),
    "removed upstream policy document": re.compile(r"UPSTREAM\.md"),
    "removed Web Remote framing": re.compile(r"\bWeb Remote\b"),
    "removed Cloudflare Tunnel framing": re.compile(r"\bCloudflare Tunnel\b"),
}
REQUIRED_EXECUTION_TERMS = ("Ubuntu 24.04", "minisd", "mount namespace", "chroot")
REQUIRED_PROVENANCE_TERMS = ("OpenMinis/OpenMinis", "GPL-3.0", "https://github.com/OpenMinis/OpenMinis")

def is_allowlisted(rel: str) -> bool:
    return rel in ALLOWLIST_FILES or any(rel.startswith(prefix) for prefix in ALLOWLIST_PREFIXES)

def active_markdown_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for name in TOP_LEVEL_ACTIVE:
        path = root / name
        if path.is_file(): files.append(path)
    for base in (root / "docs", root / ".github"):
        if base.exists():
            for path in base.rglob("*.md"):
                rel = path.relative_to(root).as_posix()
                if not is_allowlisted(rel): files.append(path)
    return sorted(set(files))

def check_tree(root: Path) -> list[str]:
    errors: list[str] = []
    if (root / "UPSTREAM.md").exists(): errors.append("UPSTREAM.md must not remain an active synchronization-policy document")
    for path in active_markdown_files(root):
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        for label, pattern in BANNED_PATTERNS.items():
            if pattern.search(text): errors.append(f"{rel}: contains {label}")
    execution = root / "docs/EXECUTION-ENVIRONMENT.md"
    if not execution.is_file(): errors.append("docs/EXECUTION-ENVIRONMENT.md is required")
    else:
        text = execution.read_text(encoding="utf-8")
        for term in REQUIRED_EXECUTION_TERMS:
            if term not in text: errors.append(f"docs/EXECUTION-ENVIRONMENT.md: missing current-runtime term {term!r}")
    provenance = root / "PROVENANCE.md"
    if not provenance.is_file(): errors.append("PROVENANCE.md is required")
    else:
        text = provenance.read_text(encoding="utf-8")
        for term in REQUIRED_PROVENANCE_TERMS:
            if term not in text: errors.append(f"PROVENANCE.md: missing provenance term {term!r}")
    return errors

def main() -> int:
    errors = check_tree(ROOT)
    if errors:
        print("documentation provenance guard failed:", file=sys.stderr)
        for error in errors: print(f"  - {error}", file=sys.stderr)
        return 1
    print("documentation provenance guard passed")
    return 0

if __name__ == "__main__": raise SystemExit(main())
