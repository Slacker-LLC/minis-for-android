#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("check_docs_provenance.py")
SPEC = importlib.util.spec_from_file_location("check_docs_provenance", MODULE_PATH)
guard = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(guard)

UBUNTU_SHA256 = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"


def write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def required_text(terms: tuple[str, ...]) -> str:
    return " ".join(terms) + "\n"


def valid_asset_manifest() -> dict:
    return {
        "schema_version": 1,
        "source_lineage": {
            "derived_from_repository": "https://github.com/OpenMinis/OpenMinis",
            "repository_id": "OpenMinis/OpenMinis",
            "license": "GPL-3.0",
            "derivation_revision": None,
            "derivation_revision_status": "not-recorded",
        },
        "assets": [
            {
                "id": "ubuntu-base-24.04.3-arm64",
                "source_url": "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
                "checksums_url": "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
                "sha256": UBUNTU_SHA256,
                "integrity_status": "verified-pinned-source",
            },
            {
                "id": "minis-ubuntu-rootfs-arm64",
                "output_path": "dist/ubuntu-arm64-rootfs.tar.gz",
                "sha256": None,
                "integrity_status": "generated-per-build",
                "hash_policy": "build-time-sidecar",
                "hash_record": "dist/ubuntu-arm64-rootfs.tar.gz.sha256",
                "manifest_record": "dist/ubuntu-arm64-rootfs.manifest.json",
            },
            {
                "id": "minisd",
                "source_path": "src/native/minisd/",
                "runtime_destination": "/data/adb/minis/bin/minisd",
                "sha256": None,
                "integrity_status": "source-built-no-fixed-artifact-hash",
            },
        ],
        "verification_boundaries": {
            "root_kernelsu_device_e2e": "not-claimed",
        },
    }


def write_asset_manifest(root: Path, manifest: dict | None = None) -> None:
    write(
        root,
        guard.ASSET_MANIFEST,
        json.dumps(manifest or valid_asset_manifest(), indent=2) + "\n",
    )


def valid_fixture(root: Path) -> None:
    write(root, "README.md", "# Minis for Android\n" + required_text(guard.REQUIRED_README_TERMS))
    write(root, "README.zh-CN.md", "# Minis for Android\n" + required_text(guard.REQUIRED_README_TERMS))
    write(root, "CONTRIBUTING.md", required_text(guard.REQUIRED_CONTRIBUTING_TERMS))
    write(root, "docs/SECURITY.md", required_text(guard.REQUIRED_SECURITY_TERMS))
    write(root, "docs/EXECUTION-ENVIRONMENT.md", required_text(guard.REQUIRED_EXECUTION_TERMS))
    write(
        root,
        "PROVENANCE.md",
        required_text(guard.REQUIRED_PROVENANCE_TERMS),
    )
    write(
        root,
        "scripts/build-ubuntu-rootfs.sh",
        'REL="${REL:-24.04.3}"\n'
        'case "$REL" in\n'
        f'  24.04.3) PINNED_BASE_SHA256="{UBUNTU_SHA256}" ;;\n'
        '  *) PINNED_BASE_SHA256="" ;;\n'
        'esac\n',
    )
    write_asset_manifest(root)


