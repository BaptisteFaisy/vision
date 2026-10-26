#!/usr/bin/env python3
"""Tests de la porte `tools/check-budget-derive.py`.

Lancer : python3 -m unittest tools/test_check_budget_derive.py -v
Aucune dépendance externe : uniquement `unittest` et la bibliothèque standard.

Chaque cas écrit un **faux dépôt** (les constantes citées par le registre + le
document de budget demandé), lance la porte en sous-processus — donc sur son vrai
code de retour — et vérifie la sortie. Aucun fichier du dépôt n'est touché.
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "check-budget-derive.py")
DOC = "docs/LATENCE-BUDGET.md"
GARDE = os.path.join(ROOT, "tools", "check-kotlin-limits.py")

CTX = "core/src/main/java/dev/sam3vision/core/net"
APP = "app/src/main/java/dev/sam3vision/app"

# Les constantes du registre, aux chemins réels — comme dans le dépôt.
CODE = {
    CTX + "/HttpLiveClient.kt":
        "package dev.sam3vision.core.net\n\n"
        "const val LIVE_TIMEOUT_SECONDS: Long = 10L\n\n"
        "const val WAKE_TIMEOUT_SECONDS: Long = 180L\n",
    CTX + "/SamLifecycle.kt":
        "package dev.sam3vision.core.net\n\n"
        "/** Délai de l'extinction : long, la requête peut arriver sur un conteneur endormi. */\n"
        "const val RELEASE_TIMEOUT_SECONDS: Long = 45L\n",
    APP + "/live/LiveWakeGuard.kt":
        "package dev.sam3vision.app.live\n\n"
        "class LiveWakeGuard {\n"
        "    companion object {\n"
        "        const val WAKE_TIMEOUT_MS = 60_000L\n"
        "    }\n"
        "}\n",
    "core/src/main/java/dev/sam3vision/core/live/LiveSession.kt":
        "package dev.sam3vision.core.live\n\n"
        "data class Cadence(val fastMs: Long, val latencyBudgetMs: Long = 0) {\n"
        "    companion object {\n"
        "        val Direct = Cadence(fastMs = 350L, targetMs = 500L, slowMs = 900L)\n\n"
        "        val Fast = Cadence(\n"
        "            fastMs = 0L,\n"
        "            targetMs = 0L,\n"
        "            slowMs = 900L,\n"
        "            concurrency = 1,\n"
        "            latencyBudgetMs = 150L,\n"
        "        )\n"
        "    }\n"
        "}\n",
    APP + "/ihear/IhearCameraPane.kt":
        "package dev.sam3vision.app.ihear\n\nconst val IHEAR_FRAME_EDGE = 320\n",
    APP + "/LiveScreen.kt":
        "package dev.sam3vision.app\n\nconst val LIVE_EDGE = 512\n",
    "core/src/main/java/dev/sam3vision/core/model/ObjectLimits.kt":
        "package dev.sam3vision.core.model\n\nobject ObjectLimits {\n"
        "    const val DEFAULT_ON_SCREEN = 8\n}\n",
    CTX + "/HttpSam31VideoClient.kt":
        "package dev.sam3vision.core.net\n\nprivate const val UPLOAD_TIMEOUT_SECONDS: Long = 120L\n",
    # Fichier hôte des renvois `chemin.kt:ligne` : la ligne 28 porte 60.
    CTX + "/Http.kt": "package dev.sam3vision.core.net\n" + "\n" * 26
        + "const val DEFAULT_TIMEOUT_SECONDS: Long = 60L\n",
}

# Un document qui publie chaque constante du registre avec sa valeur du code.
DOC_CONFORME = """# LATENCE-BUDGET

