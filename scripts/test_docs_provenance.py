#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("check_docs_provenance.py")
SPEC = importlib.util.spec_from_file_location("check_docs_provenance", MODULE_PATH)
guard = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(guard)


def write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def required_text(terms: tuple[str, ...]) -> str:
    return " ".join(terms) + "\n"


def valid_fixture(root: Path) -> None:
    write(root, "AGENTS.md", required_text(guard.REQUIRED_AGENTS_TERMS))
    write(
        root,
        "README.md",
        "# Minis for Android\n" + required_text(guard.REQUIRED_README_TERMS),
    )
    write(
        root,
        "README.zh-CN.md",
        "# Minis for Android\n"
        + required_text(guard.REQUIRED_README_TERMS)
        + required_text(guard.REQUIRED_ZH_README_AUTHORITY),
    )
    write(root, "CONTRIBUTING.md", required_text(guard.REQUIRED_CONTRIBUTING_TERMS))
    write(root, "CONTRIBUTING.zh-CN.md", required_text(guard.REQUIRED_CONTRIBUTING_TERMS))
    write(root, "docs/SECURITY.md", required_text(guard.REQUIRED_SECURITY_TERMS))
    write(root, "docs/EXECUTION-ENVIRONMENT.md", required_text(guard.REQUIRED_EXECUTION_TERMS))
    write(
        root,
        "PROVENANCE.md",
        "OpenMinis/OpenMinis https://github.com/OpenMinis/OpenMinis GPL-3.0\n",
    )
    write(root, "docs/contracts/00-IDENTITY.md", required_text(guard.REQUIRED_IDENTITY_TERMS))
    write(root, "docs/contracts/01-ARCHITECTURE.md", "architecture\n")
    write(root, "docs/contracts/02-CONSTRAINTS.md", "constraints\n")
    write(
        root,
        "docs/contracts/03-STORAGE-CONTRACT.md",
        required_text(guard.REQUIRED_STORAGE_CONTRACT_TERMS),
    )
    write(root, "docs/contracts/04-SECURITY-CONTRACT.md", "security\n")
    write(root, "docs/contracts/05-ENGINEERING.md", "engineering\n")
    write(root, "docs/contracts/06-CURRENT-GAPS.md", "Context.filesDir gap\n")


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

    def test_gaps_file_may_name_legacy_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
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

    def test_current_docs_reject_english_primary_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/README.md", "English is the primary language for docs.\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("English-primary" in error for error in errors))

    def test_readmes_require_current_runtime_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "README.zh-CN.md", "# Minis for Android\n")
            errors = guard.check_tree(root)
            self.assertTrue(
                any("README.zh-CN.md" in error and "/data/adb/minis" in error for error in errors)
            )

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

    def test_missing_contract_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            (root / "docs/contracts/03-STORAGE-CONTRACT.md").unlink()
            errors = guard.check_tree(root)
            self.assertTrue(any("03-STORAGE-CONTRACT.md" in error for error in errors))

    def test_storage_contract_requires_fixed_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/contracts/03-STORAGE-CONTRACT.md", "/data/adb/minis/workspace\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("/data/adb/minis/home" in error for error in errors))

    def test_identity_requires_target_application_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/contracts/00-IDENTITY.md", "slacker.llc\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("llc.slacker.minis" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
