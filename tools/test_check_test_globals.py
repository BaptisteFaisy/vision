#!/usr/bin/env python3
"""Tests de `tools/check-test-globals.py` — arbres synthétiques, aucun Gradle."""

import importlib.util
import os
import tempfile
import unittest

# Le garde porte un tiret dans son nom (convention de `tools/check-*.py`) : on
# le charge par chemin plutôt que par `import`.
_CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "check-test-globals.py")
_SPEC = importlib.util.spec_from_file_location("check_test_globals", _CHEMIN)
G = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(G)

PUBLIE_SALE = """package dev.sam3vision.app.isee
class SaleTest {
    @Test fun `lance une frame`() { probe.onFrame("A") }
}
"""

PUBLIE_PROPRE = """package dev.sam3vision.app.isee
class PropreTest {
    @After fun apres() = seanceVide()
    @Test fun `lance une frame`() { probe.onFrame("A") }
}
"""

PUBLIE_BEGIN = """package dev.sam3vision.app.isee
class BeginTest {
    @Test fun `lance une frame`() { probe.begin(); probe.onFrame("A") }
}
"""

AUTRE_RECEPTEUR = """package dev.sam3vision.app.isee.vm
class PictoTest {
    @Test fun `des pictos`() { coordinator.onFrame("frame-1") }
}
"""

INERTE = """package dev.sam3vision.app.isee
class InerteTest { fun rien() {} }
"""


class Arbre(unittest.TestCase):
    """app/src/test synthétique : on écrit les fichiers qu'on veut tester."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.racine = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def ecrire(self, relatif, contenu):
        chemin = os.path.join(self.racine, relatif)
        os.makedirs(os.path.dirname(chemin), exist_ok=True)
        with open(chemin, "w", encoding="utf-8") as f:
            f.write(contenu)
        return relatif

    def test_fichier_hors_regle(self):
        chemin = self.ecrire("app/src/test/java/x/SaleTest.kt", PUBLIE_SALE)
        violations, perimees = G.analyser(self.racine, allow=[])
        self.assertEqual([chemin], violations)
        self.assertEqual([], perimees)

    def test_helper_de_seance_suffit(self):
        self.ecrire("app/src/test/java/x/PropreTest.kt", PUBLIE_PROPRE)
        self.assertEqual(([], []), G.analyser(self.racine, allow=[]))

    def test_begin_suffit(self):
        self.ecrire("app/src/test/java/x/BeginTest.kt", PUBLIE_BEGIN)
        self.assertEqual(([], []), G.analyser(self.racine, allow=[]))

    def test_autre_receveur_ignore(self):
        self.ecrire("app/src/test/java/x/PictoTest.kt", AUTRE_RECEPTEUR)
        self.assertEqual(([], []), G.analyser(self.racine, allow=[]))

    def test_fichier_sans_publication_ignore(self):
        self.ecrire("app/src/test/java/x/InerteTest.kt", INERTE)
        self.assertEqual(([], []), G.analyser(self.racine, allow=[]))

    def test_exemption_respectee(self):
        chemin = self.ecrire("app/src/test/java/x/SaleTest.kt", PUBLIE_SALE)
        violations, _ = G.analyser(self.racine, allow=[chemin])
        self.assertEqual([], violations)

    def test_exemption_devenue_inutile_signalee(self):
        chemin = self.ecrire("app/src/test/java/x/PropreTest.kt", PUBLIE_PROPRE)
        _, perimees = G.analyser(self.racine, allow=[chemin])
        self.assertEqual([chemin], perimees)

    def test_core_hors_perimetre(self):
        """`core` n'a pas de globaux de séance : la garde ne le regarde pas."""
        self.ecrire("core/src/test/java/x/SaleTest.kt", PUBLIE_SALE)
        self.assertEqual(([], []), G.analyser(self.racine, allow=[]))

    def test_racine_absente(self):
        self.assertEqual(2, G.main([os.path.join(self.racine, "absent")]))


class Sorties(unittest.TestCase):

    def test_conseil_nomme_le_fichier_et_le_remède(self):
        texte = G.conseil("app/src/test/java/x/SaleTest.kt")
        self.assertIn("SaleTest.kt", texte)
        self.assertIn("begin()", texte)

    def test_liste_allow_ignore_commentaires(self):
        with tempfile.TemporaryDirectory() as tmp:
            chemin = os.path.join(tmp, "allow.txt")
            with open(chemin, "w", encoding="utf-8") as f:
                f.write("# commentaire\n\n app/src/test/java/x/A.kt \n")
            self.assertEqual(["app/src/test/java/x/A.kt"], G.liste_allow(chemin))

    def test_allow_absente_vaut_liste_vide(self):
        self.assertEqual([], G.liste_allow("/tmp/inexistant-allow-xyz.txt"))


if __name__ == "__main__":
    unittest.main()
