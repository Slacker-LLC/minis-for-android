#!/usr/bin/env python3
"""Reject obsolete build paths without banning provenance or migration-only identity."""

from __future__ import annotations

import re
import sys
from pathlib import Path

REQUIRED_PATHS = (
    "src/android/gradlew",
    "src/android/app/build.gradle.kts",
    "src/native/minisd/Cargo.toml",
    "scripts/build-android-debug.ps1",
    "scripts/build-ubuntu-rootfs.sh",
    "scripts/verify-android-release.sh",
)

FORBIDDEN_PATHS = (
    "scripts/build-pet-apk.ps1",
    "scripts/verify_models_dev_resolution.py",
)

ROOT_ACTIVE_DOCS = (
    "README.md",
    "README.zh-CN.md",
    "BUILDING.md",
    "BUILDING.zh-CN.md",
    "CONTRIBUTING.md",
)

SCRIPT_SUFFIXES = {".sh", ".ps1", ".py"}
SELF_EXCLUDED_SCRIPTS = {
    "scripts/check_build_cleanup.py",
    "scripts/test_build_cleanup_guard.py",
}

LEGACY_IOS_RE = re.compile(r'''(?i)(?:^|[\\/\s'"`])src[\\/]ios(?:[\\/\s'"`]|$)''')
LEGACY_WRAPPER_RE = re.compile(r"(?i)build-pet-apk\.ps1")
UPSTREAM_CLONE_RE = re.compile(
    r"(?is)\bgit\s+clone\b[^\n]*(?:github\.com[/:]OpenMinis/OpenMinis(?:\.git)?|\bOpenMinisPet\b)"
)
UPSTREAM_PATCH_RE = re.compile(
    r"(?is)(?:\bgit\s+apply\b|\bpatch\b)[^\n]*(?:OpenMinis|OpenMinisPet)"
)


def _active_files(root: Path) -> list[Path]:
    files: set[Path] = set()

    for relative in ROOT_ACTIVE_DOCS:
        path = root / relative
        if path.is_file():
            files.add(path)

    scripts = root / "scripts"
    if scripts.is_dir():
        for path in scripts.iterdir():
            if not path.is_file() or path.suffix.lower() not in SCRIPT_SUFFIXES:
                continue
            relative = path.relative_to(root).as_posix()
            if relative not in SELF_EXCLUDED_SCRIPTS:
                files.add(path)

    workflows = root / ".github" / "workflows"
    if workflows.is_dir():
        for pattern in ("*.yml", "*.yaml"):
            files.update(path for path in workflows.glob(pattern) if path.is_file())

    return sorted(files)


def check_repository(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []

    for relative in REQUIRED_PATHS:
        if not (root / relative).is_file():
            errors.append(f"missing canonical build path: {relative}")

    for relative in FORBIDDEN_PATHS:
        if (root / relative).exists():
            errors.append(f"obsolete build path exists: {relative}")

    for path in _active_files(root):
        relative = path.relative_to(root).as_posix()
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            errors.append(f"active tooling is not UTF-8 text: {relative}")
            continue

        if LEGACY_WRAPPER_RE.search(text):
            errors.append(f"legacy build wrapper referenced by active tooling: {relative}")
        if LEGACY_IOS_RE.search(text):
            errors.append(f"removed iOS source path referenced by active tooling: {relative}")
        if UPSTREAM_CLONE_RE.search(text):
            errors.append(f"obsolete upstream clone pipeline referenced by active tooling: {relative}")
        if UPSTREAM_PATCH_RE.search(text):
            errors.append(f"obsolete upstream patch pipeline referenced by active tooling: {relative}")

    return errors


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[1]
    errors = check_repository(root)
    if errors:
        for error in errors:
            print(f"build-cleanup guard: {error}", file=sys.stderr)
        return 1
    print("build-cleanup guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
