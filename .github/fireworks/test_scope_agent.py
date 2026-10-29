#!/usr/bin/env python3
"""Tests ciblés de scope_agent.py — bibliothèque standard uniquement.

Lance : python3 -m unittest test_scope_agent -v
(depuis .github/fireworks/).
"""
import importlib.util
import os
import unittest

import scope_agent

GARDE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "couche0", "check_couche0.py")


def garde():
    spec = importlib.util.spec_from_file_location("couche0", GARDE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fichier(chemin, ajout="+ligne"):
    return {"filename": chemin, "patch": "@@ -1 +1 @@\n-%s\n%s" % (chemin, ajout)}


class ReponseTest(unittest.TestCase):
    """Lecture des puces rendues par le modèle."""

    def test_deux_puces(self):
        texte = ("- `app/A.kt` — ajoute l'écran\n"
                 "- `docs/X.md` — documente le choix\n")
        self.assertEqual(scope_agent.raisons_repondue(texte),
                         {"app/A.kt": "ajoute l'écran",
                          "docs/X.md": "documente le choix"})

    def test_ignore_les_phrases_autour(self):
        texte = ("Voici la section :\n\n"
                 "## Scope\n- `core/B.kt` — corrige le calcul\n\nVoilà.\n")
        self.assertEqual(scope_agent.raisons_repondue(texte),
                         {"core/B.kt": "corrige le calcul"})

    def test_tiret_long_et_espace(self):
        self.assertEqual(scope_agent.raisons_repondue("- `a/b.kt` — x"),
                         {"a/b.kt": "x"})


class RecomposerTest(unittest.TestCase):
    """Le corps recomposé doit passer le garde, ou ne pas être écrit."""

    def setUp(self):
        self.g = garde()

    def test_section_absente(self):
        fichiers = [fichier("app/A.kt"), fichier("core/B.kt")]
        corps = "## Problème\n\nRien ne marche.\n"
        nouveau, ajouts, retires = scope_agent.recomposer(
            corps, fichiers, {"app/A.kt": "corrige A", "core/B.kt": "corrige B"},
            self.g)
        self.assertEqual(sorted(a.split("`")[1] for a in ajouts),
                         ["app/A.kt", "core/B.kt"])
        self.assertEqual(retires, [])
        erreurs = []
        self.g.check_scope(fichiers, nouveau, erreurs)
        self.assertEqual(erreurs, [])
        self.assertIn("## Problème", nouveau)          # prose conservée

    def test_exempte_non_declaree(self):
        fichiers = [fichier("app/A.kt"),
                    fichier("app/build/sortie.bin"),
                    fichier("gradle/wrapper/x.jar")]
        nouveau, ajouts, _ = scope_agent.recomposer(
            "", fichiers, {"app/A.kt": "corrige A"}, self.g)
        self.assertEqual([a.split("`")[1] for a in ajouts], ["app/A.kt"])
        erreurs = []
        self.g.check_scope(fichiers, nouveau, erreurs)
        self.assertEqual(erreurs, [])

    def test_garde_les_puces_valides_et_ajoute_les_manquantes(self):
        fichiers = [fichier("app/A.kt"), fichier("core/B.kt")]
        corps = ("## Scope\n"
                 "- `app/A.kt` — raison de l'auteur\n"
                 "- `vieux/D.kt` — puce morte\n"
                 "\n## Preuves\n- 11/11\n")
        nouveau, ajouts, retires = scope_agent.recomposer(
            corps, fichiers, {"app/A.kt": "jamais utilisée",
                              "core/B.kt": "corrige B"}, self.g)
        self.assertEqual([a.split("`")[1] for a in ajouts], ["core/B.kt"])
        self.assertEqual(retires, ["vieux/D.kt"])
        self.assertIn("raison de l'auteur", nouveau)   # puce de l'auteur intacte
        self.assertIn("## Preuves", nouveau)
        erreurs = []
        self.g.check_scope(fichiers, nouveau, erreurs)
        self.assertEqual(erreurs, [])


class MessageTest(unittest.TestCase):
    """Le prompt contient chaque fichier une fois, avec un extrait du diff."""

    def test_un_bloc_par_fichier(self):
        fichiers = [fichier("app/A.kt", "+val x = 1"),
                    fichier("core/B.kt", "+val y = 2")]
        messages = scope_agent.messages_pour(
            {"title": "t", "body": "b"}, fichiers, ["app/A.kt", "core/B.kt"])
        contenu = messages[1]["content"]
        self.assertEqual(contenu.count("### app/A.kt"), 1)
        self.assertIn("val x = 1", contenu)


if __name__ == "__main__":
    unittest.main()