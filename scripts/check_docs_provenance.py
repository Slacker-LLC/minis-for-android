#!/usr/bin/env python3
"""Fail if current docs or provenance records regress into stale or unverifiable claims."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOP_LEVEL_ACTIVE = {
    "README.md",
    "README.zh-CN.md",
    "BUILDING.md",
    "BUILDING.zh-CN.md",
    "CONTRIBUTING.md",
    "CONTRIBUTORS.md",
}
ALLOWLIST_FILES = {"PROVENANCE.md", "THIRD_PARTY_LICENSES.md", "CHANGELOG.md", "LICENSE"}
ALLOWLIST_PREFIXES = ("docs/archive/",)
ASSET_MANIFEST = "provenance/runtime-assets.json"
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
    ASSET_MANIFEST,
    "Root/KernelSU device E2E",
    "root_kernelsu_device_e2e: not-claimed",
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REL_RE = re.compile(r'^REL="\$\{REL:-([^}]+)\}"$', re.MULTILINE)
PIN_RE = re.compile(r'^\s*([0-9]+(?:\.[0-9]+)*)\)\s+PINNED_BASE_SHA256="([0-9a-fA-F]{64})"', re.MULTILINE)


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


def rootfs_pin(errors: list[str], root: Path) -> dict[str, str] | None:
    path = root / "scripts/build-ubuntu-rootfs.sh"
    if not path.is_file():
        errors.append("scripts/build-ubuntu-rootfs.sh is required for runtime-asset provenance")
        return None
    text = path.read_text(encoding="utf-8")
    rel_match = REL_RE.search(text)
    if rel_match is None:
        errors.append("scripts/build-ubuntu-rootfs.sh: default REL pin is not parseable")
        return None
    release = rel_match.group(1)
    pins = {version: digest.lower() for version, digest in PIN_RE.findall(text)}
    digest = pins.get(release)
    if digest is None:
        errors.append(f"scripts/build-ubuntu-rootfs.sh: no SHA-256 pin found for default release {release}")
        return None
    base_name = f"ubuntu-base-{release}-base-arm64.tar.gz"
    return {
        "release": release,
        "sha256": digest,
        "source_url": f"https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/{base_name}",
        "checksums_url": "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
    }


def load_json(errors: list[str], path: Path, label: str) -> object | None:
    if not path.is_file():
        errors.append(f"{label} is required")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        errors.append(f"{label}: invalid JSON: {exc}")
        return None


def check_asset_manifest(errors: list[str], root: Path) -> None:
    raw = load_json(errors, root / ASSET_MANIFEST, ASSET_MANIFEST)
    if not isinstance(raw, dict):
        if raw is not None:
            errors.append(f"{ASSET_MANIFEST}: top level must be an object")
        return
    if raw.get("schema_version") != 1:
        errors.append(f"{ASSET_MANIFEST}: schema_version must be 1")

    lineage = raw.get("source_lineage")
    if not isinstance(lineage, dict):
        errors.append(f"{ASSET_MANIFEST}: source_lineage must be an object")
    else:
        if lineage.get("derived_from_repository") != "https://github.com/OpenMinis/OpenMinis":
            errors.append(f"{ASSET_MANIFEST}: OpenMinis source repository must remain explicit")
        if lineage.get("repository_id") != "OpenMinis/OpenMinis":
            errors.append(f"{ASSET_MANIFEST}: OpenMinis repository id must remain explicit")
        if lineage.get("license") != "GPL-3.0":
            errors.append(f"{ASSET_MANIFEST}: source lineage license must remain GPL-3.0")
        if lineage.get("derivation_revision") is not None:
            revision = lineage.get("derivation_revision")
            if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
                errors.append(f"{ASSET_MANIFEST}: derivation_revision must be null or a full 40-hex commit")
        if lineage.get("derivation_revision") is None and lineage.get("derivation_revision_status") != "not-recorded":
            errors.append(f"{ASSET_MANIFEST}: a missing derivation revision must be marked not-recorded")

    assets = raw.get("assets")
    if not isinstance(assets, list):
        errors.append(f"{ASSET_MANIFEST}: assets must be an array")
        return
    by_id: dict[str, dict[str, object]] = {}
    for asset in assets:
        if not isinstance(asset, dict):
            errors.append(f"{ASSET_MANIFEST}: every asset must be an object")
            continue
        asset_id = asset.get("id")
        if not isinstance(asset_id, str) or not asset_id:
            errors.append(f"{ASSET_MANIFEST}: every asset needs a non-empty id")
            continue
        if asset_id in by_id:
            errors.append(f"{ASSET_MANIFEST}: duplicate asset id {asset_id!r}")
            continue
        by_id[asset_id] = asset
        digest = asset.get("sha256")
        status = asset.get("integrity_status")
        if digest is not None and (not isinstance(digest, str) or not SHA256_RE.fullmatch(digest)):
            errors.append(f"{ASSET_MANIFEST}: {asset_id} has an invalid SHA-256")
        if isinstance(status, str) and status.startswith("verified") and digest is None:
            errors.append(f"{ASSET_MANIFEST}: {asset_id} claims verified integrity without a SHA-256")

    pin = rootfs_pin(errors, root)
    ubuntu = by_id.get("ubuntu-base-24.04.3-arm64")
    if ubuntu is None:
        errors.append(f"{ASSET_MANIFEST}: missing ubuntu-base-24.04.3-arm64")
    elif pin is not None:
        for key in ("source_url", "checksums_url", "sha256"):
            if ubuntu.get(key) != pin[key]:
                errors.append(f"{ASSET_MANIFEST}: Ubuntu Base {key} must match scripts/build-ubuntu-rootfs.sh")
        if ubuntu.get("integrity_status") != "verified-pinned-source":
            errors.append(f"{ASSET_MANIFEST}: Ubuntu Base must be marked verified-pinned-source")

    rootfs = by_id.get("minis-ubuntu-rootfs-arm64")
    if rootfs is None:
        errors.append(f"{ASSET_MANIFEST}: missing minis-ubuntu-rootfs-arm64")
    else:
        if rootfs.get("output_path") != "dist/ubuntu-arm64-rootfs.tar.gz":
            errors.append(f"{ASSET_MANIFEST}: generated rootfs output path is incorrect")
        if rootfs.get("sha256") is not None:
            errors.append(f"{ASSET_MANIFEST}: generated rootfs must not claim a fixed repository SHA-256")
        if rootfs.get("integrity_status") != "generated-per-build":
            errors.append(f"{ASSET_MANIFEST}: generated rootfs must be marked generated-per-build")
        if rootfs.get("hash_policy") != "build-time-sidecar":
            errors.append(f"{ASSET_MANIFEST}: generated rootfs must use the build-time-sidecar hash policy")
        if rootfs.get("hash_record") != "dist/ubuntu-arm64-rootfs.tar.gz.sha256":
            errors.append(f"{ASSET_MANIFEST}: generated rootfs hash sidecar path is incorrect")
        if rootfs.get("manifest_record") != "dist/ubuntu-arm64-rootfs.manifest.json":
            errors.append(f"{ASSET_MANIFEST}: generated rootfs manifest path is incorrect")

    minisd = by_id.get("minisd")
    if minisd is None:
        errors.append(f"{ASSET_MANIFEST}: missing minisd")
    else:
        if minisd.get("source_path") != "src/native/minisd/":
            errors.append(f"{ASSET_MANIFEST}: minisd source_path is incorrect")
        if minisd.get("runtime_destination") != "/data/adb/minis/bin/minisd":
            errors.append(f"{ASSET_MANIFEST}: minisd runtime destination is incorrect")
        if minisd.get("sha256") is not None:
            errors.append(f"{ASSET_MANIFEST}: minisd must not claim a universal artifact SHA-256")
        if minisd.get("integrity_status") != "source-built-no-fixed-artifact-hash":
            errors.append(f"{ASSET_MANIFEST}: minisd integrity status is incorrect")

    boundaries = raw.get("verification_boundaries")
    if not isinstance(boundaries, dict):
        errors.append(f"{ASSET_MANIFEST}: verification_boundaries must be an object")
    elif boundaries.get("root_kernelsu_device_e2e") != "not-claimed":
        errors.append(f"{ASSET_MANIFEST}: Root/KernelSU device E2E must remain not-claimed without device evidence")


def check_tree(root: Path) -> list[str]:
    errors: list[str] = []
    if (root / "UPSTREAM.md").exists():
        errors.append("UPSTREAM.md must not remain an active synchronization-policy document")

    for path in active_markdown_files(root):
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        for label, pattern in BANNED_PATTERNS.items():
            if pattern.search(text):
                errors.append(f"{rel}: contains {label}")

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
        root / "CONTRIBUTING.md",
        "CONTRIBUTING.md",
        REQUIRED_CONTRIBUTING_TERMS,
    )
    require_terms(
        errors,
        root / "docs/SECURITY.md",
        "docs/SECURITY.md",
        REQUIRED_SECURITY_TERMS,
    )

    provenance = root / "PROVENANCE.md"
    if not provenance.is_file():
        errors.append("PROVENANCE.md is required")
    else:
        text = provenance.read_text(encoding="utf-8")
        for term in REQUIRED_PROVENANCE_TERMS:
            if term not in text:
                errors.append(f"PROVENANCE.md: missing provenance term {term!r}")

    check_asset_manifest(errors, root)
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
