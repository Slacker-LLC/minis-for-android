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

def valid_fixture(root: Path) -> None:
    write(root, "README.md", "# Minis for Android\nCurrent Android runtime.\n")
    write(root, "docs/EXECUTION-ENVIRONMENT.md", "Ubuntu 24.04 minisd mount namespace chroot\n")
    write(root, "PROVENANCE.md", "OpenMinis/OpenMinis https://github.com/OpenMinis/OpenMinis GPL-3.0\n")

class ProvenanceGuardTests(unittest.TestCase):
    def test_current_doc_rejects_historical_product_framing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); valid_fixture(root)
            write(root, "README.md", "Current project differs from OpenMinis and PRoot.\n")
            self.assertTrue(any("README.md" in e for e in guard.check_tree(root)))
    def test_provenance_and_archive_are_allowlisted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); valid_fixture(root)
            write(root, "docs/archive/runtime.md", "OpenMinis Alpine PRoot historical note\n")
            self.assertEqual([], guard.check_tree(root))
    def test_removed_upstream_policy_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); valid_fixture(root); write(root, "UPSTREAM.md", "legacy sync policy\n")
            self.assertTrue(any("UPSTREAM.md" in e for e in guard.check_tree(root)))
    def test_execution_contract_requires_current_runtime_terms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); valid_fixture(root); write(root, "docs/EXECUTION-ENVIRONMENT.md", "Ubuntu 24.04 minisd chroot\n")
            self.assertTrue(any("mount namespace" in e for e in guard.check_tree(root)))
    def test_provenance_requires_source_and_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); valid_fixture(root); write(root, "PROVENANCE.md", "GPL-3.0\n")
            self.assertTrue(any("OpenMinis/OpenMinis" in e for e in guard.check_tree(root)))

if __name__ == "__main__": unittest.main()