class ProvenanceGuardTests(unittest.TestCase):
    def test_current_doc_rejects_historical_product_framing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(
                root,
                "README.md",
                "# Minis for Android\n"
                + required_text(guard.REQUIRED_README_TERMS)
                + "Current project differs from OpenMinis and PRoot.\n",
            )
            errors = guard.check_tree(root)
            self.assertTrue(any("README.md" in error for error in errors))

    def test_provenance_and_archive_are_allowlisted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/archive/runtime.md", "OpenMinis Alpine PRoot historical note\n")
            self.assertEqual([], guard.check_tree(root))

    def test_removed_upstream_policy_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "UPSTREAM.md", "legacy sync policy\n")
            self.assertTrue(any("UPSTREAM.md" in error for error in guard.check_tree(root)))

    def test_execution_contract_requires_current_runtime_terms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            text = required_text(guard.REQUIRED_EXECUTION_TERMS).replace("mount namespace", "")
            write(root, "docs/EXECUTION-ENVIRONMENT.md", text)
            self.assertTrue(any("mount namespace" in error for error in guard.check_tree(root)))

    def test_execution_contract_requires_fixed_persistent_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            text = required_text(guard.REQUIRED_EXECUTION_TERMS).replace("/data/adb/minis/home", "")
            write(root, "docs/EXECUTION-ENVIRONMENT.md", text)
            self.assertTrue(any("/data/adb/minis/home" in error for error in guard.check_tree(root)))

    def test_current_docs_reject_obsolete_app_files_backing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(
                root,
                "docs/runtime.md",
                "Persistent workspace is resolved from Context.filesDir.\n",
            )
            errors = guard.check_tree(root)
            self.assertTrue(any("App-filesDir" in error for error in errors))

    def test_current_docs_reject_stale_single_authority_persistence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(
                root,
                "CONTRIBUTING.md",
                required_text(guard.REQUIRED_CONTRIBUTING_TERMS)
                + "The Android app is the single source of truth for persistence.\n",
            )
            errors = guard.check_tree(root)
            self.assertTrue(any("single-authority persistence" in error for error in errors))

    def test_current_docs_reject_app_private_workspace_framing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(
                root,
                "docs/SECURITY.md",
                required_text(guard.REQUIRED_SECURITY_TERMS)
                + "SAF and app-private workspace are separate trust domains.\n",
            )
            errors = guard.check_tree(root)
            self.assertTrue(any("app-private workspace" in error for error in errors))

    def test_readmes_require_current_runtime_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "README.zh-CN.md", "# Minis for Android\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("README.zh-CN.md" in error and "/data/adb/minis" in error for error in errors))

    def test_contributing_requires_persistent_runtime_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "CONTRIBUTING.md", "minisd mount namespace\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("CONTRIBUTING.md" in error and "/data/adb/minis" in error for error in errors))

    def test_security_requires_persistent_backing_rule(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/SECURITY.md", "minisd /data/adb/minis\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("docs/SECURITY.md" in error and "tmpfs" in error for error in errors))

    def test_provenance_requires_source_and_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "PROVENANCE.md", "GPL-3.0\n")
            self.assertTrue(any("OpenMinis/OpenMinis" in error for error in guard.check_tree(root)))

    def test_asset_manifest_is_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            (root / guard.ASSET_MANIFEST).unlink()
            errors = guard.check_tree(root)
            self.assertTrue(any(guard.ASSET_MANIFEST in error and "required" in error for error in errors))

    def test_ubuntu_asset_must_match_build_script_pin(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            manifest = valid_asset_manifest()
            manifest["assets"][0]["sha256"] = "0" * 64
            write_asset_manifest(root, manifest)
            errors = guard.check_tree(root)
            self.assertTrue(any("Ubuntu Base sha256" in error for error in errors))

    def test_verified_asset_requires_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            manifest = valid_asset_manifest()
            manifest["assets"][0]["sha256"] = None
            write_asset_manifest(root, manifest)
            errors = guard.check_tree(root)
            self.assertTrue(any("claims verified integrity without a SHA-256" in error for error in errors))

    def test_generated_rootfs_must_not_claim_fixed_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            manifest = valid_asset_manifest()
            manifest["assets"][1]["sha256"] = "1" * 64
            write_asset_manifest(root, manifest)
            errors = guard.check_tree(root)
            self.assertTrue(any("generated rootfs must not claim a fixed" in error for error in errors))

    def test_minisd_must_not_claim_universal_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            manifest = valid_asset_manifest()
            manifest["assets"][2]["sha256"] = "2" * 64
            write_asset_manifest(root, manifest)
            errors = guard.check_tree(root)
            self.assertTrue(any("minisd must not claim a universal" in error for error in errors))

    def test_root_kernelsu_e2e_cannot_be_claimed_without_device_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            manifest = valid_asset_manifest()
            manifest["verification_boundaries"]["root_kernelsu_device_e2e"] = "verified"
            write_asset_manifest(root, manifest)
            errors = guard.check_tree(root)
            self.assertTrue(any("Root/KernelSU device E2E must remain not-claimed" in error for error in errors))

    def test_missing_derivation_revision_stays_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            manifest = valid_asset_manifest()
            manifest["source_lineage"]["derivation_revision_status"] = "verified"
            write_asset_manifest(root, manifest)
            errors = guard.check_tree(root)
            self.assertTrue(any("missing derivation revision must be marked not-recorded" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
