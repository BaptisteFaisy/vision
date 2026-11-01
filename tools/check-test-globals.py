#!/usr/bin/env python3
"""Garde : un test qui pilote une publication remet la séance à zéro.

Le 01/11 (PR #919), `LiveProbePublishConcurrencyTest` a publié de vraies frames
sans remettre les globaux de séance (`ProbeTracks`, `StairCannyCount`,
`ProbeBackgroundMotion`). `ProbeStairsPriorityTest`, exécuté après lui dans la
même JVM, s'est tu : sa personne passait pour une piste déjà prévenue. La PR
n'avait rien cassé — mais elle était bloquée, et personne ne savait pourquoi.

La règle est courte : **piloter une publication, c'est démarrer une séance**.
Un fichier de test `app/` qui appelle `.onFrame(`, `.dispatch(` ou `.runFrame(`
doit donc, dans le même fichier, soit appeler `begin()` (qui remet les globaux
à zéro), soit appeler une des remises à zéro explicites. Les fichiers
antérieurs à la garde sont listés dans `tools/test-globals-allow.txt` (dette
mesurée, retirée au fil de l'eau).

Usage : python3 tools/check-test-globals.py [racine] [--liste]
Sortie : 0 conforme, 1 violation, 2 racine absente.
"""

import argparse
import os
import sys

RACINE_DEFAUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOW = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "test-globals-allow.txt")
# Modules couverts : `core` n'a pas de globaux de séance (pas de `LiveProbe`).
MODULES = ("app",)
SOUS_CHEMIN = os.path.join("src", "test")
# Receveur `probe` : convention du dépôt pour la `LiveProbe` dans les tests. Un
# `coordinator.onFrame(...)` (pictos) ou un faux répartiteur de frames n'écrit
# pas les globaux de séance et ne doit pas être signalé.
PUBLIE = ("probe.onFrame(", "probe.dispatch(", "probe.runFrame(")
# Remises à zéro admises : `begin()` les appelle toutes depuis le 01/11.
NETTOIE = ("begin()", "seanceVide(", "ProbeTracks.reset(", "StairCannyCount.clear(",
           "ProbeBackgroundMotion.reset(", "MotionLog.reset(",
           "cleanSessionGlobals(")
SKIP_DIRS = {"build", ".gradle", ".idea", "__pycache__", ".kotlin"}


def fichiers_de_test(racine):
    """Fichiers Kotlin de test des modules couverts, triés."""
    trouves = []
    for module in MODULES:
        base = os.path.join(racine, module, SOUS_CHEMIN)
        for chemin, dossiers, noms in os.walk(base):
            dossiers[:] = [d for d in dossiers if d not in SKIP_DIRS]
            for nom in noms:
                if nom.endswith(".kt"):
                    trouves.append(os.path.relpath(os.path.join(chemin, nom),
                                                   racine))
    return sorted(trouves)


def lit(racine, chemin):
    """Contenu d'un fichier, jamais d'exception vers l'appelant."""
    try:
        with open(os.path.join(racine, chemin), encoding="utf-8",
                  errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def pilote_publication(texte):
    """Le fichier lance-t-il des frames (donc écrit-il les globaux) ?"""
    return any(motif in texte for motif in PUBLIE)


def remet_a_zero(texte):
    """Le fichier remet-il la séance à zéro ?"""
    return any(motif in texte for motif in NETTOIE)


def liste_allow(chemin=ALLOW):
    """Chemins exemptés (commentaires et lignes vides ignorés)."""
    try:
        with open(chemin, encoding="utf-8") as f:
            lignes = f.read().splitlines()
    except OSError:
        return []
    return [l.strip() for l in lignes
            if l.strip() and not l.strip().startswith("#")]


def analyser(racine, allow=None):
    """(violations, exemptions devenues inutiles) sur l'arbre donné."""
    allow = liste_allow() if allow is None else allow
    violations, perimees = [], []
    for chemin in fichiers_de_test(racine):
        texte = lit(racine, chemin)
        if not pilote_publication(texte):
            continue
        propre = remet_a_zero(texte)
        if not propre and chemin not in allow:
            violations.append(chemin)
        elif propre and chemin in allow:
            perimees.append(chemin)
    return violations, perimees


def conseil(chemin):
    """Ce qu'il faut écrire pour rentrer dans la règle."""
    return ("%s : pilote une publication sans remettre les globaux de séance — "
            "ajoute `probe.begin()` (ou un `@After { StairCannyCount.clear(); "
            "ProbeTracks.reset() }`) dans le test." % chemin)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("racine", nargs="?", default=RACINE_DEFAUT)
    p.add_argument("--liste", action="store_true",
                   help="imprime les fichiers hors règle (à reporter dans "
                        "tools/test-globals-allow.txt) et sort 0")
    p.add_argument("--allow", default=ALLOW, help="fichier d'exemptions")
    args = p.parse_args(argv)
    if not os.path.isdir(args.racine):
        print("garde globaux : racine absente %s" % args.racine, file=sys.stderr)
        return 2
    violations, perimees = analyser(args.racine, liste_allow(args.allow))
    if args.liste:
        for chemin in violations:
            print(chemin)
        return 0
    for chemin in violations:
        print("ERREUR " + conseil(chemin), file=sys.stderr)
    for chemin in perimees:
        print("note : %s remet maintenant la séance à zéro — retire-le de %s"
              % (chemin, os.path.basename(args.allow)), file=sys.stderr)
    if violations:
        print("garde globaux : %d fichier(s) hors règle" % len(violations),
              file=sys.stderr)
        return 1
    print("garde globaux : OK (aucun test qui publie sans remise à zéro)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