| étage | chiffre | source |
|---|---|---|
| garde du réveil vidéo | 180 s | `%s/HttpLiveClient.kt::WAKE_TIMEOUT_SECONDS` |
| garde de l'extinction | 45 s | `%s/SamLifecycle.kt::RELEASE_TIMEOUT_SECONDS` |
| fenêtre de réveil côté app | 60 s | `%s/live/LiveWakeGuard.kt::WAKE_TIMEOUT_MS` |
| plancher de cadence du Direct | 350 ms | `%s/LiveSession.kt::Cadence.Direct.fastMs` |
| budget de latence d'iSee | 150 ms | `%s/LiveSession.kt::Cadence.Fast.latencyBudgetMs` |
| côté long de la frame iSee | 320 px | `%s/IhearCameraPane.kt::IHEAR_FRAME_EDGE` |
| côté long de la frame du Direct | 512 px | `%s/LiveScreen.kt::LIVE_EDGE` |
| objets affichés par défaut | 8 | `%s/ObjectLimits.kt::DEFAULT_ON_SCREEN` |
| délai d'envoi d'une vidéo | 120 s | `%s/HttpSam31VideoClient.kt::UPLOAD_TIMEOUT_SECONDS` |
| ancien délai du direct | 10 s | `%s/HttpLiveClient.kt::LIVE_TIMEOUT_SECONDS` |
""" % (CTX, CTX, APP, "core/src/main/java/dev/sam3vision/core/live",
       "core/src/main/java/dev/sam3vision/core/live", APP + "/ihear", APP,
       "core/src/main/java/dev/sam3vision/core/model", CTX, CTX)


def ecrit(racine, chemin, contenu):
    """Écrit un fichier du faux dépôt, dossiers créés au besoin."""
    complet = os.path.join(racine, chemin)
    os.makedirs(os.path.dirname(complet), exist_ok=True)
    with open(complet, "w", encoding="utf-8") as handle:
        handle.write(contenu)


def lance(racine, *args):
    """Lance la porte sur le faux dépôt : rend (code de retour, sortie)."""
    proc = subprocess.run([sys.executable, SCRIPT, "--root", racine, *args],
                          capture_output=True, text=True, timeout=60)
    return proc.returncode, proc.stdout + proc.stderr


def charge_garde():
    """Charge `tools/check-kotlin-limits.py` pour ses contrôles de taille."""
    spec = importlib.util.spec_from_file_location("check_kotlin_limits", GARDE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FauxDepot(unittest.TestCase):
    """Socle : un faux dépôt jetable, les constantes du registre déjà en place."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        for chemin, contenu in CODE.items():
            ecrit(self.tmp.name, chemin, contenu)

    def doc(self, contenu):
        """Pose le document de budget et lance la porte."""
        ecrit(self.tmp.name, DOC, contenu)
        return lance(self.tmp.name)

    def sans_doc(self, *args):
        """Lance la porte sans document de budget."""
        return lance(self.tmp.name, *args)


class TestDocument(FauxDepot):
    """Le document absent, vide, ou dépourvu de renvoi."""

    def test_document_absent_avertit_sans_echouer(self):
        code, sortie = self.sans_doc()
        self.assertEqual(code, 0, sortie)
        self.assertIn("absent", sortie)
        self.assertNotIn("::error::", sortie)

    def test_document_vide_n_a_rien_a_opposer(self):
        code, sortie = self.doc("# LATENCE-BUDGET\n\nAucun chiffre publié.\n")
        self.assertEqual(code, 0, sortie)
        self.assertIn("n'est publié nulle part", sortie)

    def test_strict_refuse_les_constantes_non_publiees(self):
        ecrit(self.tmp.name, DOC, "# En cours\n")
        code, sortie = lance(self.tmp.name, "--strict")
        self.assertEqual(code, 1, sortie)
        self.assertIn("::error::", sortie)

    def test_document_conforme_accepte(self):
        code, sortie = self.doc(DOC_CONFORME)
        self.assertEqual(code, 0, sortie)
        self.assertIn("0 erreur(s), 0 avertissement(s)", sortie)


