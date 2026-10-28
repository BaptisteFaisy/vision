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


class SplitHunksTest(unittest.TestCase):
    """Découpage des marqueurs : le propre recopié, les bords nommés."""

    def test_sans_marqueur_tout_propre(self):
        segs = conflict_agent.split_hunks("a\nb\n")
        self.assertEqual(segs, [("clean", "a\nb\n")])

    def test_diff_simple(self):
        text = ("A\n<<<<<<< HEAD\nm1\n=======\nb1\n>>>>>>> br\nZ\n")
        segs = conflict_agent.split_hunks(text)
        self.assertEqual(len(segs), 3)
        self.assertEqual(segs[0], ("clean", "A"))
        kind, hunk = segs[1]
        self.assertEqual(kind, "hunk")
        self.assertEqual(hunk["head"], ["m1"])
        self.assertIsNone(hunk["base"])
        self.assertEqual(hunk["saved"], ["b1"])
        self.assertEqual(segs[2], ("clean", "Z\n"))

    def test_diff3_avec_base(self):
        text = ("<<<<<<< HEAD\nm\n||||||| base\nb\n=======\ns\n>>>>>>> br\n")
        segs = conflict_agent.split_hunks(text)
        self.assertEqual(len(segs), 2)
        kind, hunk = segs[0]
        self.assertEqual(hunk["head"], ["m"])
        self.assertEqual(hunk["base"], ["b"])
        self.assertEqual(hunk["saved"], ["s"])

    def test_rebase_reel_second_bloc_est_branche(self):
        # Non-régression 28/10 : en rebase, "saved" = branche rejouée.
        repo = tempfile.mkdtemp(prefix="fw-sides-")
        self.addCleanup(shutil.rmtree, repo, True)
        old = os.getcwd()
        self.addCleanup(os.chdir, old)
        os.chdir(repo)
        run = lambda *a: subprocess.run(  # noqa: E731
            ["git", *a], capture_output=True, text=True)
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
        with open("f.txt") as fh:
            segs = conflict_agent.split_hunks(fh.read())
        hunks = [s for k, s in segs if k == "hunk"]
        self.assertEqual(len(hunks), 1)
        self.assertEqual(hunks[0]["saved"], ["BRANCHE"])
        self.assertEqual(hunks[0]["head"], ["MASTER"])


if __name__ == "__main__":
    unittest.main()
