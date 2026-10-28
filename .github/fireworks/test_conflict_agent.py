#!/usr/bin/env python3
"""Tests ciblés de conflict_agent.py — bibliothèque standard uniquement.

Lance : python3 -m unittest test_conflict_agent -v
(depuis .github/fireworks/).
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

import conflict_agent


class EnvOrTest(unittest.TestCase):
    """Résolution des réglages : une var GitHub absente arrive comme ""."""

    def test_var_absente_rend_defaut(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("FIREWORKS_MODEL", None)
            self.assertEqual(
                conflict_agent.env_or("FIREWORKS_MODEL", "DEFAUT"), "DEFAUT")

    def test_var_vide_rend_defaut(self):
        # Régression 28/10 : modèle "" → 400 Fireworks sur un vrai conflit.
        with mock.patch.dict(os.environ, {"FIREWORKS_MODEL": ""}):
            self.assertEqual(
                conflict_agent.env_or("FIREWORKS_MODEL", "DEFAUT"), "DEFAUT")

    def test_var_renseignee_gagne(self):
        with mock.patch.dict(os.environ, {"FIREWORKS_MODEL": "M"}):
            self.assertEqual(
                conflict_agent.env_or("FIREWORKS_MODEL", "DEFAUT"), "M")


class PushUrlTest(unittest.TestCase):
    """URL push : PAT du CI, repli "origin" en local."""

    def test_avec_token_rend_url_authentifiee(self):
        with mock.patch.dict(os.environ, {"GH_TOKEN": "T"}):
            url = conflict_agent.push_url("O/R")
            self.assertEqual(
                url, "https://x-access-token:T@github.com/O/R.git")

    def test_sans_token_rend_origin(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("GH_TOKEN", None)
            self.assertEqual(conflict_agent.push_url("O/R"), "origin")


class RebaseSidesTest(unittest.TestCase):
    """En rebase, :3: = branche rejouée (inverse du merge)."""

    def test_bords_rebase_branche_premiere(self):
        # Non-régression 28/10 : bords inversés → l'agent gardait master seul.
        repo = tempfile.mkdtemp(prefix="fw-sides-")
        self.addCleanup(shutil.rmtree, repo, True)
        old = os.getcwd()
        self.addCleanup(os.chdir, old)
        os.chdir(repo)
        run = lambda *a: subprocess.run(
            ["git", *a], capture_output=True, text=True)  # noqa: E731
        run("init", "-q")
        run("config", "user.email", "t@t")
        run("config", "user.name", "t")
        with open("f.txt", "w") as fh:
            fh.write("base\n")
        run("add", ".")
        run("commit", "-qm", "base")
        main = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        with open("f.txt", "w") as fh:
            fh.write("BRANCHE\n")
        run("commit", "-qam", "br")
        run("checkout", "-q", main)
        with open("f.txt", "w") as fh:
            fh.write("MASTER\n")
        run("commit", "-qam", "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", main],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)  # conflit attendu
        ours, theirs = conflict_agent.rebase_sides("f.txt")
        self.assertEqual(ours, b"BRANCHE\n")
        self.assertEqual(theirs, b"MASTER\n")


if __name__ == "__main__":
    unittest.main()
