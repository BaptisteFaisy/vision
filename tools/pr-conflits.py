#!/usr/bin/env python3
"""Mesure des conflits de PR sur BaptisteFaisy/vision.

Répond à une seule question : **qu'est-ce qui fait qu'une PR conflicte ?** Et
donc quelle contre-mesure vaut son coût. Il lit les PR fusionnées (gh), calcule
pour chacune sa durée de vie et le nombre de commits que `master` a reçus
pendant ce temps, puis croise avec le marqueur de l'agent de conflit
(`<!-- agent-conflit … -->` posé par .github/fireworks/conflict_agent.py).

Ce que la mesure du 01/11/2026 a donné (250 PR, 18 conflits) :

    commits de master traversés   conflits
    0 – 5                          4 %   (7/177)
    5 – 20                        14 %   (8/57)
    20 – 60                       13 %   (2/15)
    60 et plus                   100 %   (1/1)

Conclusion : ce n'est pas la taille du diff qui fait le conflit, c'est le
**temps d'exposition** à `master` (~300 commits/jour, ~12/h). D'où
`tools/hooks/pre-push`, qui ramène cette exposition à zéro au moment du push.

Le script dit aussi **quels fichiers** l'agent de conflit a réellement dû
réconcilier (il les nomme dans son compte rendu). Mesure du 01/11 sur 250 PR :
19 fichiers réconciliés, dont `docs/VOIX-DANGER.md` (3), `docs/TRAVERSEE.md` (3),
`Guidance.kt` (2), `fast_core.py` (2), `ProbeWiring.kt` (2) — et **zéro**
`CHANGELOG.md` : le journal est déjà couvert par `merge=union`, il ne coûte
aucune saisine LLM. Utile avant de « corriger » un fichier qu'on croit coupable.

Usage : python3 tools/pr-conflits.py [--limit 250] [--json]
Aucun secret, aucun droit d'écriture : lecture seule via `gh`.
"""

import argparse
import datetime as dt
import json
import re
import statistics
import subprocess
import sys

REPO = "BaptisteFaisy/vision"
DEPOT = "origin/master"
MARQUEURS = ("agent-conflit", "agent de conflit")
# Compte rendu de conflict_agent.py : « Conflit réconcilié par l'agent : 2
# fichier(s) (a/b.kt, c/d.md), rebase poussé sur … »
RECONCILIE = re.compile(r"Conflit réconcilié par l'agent\s*:\s*\d+\s*fichier\(s\)\s*\(([^)]*)\)")


def sh(args, check=True):
    """Lance une commande, rend sa sortie texte."""
    proc = subprocess.run(args, capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise SystemExit("échec : %s\n%s" % (" ".join(args[:3]), proc.stderr.strip()[:300]))
    return proc.stdout


def horodatage(texte):
    return dt.datetime.strptime(texte, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def prs_fusionnees(limite):
    """PR fusionnées (hors PR de file Mergify), avec leurs commentaires."""
    sortie = sh(["gh", "pr", "list", "--repo", REPO, "--state", "merged",
                 "--limit", str(limite), "--json",
                 "number,author,createdAt,mergedAt,comments"])
    return [p for p in json.loads(sortie or "[]")
            if p["author"]["login"] != "app/mergify"]


def fichiers_reconcilies(pr):
    """Fichiers que l'agent de conflit a dû réconcilier sur cette PR."""
    trouves = []
    for commentaire in pr.get("comments") or []:
        for motif in RECONCILIE.finditer(commentaire.get("body") or ""):
            trouves.extend(f.strip() for f in motif.group(1).split(",") if f.strip())
    return trouves


def conflit(pr):
    """Vrai si l'agent de conflit a dû intervenir sur cette PR."""
    for commentaire in pr.get("comments") or []:
        corps = (commentaire.get("body") or "").lower()
        if any(m in corps for m in MARQUEURS):
            return True
    return False


def commits_entre(debut, fin):
    """Nombre de commits que master a reçus entre deux horodatages."""
    sortie = sh(["git", "log", "--format=%H", "--since=" + debut, "--until=" + fin, DEPOT])
    return len(sortie.split())


def mesurer(limite):
    """Une ligne de mesure par PR fusionnée (durée, churn de master, conflit)."""
    lignes = []
    for pr in prs_fusionnees(limite):
        debut, fin = pr["createdAt"], pr["mergedAt"]
        if not fin:
            continue
        minutes = (horodatage(fin) - horodatage(debut)).total_seconds() / 60
        lignes.append({
            "pr": pr["number"],
            "auteur": pr["author"]["login"],
            "minutes": round(minutes, 1),
            "commits": commits_entre(debut, fin),
            "conflit": conflit(pr),
            "fichiers": fichiers_reconcilies(pr),
        })
    if not lignes:
        raise SystemExit("aucune PR fusionnée lisible")
    return lignes


def afficher_tranches(lignes):
    """Taux de conflit par tranche de commits de `master` traversés."""
    print("commits traversés   conflits")
    for bas, haut in ((0, 5), (5, 20), (20, 60), (60, 10 ** 9)):
        tranche = [l for l in lignes if bas <= l["commits"] < haut]
        if not tranche:
            continue
        taux = sum(1 for l in tranche if l["conflit"])
        borne = "∞" if haut > 10 ** 8 else str(haut)
        print("%3d – %-3s           %2d/%3d  (%2.0f %%)"
              % (bas, borne, taux, len(tranche), 100 * taux / len(tranche)))


def afficher_fichiers(lignes):
    """Fichiers que l'agent de conflit a réellement réconciliés."""
    attribues = [f for l in lignes for f in l["fichiers"]]
    if not attribues:
        return
    compte = {}
    for fichier in attribues:
        compte[fichier] = compte.get(fichier, 0) + 1
    print()
    print("fichiers réellement réconciliés par l'agent (%d au total) :" % len(attribues))
    for fichier, nombre in sorted(compte.items(), key=lambda kv: (-kv[1], kv[0]))[:15]:
        print("  %2d  %s" % (nombre, fichier))


def main():
    parseur = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parseur.add_argument("--limit", type=int, default=250, help="PR fusionnées à lire (défaut 250)")
    parseur.add_argument("--json", action="store_true", help="sortie JSON brute")
    args = parseur.parse_args()

    lignes = mesurer(args.limit)
    if args.json:
        print(json.dumps(lignes, ensure_ascii=False, indent=1))
        return 0

    total = len(lignes)
    en_conflit = [l for l in lignes if l["conflit"]]
    print("PR fusionnées analysées : %d — conflits (agent) : %d (%.0f %%)"
          % (total, len(en_conflit), 100 * len(en_conflit) / total))
    print("durée de vie médiane : %.1f min (conflits : %.1f min)"
          % (statistics.median([l["minutes"] for l in lignes]),
             statistics.median([l["minutes"] for l in en_conflit]) if en_conflit else 0))
    print("commits de master traversés, médiane : %d (conflits : %d)"
          % (statistics.median([l["commits"] for l in lignes]),
             statistics.median([l["commits"] for l in en_conflit]) if en_conflit else 0))
    print()
    afficher_tranches(lignes)
    afficher_fichiers(lignes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
