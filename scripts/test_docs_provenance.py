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


def valid_execution_text() -> str:
    return " ".join(guard.REQUIRED_EXECUTION_TERMS) + "\n"


def valid_readme_text() -> str:
    return "# Minis for Android\n" + " ".join(guard.REQUIRED_README_TERMS) + "\n"


def valid_fixture(root: Path) -> None:
    write(root, "README.md", valid_readme_text())
    write(root, "README.zh-CN.md", valid_readme_text())
    write(root, "docs/EXECUTION-ENVIRONMENT.md", valid_execution_text())
    write(
        root,
        "PROVENANCE.md",
        "OpenMinis/OpenMinis https://github.com/OpenMinis/OpenMinis GPL-3.0\n",
    )


class ProvenanceGuardTests(unittest.TestCase):
    def test_current_doc_rejects_historical_product_framing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "README.md", valid_readme_text() + "Current project differs from OpenMinis and PRoot.\n")
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
            text = valid_execution_text().replace("mount namespace", "")
            write(root, "docs/EXECUTION-ENVIRONMENT.md", text)
            self.assertTrue(any("mount namespace" in error for error in guard.check_tree(root)))

    def test_execution_contract_requires_fixed_persistent_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            text = valid_execution_text().replace("/data/adb/minis/home", "")
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

    def test_readmes_require_current_runtime_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "README.zh-CN.md", "# Minis for Android\n")
            errors = guard.check_tree(root)
            self.assertTrue(any("README.zh-CN.md" in error and "/data/adb/minis" in error for error in errors))

    def test_provenance_requires_source_and_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_fixture(root)
            write(root, "PROVENANCE.md", "GPL-3.0\n")
            self.assertTrue(any("OpenMinis/OpenMinis" in error for error in guard.check_tree(root)))


if __name__ == "__main__":
    unittest.main()
