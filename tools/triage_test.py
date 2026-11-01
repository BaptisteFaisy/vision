#!/usr/bin/env python3
"""Triage d'un test rouge : est-ce la PR, ou le voisinage des tests ?

Vécu le 01/11 (PR #919) : `ci-app` est tombé sur `ProbeStairsPriorityTest`,
une classe que la PR **ne touchait pas**. Le vrai coupable était la nouvelle
classe de test de la PR, qui publiait de vraies frames et laissait les globaux
de séance sales (`ProbeTracks`) ; la classe victime, lancée seule, passait.
Sans triage, le balayeur a appelé l'agent de réparation deux fois (150 s de
budget, « budget dépassé »), la PR est restée bloquée une demi-heure.

Ce module rend ce diagnostic **déterministe et gratuit** : il lit le journal
du run en échec, en sort les classes de test tombées, les rapproche des
fichiers du diff, et désigne les suspects (les tests de la PR qui pilotent une
publication). Avec `--verifier`, il relance la classe victime seule : verte
seule = dépendance d'ordre, pas la PR.

Usage :
  python3 tools/triage_test.py --log run.log --diff fichiers.txt [--racine .]
  python3 tools/triage_test.py ... --verifier --json /tmp/verdict.json

Sorties : 0 = test hors périmètre (la PR ne touche pas la classe tombée),
1 = test dans le périmètre (c'est la PR), 2 = compilation, 3 = rien de reconnu.
Stdlib uniquement, aucun réseau.
"""

import argparse
import json
import os
import re
import subprocess
import sys

# `SomeTest > un nom de test FAILED` (journal Gradle, éventuellement préfixé
# par `job<TAB>étape<TAB>horodatage`).
TEST_RE = re.compile(r"([A-Za-z_][\w.$]*Test) > (.+?) (?:FAILED|SKIPPED\b.*)$")
# Erreur de compilation Kotlin/Java : `e: file:///chemin/Foo.kt:12:5 ...`.
COMPIL_RE = re.compile(r"e: file://(\S+?):(\d+):")
# Ligne de bilan : `1884 tests completed, 1 failed`.
BILAN_RE = re.compile(r"(\d+) tests? completed, (\d+) failed")
# Un test de la PR qui pilote une publication réelle : suspect de pollution.
PUBLIE_RE = re.compile(r"\.onFrame\(|\.dispatch\(|\.runFrame\(")


def _lignes_journal(texte):
    """Isole le message de chaque ligne de journal (retire job/étape/horodatage)."""
    for brute in texte.splitlines():
        morceaux = brute.split("\t")
        yield morceaux[-1] if len(morceaux) >= 3 else brute


def tests_ratees(texte):
    """Classes de test tombées, dans l'ordre du journal, sans doublon."""
    vues, sortie = set(), []
    for ligne in _lignes_journal(texte):
        m = TEST_RE.search(ligne.strip())
        if not m:
            continue
        classe, nom = m.group(1), m.group(2).strip()
        if (classe, nom) in vues:
            continue
        vues.add((classe, nom))
        sortie.append({"classe": classe, "test": nom})
    return sortie


def compilation_ko(texte):
    """Fichiers en erreur de compilation (chemins absolus du journal)."""
    vus = []
    for ligne in _lignes_journal(texte):
        m = COMPIL_RE.search(ligne)
        if m and m.group(1) not in vus:
            vus.append(m.group(1))
    return vus


def bilan(texte):
    """(total, échecs) du dernier bilan Gradle, ou (0, 0)."""
    total = echecs = 0
    for ligne in _lignes_journal(texte):
        m = BILAN_RE.search(ligne)
        if m:
            total, echecs = int(m.group(1)), int(m.group(2))
    return total, echecs


def fichiers_diff(texte):
    """Chemins modifiés par la PR (sortie de `gh pr diff --name-only`)."""
    return [l.strip() for l in texte.splitlines() if l.strip()]


def fichier_de_classe(classe, racine="."):
    """Chemin relatif du source d'une classe de test, ou None."""
    simple = classe.rsplit(".", 1)[-1] + ".kt"
    for module in ("app", "core"):
        base = os.path.join(racine, module, "src", "test")
        if not os.path.isdir(base):
            continue
        for racine_mod, _, noms in os.walk(base):
            if simple in noms:
                return os.path.relpath(os.path.join(racine_mod, simple), racine)
    return None


def suspects(diff, racine="."):
    """Tests *de la PR* qui pilotent une publication réelle (pollueurs probables)."""
    trouves = []
    for chemin in diff:
        if not chemin.endswith(".kt"):
            continue
        if "/src/test/" not in chemin and "/src/androidTest/" not in chemin:
            continue
        absolu = os.path.join(racine, chemin)
        try:
            with open(absolu, encoding="utf-8", errors="replace") as f:
                texte = f.read()
        except OSError:
            continue
        if PUBLIE_RE.search(texte):
            trouves.append(chemin)
    return trouves


