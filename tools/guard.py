#!/usr/bin/env python3
"""Garde-fou iSee — quatre contrôles, un verdict.

    python3 tools/guard.py                      # check : la passe complète
    python3 tools/guard.py --quick              # le seul contrôle gratuit (limites)
    python3 tools/guard.py regress [--units …]  # chaque PATCH-*.patch sur copie jetable
    python3 tools/guard.py matrix               # couple par couple : B tient après A ?
    python3 tools/guard.py selftest             # preuves négatives (la porte doit rougir)
    python3 tools/guard.py --freeze             # refiger tools/guard-reference.json

Les quatre contrôles : `limites-500/50` (Kotlin + Python), `tests-jvm`,
`tests-backend`, `banc-cadence`. Le garde **ne compile rien** : Gradle appartient
à la CI, qui dépose les rapports JUnit relus par `tests-jvm` et `banc-cadence`.
Une preuve absente s'affiche `ABSENT` et ne devient un KO que si la passe
l'exige (`--require-jvm`, `--require-cadence`) — jamais un vert par absence de test.

Codes de sortie : 0 = aucun KO, 1 = au moins un KO, 2 = erreur d'usage.
"""
import argparse
import functools
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import guard_lib as gl              # noqa: E402 — chemin posé juste au-dessus
import guard_patches as gp          # noqa: E402
import guard_selftest as gs         # noqa: E402

COMMANDES = ("check", "regress", "matrix", "selftest")


def parse_args(argv):
    """Ligne de commande du garde ; `check` est l'action par défaut."""
    parser = argparse.ArgumentParser(
        description="Garde-fou iSee : limites, tests JVM, tests backend, cadence.")
    parser.add_argument("commande", nargs="?", default="check", choices=COMMANDES)
    parser.add_argument("--root", default=".", help="racine de l'arbre à contrôler")
    parser.add_argument("--units", help="unités à rejouer, séparées par des virgules")
    parser.add_argument("--quick", action="store_true",
                        help="ne jouer que le contrôle gratuit (limites-500/50)")
    parser.add_argument("--freeze", action="store_true",
                        help="réécrire la référence figée d'après l'arbre")
    parser.add_argument("--require-jvm", action="store_true",
                        help="l'absence de rapport JUnit devient un KO")
    parser.add_argument("--require-cadence", action="store_true",
                        help="l'absence de banc de cadence devient un KO")
    return parser.parse_args(argv)


def entete(root, reference):
    """Provenance de la passe : arbre contrôlé et état figé pris comme référence."""
    gele = reference.get("gele", {})
    if not gele:
        return (f"garde-fou iSee — arbre {os.path.abspath(root)}\n"
                f"  référence absente ({gl.REFERENCE}) : tout dépassement sera un KO")
    return (f"garde-fou iSee — arbre {os.path.abspath(root)}\n"
            f"  référence figée sur {gele.get('commit', 'inconnu')} "
            f"({gele.get('le', 'date inconnue')})")


def print_controls(controls):
    """Tableau un contrôle par ligne, puis le verdict binaire."""
    largeur = max(len(control.name) for control in controls)
    for control in controls:
        print(f"  [{control.status:<6}] {control.name:<{largeur}}  {control.detail}")
    ko = [control.name for control in controls if control.failed]
    if ko:
        print("verdict : KO — " + ", ".join(ko))
        return 1
    verts = sum(1 for c in controls if c.status == gl.OK)
    absents = sum(1 for c in controls if c.status == gl.ABSENT)
    warns = sum(1 for c in controls if c.status == gl.WARN)
    print(f"verdict : OK — {verts} au vert, {warns} avertissement(s), "
          f"{absents} absent(s), 0 KO")
    return 0


def unite_liste(args):
    """Les unités demandées par `--units`, ou rien (toutes)."""
    return [u.strip() for u in args.units.split(",") if u.strip()] if args.units else None


def cmd_check(args, reference):
    """La passe complète : les quatre contrôles sur l'arbre."""
    controls = gl.run_all(args.root, reference, quick=args.quick,
                          require_jvm=args.require_jvm,
                          require_cadence=args.require_cadence)
    return print_controls(controls)


def cmd_regress(args, reference):
    """Chaque patch sur une copie jetable ; un patch qui ne tient plus est un KO."""
    patches = gp.regress(args.root, unite_liste(args),
                         controle=functools.partial(gl.limites_text, reference=reference))
    if not patches:
        print(f"regress : aucun {gp.PATCH_GLOB} à la racine — rien à rejouer (non mesuré)")
        return 0
    for nom, ok, detail in patches:
        print(f"  [{'OK ' if ok else 'KO '}]{nom:<8} {detail}")
    kas = [nom for nom, ok, _ in patches if not ok]
    print(f"regress : {len(patches) - len(kas)}/{len(patches)} patch(s) appliqué(s)"
          + (f", KO : {', '.join(kas)}" if kas else ""))
    return 1 if kas else 0


def cmd_matrix(args):
    """Matrice des couples ordonnés : conflit = information, patch A refusé = KO."""
    conflits, couples, lignes = gp.matrix(args.root, unite_liste(args))
    if not couples:
        print(f"matrix : aucun {gp.PATCH_GLOB} inventorié — 0 couple (non mesuré)")
        return 0
    for ligne in lignes:
        print(ligne)
    print(f"matrix : {len(conflits)} CONFLIT sur {couples} couple(s) ordonné(s)"
          + (f" — {', '.join(conflits)}" if conflits else ""))
    return 1 if any("A refusé" in ligne for ligne in lignes) else 0


def cmd_selftest(args):
    """Preuves négatives : la porte doit rougir sur une entrée dégradée."""
    cas = gs.selftest(args.root)
    for nom, attendu, obtenu, ok in cas:
        print(f"  [{'OK ' if ok else 'KO '}] {nom:<38} attendu {attendu:<6} obtenu {obtenu}")
    echoues = [nom for nom, _, _, ok in cas if not ok]
    if echoues:
        print(f"selftest : KO — {len(echoues)} preuve(s) non rapportée(s) : "
              + ", ".join(echoues))
        return 1
    print(f"selftest : OK — {len(cas)} preuve(s) négative(s) rapportée(s)")
    return 0


def cmd_freeze(args):
    """Refige la référence d'après l'arbre : la seule écriture autorisée du garde."""
    reference = gl.measure(args.root)
    gl.save_reference(args.root, reference)
    plancher = reference["tests-jvm"]["plancher"]
    print(f"référence figée : {gl.reference_path(args.root)} — "
          f"{len(reference['limites-500/50']['toleres'])} dépassement(s) toléré(s), "
          f"plancher {sum(plancher.values())} tests")
    return 0


def main(argv=None):
    """Aiguillage ; `--freeze` s'applique avant toute lecture de la référence."""
    args = parse_args(argv)
    if not os.path.isdir(args.root):
        print(f"racine introuvable : {args.root}", file=sys.stderr)
        return 2
    if args.freeze:
        return cmd_freeze(args)
    reference = gl.load_reference(args.root)
    print(entete(args.root, reference))
    if args.commande == "regress":
        return cmd_regress(args, reference)
    if args.commande == "matrix":
        return cmd_matrix(args)
    if args.commande == "selftest":
        return cmd_selftest(args)
    return cmd_check(args, reference)


if __name__ == "__main__":
    sys.exit(main())