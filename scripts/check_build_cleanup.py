#!/usr/bin/env python3
"""Reject obsolete build/runtime paths without banning provenance or migration-only identity."""
from __future__ import annotations

import re
import sys
from pathlib import Path

REQUIRED_PATHS = (
    "src/android/gradlew",
    "src/android/app/build.gradle.kts",
    "src/native/root-network-proxy/Cargo.toml",
    "scripts/build-root-network-proxy-android.sh",
    "scripts/build-android-debug.ps1",
    "scripts/build-ubuntu-rootfs.sh",
    "scripts/verify-android-release.sh",
)
FORBIDDEN_PATHS = (
    "src/native/minisd",
    "scripts/build-minisd-android.sh",
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
PRODUCTION_SOURCE_ROOTS = ("src/android/app/src/main",)
PRODUCTION_TEXT_SUFFIXES = {
    ".aidl",
    ".java",
    ".json",
    ".kt",
    ".kts",
    ".pro",
    ".properties",
    ".txt",
    ".xml",
}
SELF_EXCLUDED_SCRIPTS = {
    "scripts/check_build_cleanup.py",
    "scripts/test_build_cleanup_guard.py",
    "scripts/check-runtime-package-boundary.sh",
    "scripts/verify-runtime-payload.sh",
    "scripts/verify-root-network-proxy.sh",
}
LEGACY_IOS_RE = re.compile(r"(?i)(?:^|[\\/\s\'\"`])src[\\/]ios(?:[\\/\s\'\"`]|$)")
LEGACY_WRAPPER_RE = re.compile(r"(?i)build-pet-apk\.ps1")
ACTIVE_MINISD_RE = re.compile(
    r"(?i)(?:src/native/minisd|build-minisd-android|libminisd\.so|minisd-arm64-v8a|runtime\.minisd|minisd\.sock)"
)
# Match the retired daemon identity without confusing normal Minis-prefixed
# names such as MinisDocumentsProvider or MinisDebug* with `minisd`.
PRODUCTION_MINISD_RE = re.compile(r"(?:\bminisd\b|\bMinisd[A-Za-z0-9_]*\b|\bMINISD\b)")
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
            if (
                path.is_file()
                and path.suffix.lower() in SCRIPT_SUFFIXES
                and path.relative_to(root).as_posix() not in SELF_EXCLUDED_SCRIPTS
            ):
                files.add(path)

    workflows = root / ".github" / "workflows"
    if workflows.is_dir():
        for pattern in ("*.yml", "*.yaml"):
            files.update(path for path in workflows.glob(pattern) if path.is_file())
    return sorted(files)


def _production_source_files(root: Path) -> list[Path]:
    files: set[Path] = set()
    for relative in PRODUCTION_SOURCE_ROOTS:
        source_root = root / relative
        if not source_root.is_dir():
            continue
        for path in source_root.rglob("*"):
            if path.is_file() and path.suffix.lower() in PRODUCTION_TEXT_SUFFIXES:
                files.add(path)
    return sorted(files)


def _read_text(path: Path, relative: str, errors: list[str], kind: str) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        errors.append(f"{kind} is not UTF-8 text: {relative}")
        return None


def _strip_source_comments(text: str) -> str:
    """Remove source comments while preserving strings/identifiers for residue checks."""
    text = re.sub(r"(?s)<!--.*?-->", "", text)
    text = re.sub(r"(?s)/\*.*?\*/", "", text)
    text = re.sub(r"(?m)//[^\n]*$", "", text)
    return text


def _check_production_minisd(relative: str, text: str, errors: list[str]) -> None:
    # Concrete obsolete paths/artifacts are never valid in production source,
    # even in comments: they are common copy/paste vectors for regressions.
    if ACTIVE_MINISD_RE.search(text):
        errors.append(f"obsolete minisd build/runtime path referenced by production source: {relative}")
        return

    # Plain historical prose may remain in comments, but executable source,
    # string literals, resource values and identifiers must not carry the old
    # daemon identity.
    code = _strip_source_comments(text)
    if PRODUCTION_MINISD_RE.search(code):
        errors.append(f"obsolete minisd identity referenced by production source: {relative}")


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
        text = _read_text(path, relative, errors, "active tooling")
        if text is None:
            continue
        if LEGACY_WRAPPER_RE.search(text):
            errors.append(f"legacy build wrapper referenced by active tooling: {relative}")
        if LEGACY_IOS_RE.search(text):
            errors.append(f"removed iOS source path referenced by active tooling: {relative}")
        if ACTIVE_MINISD_RE.search(text):
            errors.append(f"obsolete minisd build/runtime path referenced by active tooling: {relative}")
        if UPSTREAM_CLONE_RE.search(text):
            errors.append(f"obsolete upstream clone pipeline referenced by active tooling: {relative}")
        if UPSTREAM_PATCH_RE.search(text):
            errors.append(f"obsolete upstream patch pipeline referenced by active tooling: {relative}")

    for path in _production_source_files(root):
        relative = path.relative_to(root).as_posix()
        text = _read_text(path, relative, errors, "production source")
        if text is not None:
            _check_production_minisd(relative, text, errors)

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
