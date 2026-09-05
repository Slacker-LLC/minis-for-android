#!/usr/bin/env python3
"""Fail if current project documentation regresses into historical or stale runtime framing."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOP_LEVEL_ACTIVE = {
    "AGENTS.md",
    "README.md",
    "README.zh-CN.md",
    "BUILDING.md",
    "BUILDING.zh-CN.md",
    "CONTRIBUTING.md",
    "CONTRIBUTING.zh-CN.md",
    "CONTRIBUTORS.md",
}
ALLOWLIST_FILES = {
    "PROVENANCE.md",
    "THIRD_PARTY_LICENSES.md",
    "CHANGELOG.md",
    "LICENSE",
    "docs/contracts/06-CURRENT-GAPS.md",
}
ALLOWLIST_PREFIXES = ("docs/archive/",)
CONTRACT_FILES = (
    "docs/contracts/00-IDENTITY.md",
    "docs/contracts/01-ARCHITECTURE.md",
    "docs/contracts/02-CONSTRAINTS.md",
    "docs/contracts/03-STORAGE-CONTRACT.md",
    "docs/contracts/04-SECURITY-CONTRACT.md",
    "docs/contracts/05-ENGINEERING.md",
    "docs/contracts/06-CURRENT-GAPS.md",
)
BANNED_PATTERNS = {
    "OpenMinis product framing": re.compile(r"\bOpenMinis(?:Pet)?\b"),
    "PRoot runtime framing": re.compile(r"\bPRoot\b"),
    "Alpine runtime framing": re.compile(r"\bAlpine\b"),
    "removed upstream policy document": re.compile(r"UPSTREAM\.md"),
    "removed Web Remote framing": re.compile(r"\bWeb Remote\b"),
    "removed Cloudflare Tunnel framing": re.compile(r"\bCloudflare Tunnel\b"),
    "obsolete App-filesDir persistent backing": re.compile(
        r"Context\.filesDir|app(?:'s)? private files directory|App 私有 files 目录",
        re.IGNORECASE,
    ),
    "stale app-private workspace framing": re.compile(r"app-private workspace", re.IGNORECASE),
    "stale single-authority persistence framing": re.compile(
        r"single source of truth[^\n]{0,180}persistence",
        re.IGNORECASE,
    ),
    "English-primary documentation policy": re.compile(
        r"English is the primary (?:documentation )?language",
        re.IGNORECASE,
    ),
}
REQUIRED_EXECUTION_TERMS = (
    "Ubuntu 24.04",
    "minisd",
    "mount namespace",
    "chroot",
    "/data/adb/minis/workspace",
    "/data/adb/minis/sessions",
    "/data/adb/minis/memory",
    "/data/adb/minis/skills",
    "/data/adb/minis/shared",
    "/data/adb/minis/home",
)
REQUIRED_README_TERMS = ("Ubuntu 24.04", "minisd", "/data/adb/minis")
REQUIRED_CONTRIBUTING_TERMS = ("minisd", "/data/adb/minis", "mount namespace")
REQUIRED_SECURITY_TERMS = ("minisd", "/data/adb/minis", "tmpfs")
REQUIRED_PROVENANCE_TERMS = (
    "OpenMinis/OpenMinis",
    "GPL-3.0",
    "https://github.com/OpenMinis/OpenMinis",
)
REQUIRED_IDENTITY_TERMS = ("llc.slacker.minis", "slacker.llc")
REQUIRED_STORAGE_CONTRACT_TERMS = (
    "/data/adb/minis/workspace",
    "/data/adb/minis/sessions",
    "/data/adb/minis/memory",
    "/data/adb/minis/skills",
    "/data/adb/minis/shared",
    "/data/adb/minis/home",
)
REQUIRED_AGENTS_TERMS = ("llc.slacker.minis", "docs/contracts/")
REQUIRED_ZH_README_AUTHORITY = ("中文合同定义应保持的行为边界",)


def is_allowlisted(rel: str) -> bool:
    return rel in ALLOWLIST_FILES or any(rel.startswith(prefix) for prefix in ALLOWLIST_PREFIXES)


def active_markdown_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for name in TOP_LEVEL_ACTIVE:
        path = root / name
        if path.is_file():
            files.append(path)
    for base in (root / "docs", root / ".github"):
        if base.exists():
            for path in base.rglob("*.md"):
                rel = path.relative_to(root).as_posix()
                if not is_allowlisted(rel):
                    files.append(path)
    return sorted(set(files))


def require_terms(errors: list[str], path: Path, label: str, terms: tuple[str, ...]) -> None:
    if not path.is_file():
        errors.append(f"{label} is required")
        return
    text = path.read_text(encoding="utf-8")
    for term in terms:
        if term not in text:
            errors.append(f"{label}: missing required current-state term {term!r}")


NEGATIVE_CONTEXT_KEYWORDS = (
    "禁止",
    "不使用",
    "不依赖",
    "不再",
    "非",
    "淘汰",
    "废弃",
    "移除",
    "摒弃",
    "不属于",
    "不要",
    "不引入",
    "不能",
    "不得",
    "不应",
    "不恢复",
    "不支持",
    "不兼容",
    "不保留",
    "不采用",
    "非目标",
    "别",
    "免",
    "无需",
    "not ",
    "no ",
    "without ",
    "prohibit",
    "forbidden",
    "disallow",
    "deprecat",
    "removed",
    "replaces",
    "instead of",
    "replaced",
    "neither",
    "banned",
)


def is_negative_mention(line: str) -> bool:
    line_lower = line.lower()
    return any(keyword.lower() in line_lower for keyword in NEGATIVE_CONTEXT_KEYWORDS)


def check_tree(root: Path) -> list[str]:
    errors: list[str] = []
    if (root / "UPSTREAM.md").exists():
        errors.append("UPSTREAM.md must not remain an active synchronization-policy document")

    for path in active_markdown_files(root):
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        found_labels = set()
        for line in text.splitlines():
            for label, pattern in BANNED_PATTERNS.items():
                if pattern.search(line):
                    if label in ("PRoot runtime framing", "Alpine runtime framing") and is_negative_mention(line):
                        continue
                    if label not in found_labels:
                        found_labels.add(label)
                        errors.append(f"{rel}: contains {label}")

    for rel in CONTRACT_FILES:
        if not (root / rel).is_file():
            errors.append(f"{rel} is required")

    require_terms(
        errors,
        root / "docs/EXECUTION-ENVIRONMENT.md",
        "docs/EXECUTION-ENVIRONMENT.md",
        REQUIRED_EXECUTION_TERMS,
    )
    require_terms(errors, root / "README.md", "README.md", REQUIRED_README_TERMS)
    require_terms(errors, root / "README.zh-CN.md", "README.zh-CN.md", REQUIRED_README_TERMS)
    require_terms(
        errors,
        root / "README.zh-CN.md",
        "README.zh-CN.md",
        REQUIRED_ZH_README_AUTHORITY,
    )
    require_terms(
        errors,
        root / "CONTRIBUTING.md",
        "CONTRIBUTING.md",
        REQUIRED_CONTRIBUTING_TERMS,
    )
    require_terms(
        errors,
        root / "CONTRIBUTING.zh-CN.md",
        "CONTRIBUTING.zh-CN.md",
        REQUIRED_CONTRIBUTING_TERMS,
    )
    require_terms(
        errors,
        root / "docs/SECURITY.md",
        "docs/SECURITY.md",
        REQUIRED_SECURITY_TERMS,
    )
    require_terms(
        errors,
        root / "docs/contracts/00-IDENTITY.md",
        "docs/contracts/00-IDENTITY.md",
        REQUIRED_IDENTITY_TERMS,
    )
    require_terms(
        errors,
        root / "docs/contracts/03-STORAGE-CONTRACT.md",
        "docs/contracts/03-STORAGE-CONTRACT.md",
        REQUIRED_STORAGE_CONTRACT_TERMS,
    )
    require_terms(errors, root / "AGENTS.md", "AGENTS.md", REQUIRED_AGENTS_TERMS)

    provenance = root / "PROVENANCE.md"
    if not provenance.is_file():
        errors.append("PROVENANCE.md is required")
    else:
        text = provenance.read_text(encoding="utf-8")
        for term in REQUIRED_PROVENANCE_TERMS:
            if term not in text:
                errors.append(f"PROVENANCE.md: missing provenance term {term!r}")
    return errors


def main() -> int:
    errors = check_tree(ROOT)
    if errors:
        print("documentation provenance guard failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("documentation provenance guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
