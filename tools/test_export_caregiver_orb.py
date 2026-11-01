#!/usr/bin/env python3
"""Tests for `tools/export-caregiver-orb.py`.

Run: python3 -m unittest tools/test_export_caregiver_orb.py -v
No external dependency: only `unittest` and the standard library.
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "export-caregiver-orb.py")

SPEC = importlib.util.spec_from_file_location("export_caregiver_orb", SCRIPT)
ORBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORBE)

# The smallest shader surviving the port: AGSL declaration, liquid block
# to factor into a helper, entry point to rename.
SOURCE_FIXTURE = """import x

const val ORB_AGSL = \"\"\"
  // Le liquide :
  col += q.x * half(2.0);
  col *= half2(1.0, 2.0);
vec4 main(vec2 fc) { return vec4(col, 1.0); }
\"\"\"
"""


class RenderTest(unittest.TestCase):
    """Rendering is pure: same markers, same escapes."""

    def test_markers(self):
        rendered = ORBE.render(SOURCE_FIXTURE)
        self.assertIn("export const fragmentShader = `", rendered)
        self.assertIn("precision highp float;", rendered)
        self.assertIn("vec3 liquid(vec3 col, vec2 q)", rendered)
        self.assertIn("vec4 orb(vec2 fc)", rendered)
        self.assertIn("void main() { gl_FragColor =", rendered)

    def test_agsl_types_ported(self):
        rendered = ORBE.render(SOURCE_FIXTURE)
        self.assertIn("float(2.0)", rendered)
        self.assertIn("vec2(1.0, 2.0)", rendered)
        self.assertNotRegex(rendered, r"\bhalf\d?\b")

    def test_missing_declaration_clear_error(self):
        with self.assertRaises(ValueError):
            ORBE.render("const val OTHER = 1\n")


class CheckTest(unittest.TestCase):
    """--check compares the committed .mjs to the shader, writing nothing."""

    def test_current_tree_fresh(self):
        run = subprocess.run([sys.executable, SCRIPT, "--check"],
                             capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("OK", run.stdout)

    def test_drift_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "orb-shader.mjs"
            target.write_text("// stale\n", encoding="utf-8")
            before = ORBE.TARGET
            ORBE.TARGET = target
            try:
                self.assertFalse(ORBE.check())
            finally:
                ORBE.TARGET = before

    def test_unknown_argument_exits_2(self):
        run = subprocess.run([sys.executable, SCRIPT, "--bogus"],
                             capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 2)


if __name__ == "__main__":
    unittest.main()
