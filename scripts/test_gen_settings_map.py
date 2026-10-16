#!/usr/bin/env python3
"""Run from the repository root: python3 scripts/test_gen_settings_map.py"""
import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("gen_settings_map.py")


def load():
    spec = importlib.util.spec_from_file_location("gen_settings_map", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


def run(module):
    out = Path(tempfile.mkdtemp()) / "map.html"
    sys.argv = ["gen_settings_map.py", "--out", str(out)]
    err = io.StringIO()
    with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
        code = module.main()
    return code, err.getvalue(), out


class GenSettingsMapTest(unittest.TestCase):
    def test_current_tree_matches_navigation_and_renders(self):
        code, err, out = run(load())
        self.assertEqual(code, 0, err)
        page = out.read_text(encoding="utf-8")
        self.assertIn("设置页面地图", page)
        self.assertNotIn("Routes.", page)

    def test_registered_route_missing_from_the_tree_fails(self):
        module = load()
        agent = module.PAGES[3][1]
        agent[3][:] = [child for child in agent[3] if child[1] != "SOUL"]
        code, err, _ = run(module)
        self.assertEqual(code, 1)
        self.assertIn("route SOUL is registered", err)

    def test_tree_route_that_is_not_registered_fails(self):
        module = load()
        module.PAGES[3][0][3].append(("'幽灵页'", "GHOST_ROUTE", None, []))
        code, err, _ = run(module)
        self.assertEqual(code, 1)
        self.assertIn("GHOST_ROUTE", err)


if __name__ == "__main__":
    unittest.main()
