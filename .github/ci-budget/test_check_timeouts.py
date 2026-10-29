#!/usr/bin/env python3
"""Tests ciblés de check_timeouts.py — bibliothèque standard uniquement.

Lance : python3 .github/ci-budget/test_check_timeouts.py
(depuis la racine du dépôt).
"""
import os
import shutil
import tempfile
import unittest
from unittest import mock

import check_timeouts


CI_5 = """name: CI

on:
  pull_request:

jobs:
  ci:
    runs-on: [self-hosted]
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 1

concurrency:
  group: x
"""

HORS_PR_10 = """name: natif

on:
  push:
    branches: [master]

jobs:
  apk:
    runs-on: [self-hosted]
    timeout-minutes: 10 # hors PR : cache froid possible
    steps:
      - run: echo coucou

concurrency:
  group: x
"""

SANS_TIMEOUT = """name: oublieux

on:
  pull_request:

jobs:
  ci:
    runs-on: [self-hosted]
    steps:
      - uses: actions/checkout@v4

concurrency:
  group: x
"""

TROP_LONG_PR = """name: trop long

on:
  pull_request:

jobs:
  ci:
    runs-on: [self-hosted]
    timeout-minutes: 10
    steps:
      - run: echo coucou

concurrency:
  group: x
"""


def faux_workflow(test, nom, contenu):
    """Racine temporaire avec .github/workflows/<nom>. Rend le chemin."""
    racine = tempfile.mkdtemp(prefix="ci-budget-")
    test.addCleanup(shutil.rmtree, racine, True)
    dossier = os.path.join(racine, ".github", "workflows")
    os.makedirs(dossier)
    chemin = os.path.join(dossier, nom)
    with open(chemin, "w", encoding="utf-8") as fh:
        fh.write(contenu)
    return racine, chemin


class ScanJobsTest(unittest.TestCase):
    """Lecture des jobs et de leurs timeouts."""

    def test_jobs_et_timeouts(self):
        jobs = check_timeouts.scan_jobs(CI_5.splitlines())
        self.assertEqual(jobs, [["ci", 5]])

    def test_sans_timeout_rend_none(self):
        jobs = check_timeouts.scan_jobs(SANS_TIMEOUT.splitlines())
        self.assertEqual(jobs, [["ci", None]])


class RunsOnPrTest(unittest.TestCase):
    """Seuls les workflows pull_request sont soumis au budget."""

    def test_pr_detectee(self):
        self.assertTrue(check_timeouts.runs_on_pr(CI_5.splitlines()))

    def test_push_non_pr(self):
        self.assertFalse(check_timeouts.runs_on_pr(HORS_PR_10.splitlines()))

    def test_commentaire_ignore(self):
        lignes = ["# pull_request retiré", "on:", "  push:", "jobs:"]
        self.assertFalse(check_timeouts.runs_on_pr(lignes))


class CheckFileTest(unittest.TestCase):
    """Erreurs et avertissements par fichier."""

    def test_pr_5_min_silencieuse(self):
        _, chemin = faux_workflow(self, "ci.yml", CI_5)
        erreurs, avertissements = [], []
        check_timeouts.check_file(chemin, erreurs, avertissements)
        self.assertEqual((erreurs, avertissements), ([], []))

    def test_hors_pr_10_min_accepte(self):
        _, chemin = faux_workflow(self, "natif.yml", HORS_PR_10)
        erreurs, avertissements = [], []
        check_timeouts.check_file(chemin, erreurs, avertissements)
        self.assertEqual(erreurs, [])

    def test_sans_timeout_erreur(self):
        _, chemin = faux_workflow(self, "oubli.yml", SANS_TIMEOUT)
        erreurs, avertissements = [], []
        check_timeouts.check_file(chemin, erreurs, avertissements)
        self.assertEqual(len(erreurs), 1)
        self.assertIn("sans timeout-minutes", erreurs[0])
        self.assertEqual(len(avertissements), 1)  # checkout sans fetch-depth

    def test_pr_trop_long_erreur(self):
        _, chemin = faux_workflow(self, "long.yml", TROP_LONG_PR)
        erreurs, _ = [], []
        check_timeouts.check_file(chemin, erreurs, [])
        self.assertEqual(len(erreurs), 1)
        self.assertIn("> 5", erreurs[0])


class MainTest(unittest.TestCase):
    """Le main compte les workflows de CI_BUDGET_ROOT."""

    def test_racine_verte_rend_0(self):
        racine, _ = faux_workflow(self, "ci.yml", CI_5)
        with mock.patch.dict(os.environ, {"CI_BUDGET_ROOT": racine}):
            self.assertEqual(check_timeouts.main(), 0)

    def test_racine_rouge_rend_1(self):
        racine, _ = faux_workflow(self, "long.yml", TROP_LONG_PR)
        with mock.patch.dict(os.environ, {"CI_BUDGET_ROOT": racine}):
            self.assertEqual(check_timeouts.main(), 1)


if __name__ == "__main__":
    unittest.main()
