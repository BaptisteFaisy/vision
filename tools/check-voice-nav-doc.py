#!/usr/bin/env python3
"""Porte anti-dérive de la navigation vocale (doc <-> code).

Le fonctionnel publié vit dans `docs/NAVIGATION-VOCALE.md` ; les phrases, le
catalogue « en place » et les chiffres qu'il cite doivent être **ceux du
code**. Cette porte échoue dès qu'une citation diverge de la valeur lue dans
le Kotlin ou les ressources — même motif que `check-budget-derive.py`.

Quatre contrôles :

A. **catalogue** (§5) — chaque ligne `en place` `` `nav_x` | « texte » ``
   existe dans `strings.xml` avec ce texte exact (`%1$s` du XML vaut `X` du
   document). Les lignes `à venir` sont réservées, pas vérifiées.
B. **phrases** (§2) — les valeurs lues dans
   `NavigationAnnouncer.kt` (`started`, `GUIDANCE_UNAVAILABLE`, `${...}` valant
   `X`) sont citées telles quelles dans le document.
C. **motifs** (§6) — le nombre de motifs GO publié
   (`NavigationIntent.kt::PATTERNS — N motifs`) égale le nombre d'éléments de
   `PATTERNS` dans le code.
D. **réglage** (§6) — le défaut publié
   (`navigationGuidanceEnabled — défaut oui/non`) égale celui de `Settings.kt`.
E. **ancres** (§6) — chaque chemin `*.kt` du tableau existe sur le disque.

Une citation **absente** n'est pas une divergence : avertissement (`--strict`
le refuse). Document absent = pas encore publié : avertissement.

Usage : python3 tools/check-voice-nav-doc.py [--root .] [--strict] [--list]
Sortie : 0 si conforme, 1 si une citation diverge, 2 si la racine n'existe pas.
"""
import argparse
import os
import re
import sys

DOC_DEFAUT = "docs/NAVIGATION-VOCALE.md"
STRINGS = "app/src/main/res/values/strings.xml"
ANNOUNCER = "app/src/main/java/dev/sam3vision/app/speech/NavigationAnnouncer.kt"
INTENT = "core/src/main/java/dev/sam3vision/core/nav/NavigationIntent.kt"
SETTINGS = "core/src/main/java/dev/sam3vision/core/settings/Settings.kt"

LIGNE_CATALOGUE = re.compile(
    r"^\|\s*`(nav_[a-z0-9_]+)`\s*\|\s*«\s*(.+?)\s*»\s*\|\s*(en place|à venir)\s*\|")
LIGNE_STRING = re.compile(r'<string name="([a-z0-9_]+)">(.*?)</string>')
MODELE_STARTED = re.compile(r'fun started\(destination: String\): String = "(.*)"')
CONST_INDISPONIBLE = re.compile(r'const val GUIDANCE_UNAVAILABLE = "(.*)"')
LISTE_PATTERNS = re.compile(r"private val PATTERNS = listOf\(([^)]*)\)", re.S)
LIGNE_MOTIFS = re.compile(r"NavigationIntent\.kt::PATTERNS\D*(\d+)\s*motifs")
DEFAUT_REGLAGE = re.compile(r"val navigationGuidanceEnabled: Boolean = (true|false)")
LIGNE_DEFAUT = re.compile(r"navigationGuidanceEnabled\D*défaut (oui|non)")
ANCRE_KT = re.compile("`((?:app|core)/[^`]*\\.kt)`?")


def lire(root, chemin):
    """Contenu d'un fichier du dépôt, ou None s'il manque."""
    cible = os.path.join(root, chemin)
    if not os.path.isfile(cible):
        return None
    with open(cible, encoding="utf-8") as fh:
        return fh.read()


def normaliser_modele(texte):
    """`${...}` du Kotlin et `%1$s` du XML valent `X` du document."""
    texte = re.sub(r"\$\{[^}]*\}", "X", texte)
    return texte.replace("%1$s", "X")


def catalogue(doc):
    """Lignes du §5 : (clé, texte, statut)."""
    lignes = []
    for line in doc.splitlines():
        m = LIGNE_CATALOGUE.match(line.strip())
        if m:
            lignes.append((m.group(1), m.group(2), m.group(3)))
    return lignes


def chaines_xml(xml):
    """Dictionnaire nom -> valeur des <string>."""
    return {m.group(1): m.group(2) for m in LIGNE_STRING.finditer(xml)}


def phrases_code(kotlin):
    """(started normalisé, indisponible) lus dans NavigationAnnouncer.kt."""
    demarree, indisponible = None, None
    for line in kotlin.splitlines():
        m = MODELE_STARTED.search(line.strip())
        if m:
            demarree = normaliser_modele(m.group(1))
        m = CONST_INDISPONIBLE.search(line.strip())
        if m:
            indisponible = m.group(1)
    return demarree, indisponible


def compter_patterns(kotlin):
    """Nombre d'éléments de PATTERNS, ou None si illisible."""
    m = LISTE_PATTERNS.search(kotlin)
    if not m:
        return None
    return len([e for e in m.group(1).split(",") if e.strip()])


