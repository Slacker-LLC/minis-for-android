#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("check_build_cleanup.py")
SPEC = importlib.util.spec_from_file_location("check_build_cleanup", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class BuildCleanupGuardTests(unittest.TestCase):
    def make_repo(self) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        for relative in GUARD.REQUIRED_PATHS:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("canonical\n", encoding="utf-8")
        (root / ".github/workflows").mkdir(parents=True, exist_ok=True)
        (root / ".github/workflows/ci.yml").write_text("name: CI\n", encoding="utf-8")
        (root / "BUILDING.md").write_text("Build from src/android.\n", encoding="utf-8")
        return root

    def test_clean_repository_passes(self) -> None:
        self.assertEqual([], GUARD.check_repository(self.make_repo()))

    def test_legacy_wrapper_is_rejected(self) -> None:
        root = self.make_repo()
        (root / "scripts/build-pet-apk.ps1").write_text("legacy\n", encoding="utf-8")
        self.assertTrue(any("obsolete build path exists" in error for error in GUARD.check_repository(root)))

    def test_removed_ios_source_reference_is_rejected(self) -> None:
        root = self.make_repo()
        (root / "scripts/tool.sh").write_text(
            "cp catalog.json src/ios/App/catalog.json\n",
            encoding="utf-8",
        )
        self.assertTrue(any("removed iOS source path" in error for error in GUARD.check_repository(root)))

    def test_upstream_clone_pipeline_is_rejected(self) -> None:
        root = self.make_repo()
        (root / "scripts/tool.ps1").write_text(
            "git clone https://github.com/OpenMinis/OpenMinis.git upstream\n",
            encoding="utf-8",
        )
        self.assertTrue(any("upstream clone pipeline" in error for error in GUARD.check_repository(root)))

    def test_minisd_paths_are_rejected(self) -> None:
        root = self.make_repo()
        obsolete = root / "src/native/minisd"
        obsolete.mkdir(parents=True, exist_ok=True)
        (obsolete / "Cargo.toml").write_text("[package]\nname='minisd'\n", encoding="utf-8")
        self.assertTrue(any("obsolete build path exists" in error for error in GUARD.check_repository(root)))

    def test_minisd_tooling_reference_is_rejected(self) -> None:
        root = self.make_repo()
        (root / "scripts/tool.sh").write_text("bash scripts/build-minisd-android.sh\n", encoding="utf-8")
        self.assertTrue(any("obsolete minisd build/runtime path" in error for error in GUARD.check_repository(root)))

    def test_production_minisd_identity_is_rejected(self) -> None:
        root = self.make_repo()
        source = root / "src/android/app/src/main/java/example/Runtime.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text('val runtime = "minisd"\n', encoding="utf-8")
        self.assertTrue(any("obsolete minisd identity" in error for error in GUARD.check_repository(root)))

    def test_production_minisd_class_identity_is_rejected(self) -> None:
        root = self.make_repo()
        source = root / "src/android/app/src/main/java/example/Runtime.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("class MinisdClient\n", encoding="utf-8")
        self.assertTrue(any("obsolete minisd identity" in error for error in GUARD.check_repository(root)))

    def test_normal_minis_d_prefixed_name_is_not_a_daemon_match(self) -> None:
        root = self.make_repo()
        source = root / "src/android/app/src/main/java/example/Documents.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("class MinisDocumentsProvider\n", encoding="utf-8")
        self.assertEqual([], GUARD.check_repository(root))

    def test_plain_minisd_in_source_comment_does_not_count_as_runtime_dependency(self) -> None:
        root = self.make_repo()
        source = root / "src/android/app/src/main/java/example/Runtime.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("// Historical minisd broker note.\nval current = true\n", encoding="utf-8")
        self.assertEqual([], GUARD.check_repository(root))

    def test_production_minisd_runtime_path_is_rejected_even_in_comment(self) -> None:
        root = self.make_repo()
        source = root / "src/android/app/src/main/java/example/Runtime.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("// Historical runtime.minisd package.\n", encoding="utf-8")
        self.assertTrue(any("obsolete minisd build/runtime path" in error for error in GUARD.check_repository(root)))

    def test_xml_comment_is_ignored_but_resource_value_is_rejected(self) -> None:
        root = self.make_repo()
        source = root / "src/android/app/src/main/res/values/strings.xml"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("<!-- historical minisd note --><resources><string name='x'>ok</string></resources>", encoding="utf-8")
        self.assertEqual([], GUARD.check_repository(root))
        source.write_text("<resources><string name='x'>minisd</string></resources>", encoding="utf-8")
        self.assertTrue(any("obsolete minisd identity" in error for error in GUARD.check_repository(root)))

    def test_migration_only_package_identity_is_allowed(self) -> None:
        root = self.make_repo()
        (root / "src/android/app/build.gradle.kts").write_text(
            'namespace = "com.openminis.app"\napplicationId = "dev.openminispet.android"\n',
            encoding="utf-8",
        )
        self.assertEqual([], GUARD.check_repository(root))

    def test_provenance_document_is_allowlisted(self) -> None:
        root = self.make_repo()
        (root / "UPSTREAM.md").write_text(
            "Historical provenance: git clone https://github.com/OpenMinis/OpenMinis.git\n",
            encoding="utf-8",
        )
        self.assertEqual([], GUARD.check_repository(root))


if __name__ == "__main__":
    unittest.main()
