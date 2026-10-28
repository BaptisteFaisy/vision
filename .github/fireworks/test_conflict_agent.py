#!/usr/bin/env python3
"""Tests ciblés de conflict_agent.py — bibliothèque standard uniquement.

Lance : python3 -m unittest test_conflict_agent -v
(depuis .github/fireworks/).
"""
import os
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


if __name__ == "__main__":
    unittest.main()
