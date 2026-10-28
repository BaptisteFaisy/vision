#!/usr/bin/env python3
"""Tests de la porte `tools/check-voice-nav-doc.py`.

Lancer : python3 -m unittest tools/test_check_voice_nav_doc.py -v
Aucune dépendance externe : uniquement `unittest` et la bibliothèque standard.

Chaque cas écrit un **faux dépôt** (le document + les fichiers de code cités),
lance la porte en sous-processus — donc sur son vrai code de retour — et
vérifie la sortie. Aucun fichier du dépôt n'est touché.
"""
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "check-voice-nav-doc.py")
DOC = "docs/NAVIGATION-VOCALE.md"
STRINGS = "app/src/main/res/values/strings.xml"
ANNOUNCER = "app/src/main/java/dev/sam3vision/app/speech/NavigationAnnouncer.kt"
INTENT = "core/src/main/java/dev/sam3vision/core/nav/NavigationIntent.kt"
SETTINGS = "core/src/main/java/dev/sam3vision/core/settings/Settings.kt"

XML = ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
       '    <string name="nav_confirm_destination">Navigation vers %1$s</string>\n'
       '    <string name="nav_guidance_unavailable">Je ne peux pas ouvrir le guidage</string>\n'
       "</resources>\n")

ANNONCEUR = ("package dev.sam3vision.app.speech\n\nobject NavigationPhrases {\n"
             '    fun started(destination: String): String = "Navigation vers '
             '${destination.trim()}"\n'
             '    const val GUIDANCE_UNAVAILABLE = "Je ne peux pas ouvrir le guidage"\n'
             "}\n")

INTENTION = ("package dev.sam3vision.core.nav\n\nobject NavigationIntentParser {\n"
             "    private val JE_VEUX = Regex(\"x\")\n"
             "    private val ALLER = Regex(\"y\")\n"
             "    private val PATTERNS = listOf(JE_VEUX, ALLER)\n"
             "}\n")

REGLAGES = ("package dev.sam3vision.core.settings\n\ndata class Settings(\n"
            "    val navigationGuidanceEnabled: Boolean = true,\n)\n")

DOCUMENT = """# Navigation vocale

## 2. Annonces

| Événement | Phrase exacte |
|---|---|
| Lancé | « Navigation vers X » |
| Impossible | « Je ne peux pas ouvrir le guidage » |

## 5. Catalogue

| Clé | Texte FR | Statut |
|---|---|---|
| `nav_confirm_destination` | « Navigation vers X » | en place |
| `nav_guidance_unavailable` | « Je ne peux pas ouvrir le guidage » | en place |
| `nav_stop_done` | « Guidage arrêté » | à venir |

## 6. Ancres

| Fait publié | Ancre |
|---|---|
| Motifs GO | `%s::PATTERNS` — 2 motifs |
| Phrases | `%s::NavigationPhrases` |
| Réglage | `%s::navigationGuidanceEnabled` — défaut oui |
""" % (INTENT, ANNOUNCER, SETTINGS)


def faux_depot(fichiers):
    """Répertoire temporaire contenant fichiers = {chemin relatif: contenu}."""
    depot = tempfile.mkdtemp(prefix="voix-nav-")
    for chemin, contenu in fichiers.items():
        cible = os.path.join(depot, chemin)
        os.makedirs(os.path.dirname(cible), exist_ok=True)
        with open(cible, "w", encoding="utf-8") as fh:
            fh.write(contenu)
    return depot


def porte(depot, *options):
    """(code, sortie) de la porte sur le faux dépôt."""
    lance = subprocess.run([sys.executable, SCRIPT, "--root", depot, *options],
                           capture_output=True, text=True, timeout=60)
    return lance.returncode, lance.stdout + lance.stderr


def depot_conforme(**variantes):
    """Faux dépôt conforme, avec variantes {chemin: contenu} appliquées."""
    fichiers = {DOC: DOCUMENT, STRINGS: XML, ANNOUNCER: ANNONCEUR,
                INTENT: INTENTION, SETTINGS: REGLAGES}
    fichiers.update(variantes)
    return faux_depot(fichiers)


class PorteVoixNavTest(unittest.TestCase):

    def test_conforme(self):
        code, sortie = porte(depot_conforme())
        self.assertEqual(code, 0, sortie)
        self.assertIn("OK", sortie)

    def test_catalogue_divergent(self):
        xml = XML.replace("Navigation vers %1$s", "Cap sur %1$s")
        code, sortie = porte(depot_conforme(**{STRINGS: xml}))
        self.assertEqual(code, 1, sortie)
        self.assertIn("nav_confirm_destination", sortie)

    def test_catalogue_cle_absente_du_xml(self):
        doc = DOCUMENT.replace("`nav_stop_done` | « Guidage arrêté » | à venir",
                               "`nav_stop_done` | « Guidage arrêté » | en place")
        code, sortie = porte(depot_conforme(**{DOC: doc}))
        self.assertEqual(code, 1, sortie)
        self.assertIn("nav_stop_done", sortie)

    def test_a_venir_ignore(self):
        # `nav_stop_done` est « à venir » et absent du XML : conforme.
        code, sortie = porte(depot_conforme())
        self.assertEqual(code, 0, sortie)

    def test_phrase_non_citee(self):
        doc = DOCUMENT.replace("« Navigation vers X »", "« Cap sur X »")
        code, sortie = porte(depot_conforme(**{DOC: doc}))
        self.assertEqual(code, 1, sortie)
        self.assertIn("Navigation vers X", sortie)

    def test_motifs_divergents(self):
        doc = DOCUMENT.replace("— 2 motifs", "— 9 motifs")
        code, sortie = porte(depot_conforme(**{DOC: doc}))
        self.assertEqual(code, 1, sortie)
        self.assertIn("9 motifs publiés, 2 dans PATTERNS", sortie)

    def test_reglage_divergent(self):
        code, sortie = porte(depot_conforme(
            **{SETTINGS: REGLAGES.replace("= true", "= false")}))
        self.assertEqual(code, 1, sortie)
        self.assertIn("défaut", sortie)

    def test_ancre_sans_fichier(self):
        doc = DOCUMENT + "| Fantôme | `core/src/main/java/Fantome.kt` |\n"
        code, sortie = porte(depot_conforme(**{DOC: doc}))
        self.assertEqual(code, 1, sortie)
        self.assertIn("Fantome.kt", sortie)

    def test_document_absent_avertit(self):
        depot = faux_depot({STRINGS: XML, ANNOUNCER: ANNONCEUR,
                            INTENT: INTENTION, SETTINGS: REGLAGES})
        code, sortie = porte(depot)
        self.assertEqual(code, 0, sortie)
        self.assertIn("avertissement", sortie)

    def test_document_absent_strict_refuse(self):
        depot = faux_depot({STRINGS: XML})
        code, sortie = porte(depot, "--strict")
        self.assertEqual(code, 1, sortie)

    def test_citation_absente_avertit_strict_refuse(self):
        doc = "\n".join(l for l in DOCUMENT.splitlines() if "motifs" not in l) + "\n"
        depot = depot_conforme(**{DOC: doc})
        code, sortie = porte(depot)
        self.assertEqual(code, 0, sortie)
        self.assertIn("avertissement", sortie)
        code, sortie = porte(depot, "--strict")
        self.assertEqual(code, 1, sortie)

    def test_racine_inconnue(self):
        code, _ = porte(os.path.join(tempfile.mkdtemp(), "absent"))
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
