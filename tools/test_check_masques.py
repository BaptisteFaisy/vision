#!/usr/bin/env python3
"""Tests de la garde `tools/check-masques.py`.

Lancer : python3 -m unittest tools/test_check_masques.py -v
Aucune dépendance externe : uniquement `unittest` et la bibliothèque standard.

Chaque cas monte un **faux dépôt** (copies réelles de `LivePrompts.kt` et des
deux `concepts.py`, mutées en place) puis appelle la garde en processus —
plus un test de fumée qui l'exécute vraiment en sous-processus (délai borné,
code 0 attendu). Aucun fichier du dépôt touché.
"""

import contextlib
import importlib.util
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "check-masques.py")

LIVE = os.path.join(
    "core", "src", "main", "java", "dev", "sam3vision", "core", "live",
    "LivePrompts.kt",
)
ALIASES = [
    os.path.join("server", "backend", "concepts.py"),
    os.path.join("server", "backend", "live_fast", "concepts.py"),
]


def load_guard():
    """Module de la garde chargé depuis son fichier (nom à tiret)."""
    spec = importlib.util.spec_from_file_location("check_masques_probe", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_guard(root):
    """(code, sortie) de la garde en processus sur l'arbre `root`."""
    guard = load_guard()
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = guard.main(["--root", root])
    return code, out.getvalue()


def fake_tree(mutate=None):
    """Faux dépôt : copies réelles, mutation optionnelle, détruire après."""
    tmp = tempfile.mkdtemp(prefix="masques-garde-")
    for rel in [LIVE, *ALIASES]:
        dst = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(os.path.join(ROOT, rel), dst)
    if mutate is not None:
        mutate(tmp)
    return tmp


def drop_concept(concept):
    """Mutation : retire `"concept", ` de la liste dehors."""

    def mutate(tmp):
        path = os.path.join(tmp, LIVE)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text.replace(f'"{concept}", ', "", 1))

    return mutate


def drop_alias(fr):
    """Mutation : retire les lignes d'alias du libellé FR (deux copies)."""

    def mutate(tmp):
        for rel in ALIASES:
            path = os.path.join(tmp, rel)
            with open(path, encoding="utf-8") as handle:
                lines = handle.readlines()
            with open(path, "w", encoding="utf-8") as handle:
                handle.writelines(line for line in lines if f'"{fr}"' not in line)

    return mutate


def flood_prompts(count=10):
    """Mutation : ajoute `count` concepts bidons (dépasse le plafond)."""

    def mutate(tmp):
        path = os.path.join(tmp, LIVE)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        extra = ", ".join(f'"bidon{i}"' for i in range(count))
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text.replace('"souvenir shop",', f'"souvenir shop", {extra},', 1))

    return mutate


class CheckMasquesTest(unittest.TestCase):
    def assertGuard(self, root, expect_ok, *mots):
        code, sortie = run_guard(root)
        if expect_ok:
            self.assertEqual(0, code, sortie)
        else:
            self.assertEqual(1, code, sortie)
        for mot in mots:
            self.assertIn(mot, sortie)

    def test_arbre_reel_conforme(self):
        self.assertGuard(ROOT, True, "11/11")

    def test_prompt_manquant_refuse(self):
        tmp = fake_tree(drop_concept("stairs"))
        self.addCleanup(shutil.rmtree, tmp, True)
        self.assertGuard(tmp, False, "stairs")

    def test_alias_manquant_refuse(self):
        tmp = fake_tree(drop_alias("poussette"))
        self.addCleanup(shutil.rmtree, tmp, True)
        self.assertGuard(tmp, False, "poussette")

    def test_plafond_depasse_refuse(self):
        tmp = fake_tree(flood_prompts())
        self.addCleanup(shutil.rmtree, tmp, True)
        self.assertGuard(tmp, False, "plafond")

    def test_executable_en_sous_processus_reel(self):
        proc = subprocess.run(
            [sys.executable, SCRIPT, "--root", ROOT],
            capture_output=True, text=True, timeout=60, check=True,
        )
        self.assertIn("11/11", proc.stdout)


if __name__ == "__main__":
    unittest.main()
