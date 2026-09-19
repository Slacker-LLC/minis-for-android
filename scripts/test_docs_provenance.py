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
    write(root, "README.md", "# Minis for Android\n" + required_text(guard.REQUIRED_README_TERMS))
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
    write(root, "docs/contracts/01-ARCHITECTURE.md", "direct architecture\n")
    write(root, "docs/contracts/02-CONSTRAINTS.md", "constraints\n")
    write(
        root,
        "docs/contracts/03-STORAGE-CONTRACT.md",
        required_text(guard.REQUIRED_STORAGE_CONTRACT_TERMS),
    )
    write(root, "docs/contracts/04-SECURITY-CONTRACT.md", "security\n")
    write(root, "docs/contracts/05-ENGINEERING.md", "engineering\n")
    write(root, "docs/contracts/06-CURRENT-GAPS.md", "historical minisd /data/adb/minis/workspace baseline\n")
    write(root, "docs/contracts/07-OWNERSHIP-MIGRATION.md", "legacy migration\n")
    write(root, "docs/contracts/08-BOT-COORDINATION.md", "bot coordination\n")


class ProvenanceGuardTests(unittest.TestCase):
    def test_current_doc_rejects_historical_product_framing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "README.md", "# Minis for Android\n" + required_text(guard.REQUIRED_README_TERMS) + "Using OpenMinis here.\n")
            self.assertTrue(any("README.md" in error for error in guard.check_tree(root)))

    def test_negative_context_allows_removed_runtime_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(
                root,
                "docs/runtime.md",
                "The former minisd runtime is removed; no PRoot or Alpine backend is active.\n",
            )
            self.assertEqual([], guard.check_tree(root))

    def test_archive_and_issue_docs_are_historical_allowlists(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/archive/runtime.md", "OpenMinis Alpine PRoot minisd historical note\n")
            write(root, "docs/issue-44-runtime-remnant-audit.md", "minisd is current in this old issue snapshot\n")
            self.assertEqual([], guard.check_tree(root))

    def test_gaps_file_may_name_legacy_runtime(self):
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

    def test_execution_contract_requires_mount_namespace(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            text = required_text(guard.REQUIRED_EXECUTION_TERMS).replace("mount namespace", "")
            write(root, "docs/EXECUTION-ENVIRONMENT.md", text)
            self.assertTrue(any("mount namespace" in error for error in guard.check_tree(root)))

    def test_execution_contract_requires_app_owned_backing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            text = required_text(guard.REQUIRED_EXECUTION_TERMS).replace("Context.filesDir", "")
            write(root, "docs/EXECUTION-ENVIRONMENT.md", text)
            self.assertTrue(any("Context.filesDir" in error for error in guard.check_tree(root)))

    def test_current_docs_reject_active_minisd_framing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(
                root,
                "docs/runtime.md",
                "minisd is the production Root execution broker and all Root requests go through it.\n",
            )
            errors = guard.check_tree(root)
            self.assertTrue(any("obsolete minisd runtime framing" in error for error in errors))

    def test_current_docs_reject_root_owned_user_data_as_current_truth(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/runtime.md", "Persistent Linux data is rooted at /data/adb/minis/workspace.\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("Root-owned user-data" in error for error in errors))

    def test_current_docs_allow_legacy_user_data_migration_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/runtime.md", "Legacy migration source: /data/adb/minis/workspace; it is not active storage.\n")
            self.assertEqual([], guard.check_tree(root))

    def test_current_docs_reject_english_primary_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/README.md", "English is the primary language for docs.\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("English-primary" in error for error in errors))

    def test_readmes_require_rootfs_current_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "README.zh-CN.md", "# Minis for Android\n" + required_text(guard.REQUIRED_ZH_README_AUTHORITY))
            errors = guard.check_tree(root)
            self.assertTrue(any("README.zh-CN.md" in error and "/data/adb/minis/rootfs" in error for error in errors))

    def test_contributing_requires_direct_runtime_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "CONTRIBUTING.md", "Root only\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("CONTRIBUTING.md" in error and "direct Ubuntu" in error for error in errors))

    def test_security_requires_loopback_proxy_boundary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            text = required_text(guard.REQUIRED_SECURITY_TERMS).replace("127.0.0.1:18787", "")
            write(root, "docs/SECURITY.md", text)
            errors = guard.check_tree(root)
            self.assertTrue(any("docs/SECURITY.md" in error and "127.0.0.1:18787" in error for error in errors))

    def test_provenance_requires_source_and_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "PROVENANCE.md", "GPL-3.0\n")
            self.assertTrue(any("OpenMinis/OpenMinis" in error for error in guard.check_tree(root)))

    def test_missing_new_contract_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            (root / "docs/contracts/08-BOT-COORDINATION.md").unlink()
            errors = guard.check_tree(root)
            self.assertTrue(any("08-BOT-COORDINATION.md" in error for error in errors))

    def test_storage_contract_requires_direct_app_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/contracts/03-STORAGE-CONTRACT.md", "/data/adb/minis/rootfs\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("Context.filesDir" in error for error in errors))
            self.assertTrue(any("minis-sessions" in error for error in errors))

    def test_identity_requires_target_application_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "docs/contracts/00-IDENTITY.md", "slacker.llc direct Ubuntu\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("llc.slacker.minis" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