class TestRenvois(FauxDepot):
    """Contrôle A : `chemin.kt::SYMBOLE` et `chemin.kt:ligne`."""

    def test_renvoi_conforme(self):
        code, sortie = self.doc("| extinction | 45 s | `%s/SamLifecycle.kt::"
                                "RELEASE_TIMEOUT_SECONDS` |\n" % CTX)
        self.assertEqual(code, 0, sortie)
        self.assertNotIn("::error::", sortie)

    def test_renvoi_divergent_est_refuse(self):
        code, sortie = self.doc("| extinction | 60 s | `%s/SamLifecycle.kt::"
                                "RELEASE_TIMEOUT_SECONDS` |\n" % CTX)
        self.assertEqual(code, 1, sortie)
        self.assertIn("45 s", sortie)

    def test_unite_respectee_secondes_contre_millisecondes(self):
        bon, _ = self.doc("| réveil | 180 s | `%s/HttpLiveClient.kt::"
                          "WAKE_TIMEOUT_SECONDS` |\n" % CTX)
        self.assertEqual(bon, 0)
        mauvais, sortie = self.doc("| réveil | 180 ms | `%s/HttpLiveClient.kt::"
                                   "WAKE_TIMEOUT_SECONDS` |\n" % CTX)
        self.assertEqual(mauvais, 1, sortie)
        self.assertIn("180 s", sortie)

    def test_symbole_disparu_du_code(self):
        ecrit(self.tmp.name, CTX + "/SamLifecycle.kt", "package net\n\nconst val AUTRE = 45L\n")
        code, sortie = self.doc("| extinction | 45 s | `%s/SamLifecycle.kt::"
                                "RELEASE_TIMEOUT_SECONDS` |\n" % CTX)
        self.assertEqual(code, 1, sortie)
        self.assertIn("ne se lit plus", sortie)

    def test_renvoi_introuvable(self):
        code, sortie = self.doc("| fantôme | 1 ms | `core/live/Absent.kt::X` |\n")
        self.assertEqual(code, 1, sortie)
        self.assertIn("introuvable", sortie)

    def test_renvoi_ambigu(self):
        ecrit(self.tmp.name, "core/src/main/java/dev/sam3vision/core/LiveScreen.kt",
              "package core\n\nconst val LIVE_EDGE = 512\n")
        code, sortie = self.doc("| frame | 512 px | `LiveScreen.kt::LIVE_EDGE` |\n")
        self.assertEqual(code, 1, sortie)
        self.assertIn("ambigu", sortie)

    def test_renvoi_de_ligne_conforme(self):
        code, sortie = self.doc("| délai HTTP | 60 s | `%s/Http.kt:28` |\n" % CTX)
        self.assertEqual(code, 0, sortie)
        self.assertNotIn("::error::", sortie)

    def test_renvoi_de_ligne_perime(self):
        code, sortie = self.doc("| délai HTTP | 60 s | `%s/Http.kt:3` |\n" % CTX)
        self.assertEqual(code, 1, sortie)
        self.assertIn("renvoi périmé", sortie)


class TestRegistre(FauxDepot):
    """Contrôles B et C : les constantes de latence suivies."""

    def test_citation_par_nom_de_classe(self):
        bon, sortie = self.doc("| extinction | 45 s | `SamLifecycle` |\n")
        self.assertEqual(bon, 0, sortie)
        mauvais, sortie = self.doc("| extinction | 60 s | `SamLifecycle` |\n")
        self.assertEqual(mauvais, 1, sortie)
        self.assertIn("sans la valeur du code", sortie)

    def test_fichier_du_registre_disparu(self):
        os.remove(os.path.join(self.tmp.name, CTX + "/SamLifecycle.kt"))
        code, sortie = self.doc(DOC_CONFORME)
        self.assertEqual(code, 1, sortie)
        self.assertIn("porte périmée", sortie)

    def test_liste_montre_les_valeurs_du_code(self):
        code, sortie = self.sans_doc("--list")
        self.assertEqual(code, 0, sortie)
        self.assertIn("45 s", sortie)
        self.assertIn("180 s", sortie)
        self.assertIn("Cadence.Fast.latencyBudgetMs", sortie)


class TestPorteElleMeme(unittest.TestCase):
    """La porte et ses tests respectent les règles de la maison (500/50)."""

    def test_sous_les_limites_de_taille(self):
        garde = charge_garde()
        for chemin in (SCRIPT, os.path.abspath(__file__)):
            with open(chemin, encoding="utf-8") as handle:
                texte = handle.read()
            problemes = [p.text() for p in garde.check_file(chemin)]
            self.assertEqual(problemes, [], "%s : %s" % (chemin, problemes))
            self.assertLess(len(texte.splitlines()), 500)


if __name__ == "__main__":
    unittest.main()