def classer(classes, diff, racine="."):
    """Rapproche chaque classe tombée des fichiers du diff."""
    modifie = set(diff)
    sortie = []
    for item in classes:
        chemin = fichier_de_classe(item["classe"], racine)
        sortie.append({
            "classe": item["classe"],
            "test": item["test"],
            "fichier": chemin,
            "dans_perimetre": bool(chemin and chemin in modifie),
        })
    return sortie


def verdict(texte, diff, racine="."):
    """Verdict de triage, sans rien lancer."""
    classes = classer(tests_ratees(texte), diff, racine)
    total, echecs = bilan(texte)
    compil = compilation_ko(texte)
    if compil and not classes:
        return {"genre": "compilation", "classes": [], "fichiers": compil,
                "suspects": [], "bilan": [total, echecs]}
    if not classes:
        return {"genre": "inconnu", "classes": [], "fichiers": [],
                "suspects": [], "bilan": [total, echecs]}
    dans = [c for c in classes if c["dans_perimetre"]]
    return {
        "genre": "perimetre" if dans else "hors-perimetre",
        "classes": classes,
        "fichiers": [],
        "suspects": suspects(diff, racine),
        "bilan": [total, echecs],
    }


def module_de(chemin):
    """Module Gradle d'un fichier de test (`app`/`core`), ou None."""
    if not chemin:
        return None
    premier = chemin.split("/", 1)[0]
    return premier if premier in ("app", "core") else None


def commande_gradle(classes, racine="."):
    """Commande de relance ciblée des classes seules, par module."""
    par_module = {}
    for item in classes:
        module = module_de(item.get("fichier"))
        if module:
            par_module.setdefault(module, []).append(item["classe"])
    if not par_module:
        return None
    taches, filtres = [], []
    for module, noms in sorted(par_module.items()):
        taches.append(":%s:testDebugUnitTest" % module)
        for nom in sorted(set(noms)):
            filtres += ["--tests", "*%s" % nom.rsplit(".", 1)[-1]]
    return [os.path.join(racine, "gradlew")] + taches + filtres + [
        "--console=plain", "-q"]


def verifier(classes, racine=".", delai=900):
    """Relance les classes tombées SEULES : True = vertes seules."""
    cmd = commande_gradle(classes, racine)
    if not cmd:
        return None, "module inconnu"
    try:
        proc = subprocess.run(cmd, cwd=racine, capture_output=True, text=True,
                              timeout=delai)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return None, "relance impossible : %s" % exc.__class__.__name__
    return proc.returncode == 0, (proc.stdout or "")[-4000:]


def resume(v, seule=None):
    """Texte lisible du verdict (commentaire de PR, journal de job)."""
    lignes = []
    total, echecs = v["bilan"]
    if v["genre"] == "compilation":
        lignes.append("Compilation en échec (%s) — pas un test."
                      % ", ".join(os.path.basename(f) for f in v["fichiers"][:5]))
    for c in v["classes"]:
        ou = "dans le périmètre" if c["dans_perimetre"] else "**hors périmètre**"
        lignes.append("`%s` → `%s` (%s)" % (c["test"], c["classe"], ou))
    if seule is True:
        lignes.append("Relancée seule : **verte** → dépendance d'ordre entre "
                      "tests (ou verdict de `master`), pas le code de la PR.")
    elif seule is False:
        lignes.append("Relancée seule : **rouge** → la classe échoue vraiment.")
    if v["suspects"]:
        lignes.append("Suspects (tests de la PR qui publient des frames, donc "
                      "écrivent les globaux de séance) : %s"
                      % ", ".join("`%s`" % s for s in v["suspects"][:6]))
    if total:
        lignes.append("Bilan Gradle : %d tests, %d échec(s)." % (total, echecs))
    return "\n".join("- " + l for l in lignes) if lignes else "- rien de reconnu"


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--log", required=True, help="journal du run en échec")
    p.add_argument("--diff", required=True,
                   help="fichiers de la PR (gh pr diff --name-only)")
    p.add_argument("--racine", default=".", help="racine du dépôt")
    p.add_argument("--verifier", action="store_true",
                   help="relance les classes tombées seules (Gradle)")
    p.add_argument("--json", help="écrit le verdict dans ce fichier")
    args = p.parse_args(argv)
    try:
        with open(args.log, encoding="utf-8", errors="replace") as f:
            texte = f.read()
        with open(args.diff, encoding="utf-8", errors="replace") as f:
            diff = fichiers_diff(f.read())
    except OSError as exc:
        print("triage : lecture impossible (%s)" % exc, file=sys.stderr)
        return 3
    v = verdict(texte, diff, args.racine)
    seule = None
    if args.verifier and v["genre"] == "hors-perimetre":
        seule, detail = verifier(v["classes"], args.racine)
        v["seule"] = seule
        if detail and seule is None:
            print("triage : %s" % detail, file=sys.stderr)
        if seule:
            v["genre"] = "pollution"
    print("triage : %s" % v["genre"])
    print(resume(v, seule))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(v, f, ensure_ascii=False, indent=1)
    return {"perimetre": 1, "compilation": 2, "pollution": 0,
            "hors-perimetre": 0}.get(v["genre"], 3)


if __name__ == "__main__":
    sys.exit(main())
