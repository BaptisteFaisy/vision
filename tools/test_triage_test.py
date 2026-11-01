#!/usr/bin/env python3
"""Tests de `tools/triage_test.py` — stdlib, aucun Gradle, aucun réseau.

Les lignes de journal sont recopiées d'un vrai run (PR #919, 01/11) : le
parsing doit survivre au préfixe `job<TAB>étape<TAB>horodatage` que pose
`gh run view --log-failed`.
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import triage_test as T  # noqa: E402

JOURNAL = "\n".join([
    "ci-app\tTests JVM (:app)\t2026-11-01T18:29:20.5Z > Task :app:testDebugUnitTest",
    "ci-app\tTests JVM (:app)\t2026-11-01T18:30:20.5Z ProbeStairsPriorityTest > "
    "evitement parle sur les escaliers, cap normal tu FAILED",
    "ci-app\tTests JVM (:app)\t2026-11-01T18:30:22.1Z > Task :app:testDebugUnitTest FAILED",
    "ci-app\tTests JVM (:app)\t2026-11-01T18:30:22.1Z 1884 tests completed, 1 failed",
    "ci-app\tTests JVM (:app)\t2026-11-01T18:30:22.1Z BUILD FAILED in 1m 14s",
])

COMPIL_KO = "\n".join([
    "ci-app\tTests JVM (:app)\t2026-11-01T18:29:00.0Z e: "
    "file:///home/baptiste/w/app/src/main/java/dev/x/Foo.kt:12:5 Unresolved reference: bar",
    "ci-app\tTests JVM (:app)\t2026-11-01T18:29:01.0Z > Task :app:compileDebugKotlin FAILED",
])

PUBLIE = """package dev.sam3vision.app.isee
class LiveProbePublishConcurrencyTest {
    fun `deux vols`() { probe.onFrame("A") }
}
"""

PROPRE = """package dev.sam3vision.app.isee
class AutreTest { fun rien() {} }
"""


class Arbre(unittest.TestCase):
    """Dépôt synthétique : deux modules, deux classes de test."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.racine = self.tmp.name
        for module, nom in (("app", "ProbeStairsPriorityTest"),
                            ("app", "LiveProbePublishConcurrencyTest"),
                            ("core", "CrossingAnnounceTest")):
            chemin = os.path.join(self.racine, module, "src", "test", "java",
                                  "dev", nom + ".kt")
            os.makedirs(os.path.dirname(chemin), exist_ok=True)
            with open(chemin, "w", encoding="utf-8") as f:
                f.write(PUBLIE if "Concurrency" in nom else PROPRE)

    def tearDown(self):
        self.tmp.cleanup()

    def chemin(self, module, nom):
        return "%s/src/test/java/dev/%s.kt" % (module, nom)


class Parsing(unittest.TestCase):

    def test_classes_ratees(self):
        ratees = T.tests_ratees(JOURNAL)
        self.assertEqual(1, len(ratees))
        self.assertEqual("ProbeStairsPriorityTest", ratees[0]["classe"])
        self.assertIn("cap normal tu", ratees[0]["test"])

    def test_pas_de_doublon(self):
        self.assertEqual(1, len(T.tests_ratees(JOURNAL + "\n" + JOURNAL)))

    def test_bilan(self):
        self.assertEqual((1884, 1), T.bilan(JOURNAL))

    def test_compilation(self):
        fichiers = T.compilation_ko(COMPIL_KO)
        self.assertEqual(1, len(fichiers))
        self.assertTrue(fichiers[0].endswith("Foo.kt"))

    def test_diff(self):
        self.assertEqual(["a.kt", "b/c.kt"], T.fichiers_diff("a.kt\n\nb/c.kt\n"))


class Classement(Arbre):

    def test_fichier_de_classe(self):
        self.assertEqual(self.chemin("app", "ProbeStairsPriorityTest"),
                         T.fichier_de_classe("ProbeStairsPriorityTest", self.racine))
        self.assertIsNone(T.fichier_de_classe("InconnuTest", self.racine))

    def test_hors_perimetre(self):
        diff = [self.chemin("app", "LiveProbePublishConcurrencyTest")]
        v = T.verdict(JOURNAL, diff, self.racine)
        self.assertEqual("hors-perimetre", v["genre"])
        self.assertFalse(v["classes"][0]["dans_perimetre"])

    def test_dans_le_perimetre(self):
        diff = [self.chemin("app", "ProbeStairsPriorityTest")]
        v = T.verdict(JOURNAL, diff, self.racine)
        self.assertEqual("perimetre", v["genre"])
        self.assertTrue(v["classes"][0]["dans_perimetre"])

    def test_suspects_ne_garde_que_les_tests_de_la_pr(self):
        diff = [self.chemin("app", "LiveProbePublishConcurrencyTest"),
                "docs/CI-QUEUE.md"]
        v = T.verdict(JOURNAL, diff, self.racine)
        self.assertEqual([self.chemin("app", "LiveProbePublishConcurrencyTest")],
                         v["suspects"])

    def test_compilation_sans_test(self):
        v = T.verdict(COMPIL_KO, [], self.racine)
        self.assertEqual("compilation", v["genre"])

    def test_inconnu(self):
        v = T.verdict("rien du tout", [], self.racine)
        self.assertEqual("inconnu", v["genre"])
        self.assertEqual([0, 0], v["bilan"])

    def test_commande_gradle_par_module(self):
        classes = [{"classe": "ProbeStairsPriorityTest",
                    "fichier": self.chemin("app", "ProbeStairsPriorityTest")},
                   {"classe": "CrossingAnnounceTest",
                    "fichier": self.chemin("core", "CrossingAnnounceTest")}]
        cmd = T.commande_gradle(classes, self.racine)
        self.assertTrue(cmd[0].endswith("gradlew"))
        self.assertIn(":app:testDebugUnitTest", cmd)
        self.assertIn(":core:testDebugUnitTest", cmd)
        self.assertIn("*ProbeStairsPriorityTest", cmd)
        self.assertIn("*CrossingAnnounceTest", cmd)

    def test_commande_sans_module(self):
        self.assertIsNone(T.commande_gradle([{"classe": "XTest", "fichier": "docs/X.kt"}]))


class CompteRendu(unittest.TestCase):

    def test_hors_perimetre_et_verte_seule(self):
        v = T.verdict(JOURNAL, [], ".")
        texte = T.resume(v, seule=True)
        self.assertIn("hors périmètre", texte)
        self.assertIn("dépendance d'ordre", texte)
        self.assertIn("1884", texte)

    def test_rouge_seule(self):
        self.assertIn("rouge", T.resume(T.verdict(JOURNAL, [], "."), seule=False))

    def test_vide(self):
        self.assertEqual("- rien de reconnu", T.resume(T.verdict("", [], ".")))


if __name__ == "__main__":
    unittest.main()