def defaut_code(kotlin):
    """Défaut de navigationGuidanceEnabled : 'oui', 'non' ou None."""
    m = DEFAUT_REGLAGE.search(kotlin)
    if not m:
        return None
    return "oui" if m.group(1) == "true" else "non"


def controler_catalogue(root, doc):
    """A : lignes §5 « en place » contre strings.xml."""
    erreurs, avertissements = [], []
    xml = lire(root, STRINGS)
    lignes = catalogue(doc)
    if not lignes:
        return erreurs, ["§5 : aucune ligne de catalogue lisible"]
    if xml is None:
        return ["%s introuvable" % STRINGS], avertissements
    chaines = chaines_xml(xml)
    for cle, texte, statut in lignes:
        if statut != "en place":
            continue
        if cle not in chaines:
            erreurs.append("catalogue : `%s` « en place » absent de %s" % (cle, STRINGS))
        elif normaliser_modele(chaines[cle]) != texte:
            erreurs.append("catalogue : `%s` vaut « %s » en XML, « %s » au §5"
                           % (cle, chaines[cle], texte))
    return erreurs, avertissements


def controler_phrases(root, doc):
    """B : valeurs de NavigationAnnouncer.kt citées au §2."""
    erreurs = []
    kotlin = lire(root, ANNOUNCER)
    if kotlin is None:
        return ["%s introuvable" % ANNOUNCER], []
    for phrase in phrases_code(kotlin):
        if phrase is None:
            erreurs.append("%s : started ou GUIDANCE_UNAVAILABLE illisible" % ANNOUNCER)
        elif "« %s »" % phrase not in doc:
            erreurs.append("§2 : « %s » (%s) pas cité tel quel" % (phrase, ANNOUNCER))
    return erreurs, []


def controler_motifs(root, doc):
    """C : motifs GO publiés au §6 contre PATTERNS."""
    kotlin = lire(root, INTENT)
    if kotlin is None:
        return ["%s introuvable" % INTENT], []
    m = LIGNE_MOTIFS.search(doc)
    if m is None:
        return [], ["§6 : ligne `NavigationIntent.kt::PATTERNS — N motifs` absente"]
    attendus = compter_patterns(kotlin)
    if attendus is None:
        return ["%s : PATTERNS illisible" % INTENT], []
    if int(m.group(1)) != attendus:
        return ["§6 : %s motifs publiés, %d dans PATTERNS" % (m.group(1), attendus)], []
    return [], []


def controler_reglage(root, doc):
    """D : défaut publié au §6 contre Settings.kt."""
    kotlin = lire(root, SETTINGS)
    if kotlin is None:
        return ["%s introuvable" % SETTINGS], []
    m = LIGNE_DEFAUT.search(doc)
    if m is None:
        return [], ["§6 : ligne `navigationGuidanceEnabled — défaut oui/non` absente"]
    attendu = defaut_code(kotlin)
    if attendu is None:
        return ["%s : navigationGuidanceEnabled illisible" % SETTINGS], []
    if m.group(1) != attendu:
        return ["§6 : défaut « %s » publié, « %s » dans Settings.kt"
                % (m.group(1), attendu)], []
    return [], []


def controler_ancres(root, doc):
    """E : chaque ancre *.kt du §6 existe sur le disque."""
    erreurs = []
    for ancre in sorted(set(ANCRE_KT.findall(doc))):
        if not os.path.isfile(os.path.join(root, ancre)):
            erreurs.append("§6 : ancre `%s` sans fichier" % ancre)
    return erreurs, []


CONTROLES = (controler_catalogue, controler_phrases, controler_motifs,
             controler_reglage, controler_ancres)


def controler(root):
    """(erreurs, avertissements) de la porte."""
    erreurs, avertissements = [], []
    doc = lire(root, DOC_DEFAUT)
    if doc is None:
        avertissements.append("%s absent : document pas encore publié" % DOC_DEFAUT)
        return erreurs, avertissements
    for controle in CONTROLES:
        echec, alerte = controle(root, doc)
        erreurs.extend(echec)
        avertissements.extend(alerte)
    return erreurs, avertissements


def main(argv):
    parser = argparse.ArgumentParser(description="Porte anti-dérive navigation vocale.")
    parser.add_argument("--root", default=".")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.root):
        print("racine introuvable : %s" % args.root, file=sys.stderr)
        return 2
    if args.list:
        print("A catalogue §5 <-> strings.xml (en place)")
        print("B phrases NavigationAnnouncer.kt citées au §2")
        print("C motifs §6 = len(PATTERNS)")
        print("D défaut §6 = Settings.kt")
        print("E ancres §6 existantes")
        return 0
    erreurs, avertissements = controler(args.root)
    for a in avertissements:
        print("avertissement : %s" % a)
    for e in erreurs:
        print("divergence : %s" % e)
    if erreurs or (args.strict and avertissements):
        return 1
    print("OK — %s conforme au code" % DOC_DEFAUT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
