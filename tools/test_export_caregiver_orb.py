#!/usr/bin/env python3
"""Tests de `tools/export-caregiver-orb.py`.

Lancer : python3 -m unittest tools/test_export_caregiver_orb.py -v
Aucune dépendance externe : uniquement `unittest` et la bibliothèque standard.
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

# Le plus petit shader qui passe le portage : déclaration AGSL, bloc liquide
# à factoriser en helper, point d'entrée à renommer.
SOURCE_FIXTURE = """import x

const val ORB_AGSL = \"\"\"
  // Le liquide :
  col += q.x * half(2.0);
  col *= half2(1.0, 2.0);
vec4 main(vec2 fc) { return vec4(col, 1.0); }
\"\"\"
"""


class RenderTest(unittest.TestCase):
    """Le rendu est pur : mêmes marqueurs, mêmes échappements."""

    def test_marqueurs(self):
        rendu = ORBE.render(SOURCE_FIXTURE)
        self.assertIn("export const fragmentShader = `", rendu)
        self.assertIn("precision highp float;", rendu)
        self.assertIn("vec3 liquid(vec3 col, vec2 q)", rendu)
        self.assertIn("vec4 orb(vec2 fc)", rendu)
        self.assertIn("void main() { gl_FragColor =", rendu)

    def test_types_agsl_portes(self):
        rendu = ORBE.render(SOURCE_FIXTURE)
        self.assertIn("float(2.0)", rendu)
        self.assertIn("vec2(1.0, 2.0)", rendu)
        self.assertNotRegex(rendu, r"\bhalf\d?\b")

    def test_sans_declaration_erreur_claire(self):
        with self.assertRaises(ValueError):
            ORBE.render("const val AUTRE = 1\n")


class CheckTest(unittest.TestCase):
    """--check compare le .mjs versionné au shader, sans rien écrire."""

    def test_arbre_courant_a_jour(self):
        lance = subprocess.run([sys.executable, SCRIPT, "--check"],
                               capture_output=True, text=True, timeout=60)
        self.assertEqual(lance.returncode, 0, lance.stderr)
        self.assertIn("OK", lance.stdout)

    def test_derive_detectee(self):
        with tempfile.TemporaryDirectory() as tmp:
            cible = Path(tmp) / "orb-shader.mjs"
            cible.write_text("// périmé\n", encoding="utf-8")
            avant = ORBE.TARGET
            ORBE.TARGET = cible
            try:
                self.assertFalse(ORBE.check())
            finally:
                ORBE.TARGET = avant

    def test_argument_inconnu_sort_2(self):
        lance = subprocess.run([sys.executable, SCRIPT, "--bogus"],
                               capture_output=True, text=True, timeout=60)
        self.assertEqual(lance.returncode, 2)


if __name__ == "__main__":
    unittest.main()
