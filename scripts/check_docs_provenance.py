#!/usr/bin/env python3
"""Fail if current project documentation regresses into historical runtime framing."""
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
    "docs/BUILD-CLEANUP-AUDIT.md",
    "docs/contracts/06-CURRENT-GAPS.md",
}
ALLOWLIST_PREFIXES = ("docs/archive/", "docs/issue-")
CONTRACT_FILES = (
    "docs/contracts/00-IDENTITY.md",
    "docs/contracts/01-ARCHITECTURE.md",
    "docs/contracts/02-CONSTRAINTS.md",
    "docs/contracts/03-STORAGE-CONTRACT.md",
    "docs/contracts/04-SECURITY-CONTRACT.md",
    "docs/contracts/05-ENGINEERING.md",
    "docs/contracts/06-CURRENT-GAPS.md",
    "docs/contracts/07-OWNERSHIP-MIGRATION.md",
    "docs/contracts/08-BOT-COORDINATION.md",
)
BANNED_PATTERNS = {
    "OpenMinis product framing": re.compile(r"\bOpenMinis(?:Pet)?\b"),
    "PRoot runtime framing": re.compile(r"\bPRoot\b"),
    "Alpine runtime framing": re.compile(r"\bAlpine\b"),
    "obsolete minisd runtime framing": re.compile(r"\bminisd\b", re.IGNORECASE),
    "obsolete Root-owned user-data framing": re.compile(
        r"(?:persistent|canonical|active|真源|持久化|现役)[^\n]{0,160}"
        r"/data/adb/minis/(?:workspace|sessions|memory|skills|shared|home|mcp-servers)",
        re.IGNORECASE,
    ),
    "removed upstream policy document": re.compile(r"UPSTREAM\.md"),
    "removed Web Remote framing": re.compile(r"\bWeb Remote\b"),
    "removed Cloudflare Tunnel framing": re.compile(r"\bCloudflare Tunnel\b"),
    "English-primary documentation policy": re.compile(
        r"English is the primary (?:documentation )?language",
        re.IGNORECASE,
    ),
}
NEGATABLE_LABELS = {
    "PRoot runtime framing",
    "Alpine runtime framing",
    "obsolete minisd runtime framing",
    "obsolete Root-owned user-data framing",
}
REQUIRED_EXECUTION_TERMS = (
    "Ubuntu 24.04",
    "mount namespace",
    "chroot",
    "setpriv",
    "Context.filesDir",
    "/data/adb/minis/rootfs",
    "127.0.0.1:18787",
)
REQUIRED_README_TERMS = (
    "Ubuntu 24.04",
    "Context.filesDir",
    "/data/adb/minis/rootfs",
)
REQUIRED_CONTRIBUTING_TERMS = (
    "direct Ubuntu",
    "App-owned",
    "Root",
)
REQUIRED_SECURITY_TERMS = (
    "DirectRootRunner",
    "App-owned",
    "/data/adb/minis/rootfs",
    "127.0.0.1:18787",
)
REQUIRED_PROVENANCE_TERMS = (
    "OpenMinis/OpenMinis",
    "GPL-3.0",
    "https://github.com/OpenMinis/OpenMinis",
)
REQUIRED_IDENTITY_TERMS = ("llc.slacker.minis", "slacker.llc", "direct Ubuntu")
REQUIRED_STORAGE_CONTRACT_TERMS = (
    "Context.filesDir",
    "/data/adb/minis/rootfs",
    "minis-sessions",
    "minis-global",
    ".root-data-migrated-v1",
)
REQUIRED_AGENTS_TERMS = (
    "llc.slacker.minis",
    "docs/contracts/",
    "Context.filesDir",
    "DirectRootRunner",
)
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
    "旧",
    "历史",
    "迁移源",
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
    "legacy",
    "historical",
    "former",
    "obsolete",
    "migration source",
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
                if not pattern.search(line):
                    continue
                if label in NEGATABLE_LABELS and is_negative_mention(line):
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
