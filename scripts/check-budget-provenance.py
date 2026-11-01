#!/usr/bin/env python3
"""check-budget-provenance.py — chaque chiffre publié dit d'où il vient, ou dit qu'il ne vient de rien.

`docs/LATENCE-BUDGET.md` porte une colonne « provenance » par étage. Cette porte la vérifie
contre une **archive de campagnes datées** (`CAMPAGNES` ci-dessous) et contre un vocabulaire
fermé. Cinq contrôles :

A. **vocabulaire** — chaque étage porte exactement une étiquette parmi `mesuré`, `calculé`,
   `estimé`, `simulation`, `hypothèse`. Zéro étiquette = provenance implicite (celle qui se
   croit mesurée) ; deux étiquettes = provenance négociable. Les deux échouent.
B. **source** — une étiquette `mesuré` porte une date (`JJ/MM/AAAA` ou `AAAA-MM-JJ`). Un
   « mesuré » sans date n'est pas une mesure, c'est un souvenir.
C. **contradiction** — si une campagne archivée mesure un étage, le budget ne peut plus
   publier cet étage en `simulation`/`hypothèse`/`estimé`, ni publier un `mesuré` qui
   s'écarte de plus de `TOLERANCE` de la mesure. C'est le contrôle qui manquait le
   01/11/2026 : `CAPTURE` était publié à 25 ms (**simulation**, « jamais lu sur un
   appareil ») alors que l'appareil mesurait 86 ms, et `ENCODE` à 5–8 ms (**estimé**)
   alors que l'appareil mesurait 27 ms. Rien ne reliait l'étiquette à une campagne.
D. **campagnes** — toute ligne du tableau `mesures` (§ 10) porte un N (entier > 0) ;
   toute NOUVELLE ligne (absente de la base git) porte des conditions datées. Les lignes
   anciennes sans date sont averties, pas refusées (clause grand-père) ; sans base git
   (checkout superficiel de la CI), la nouveauté est invérifiable et la date n'est
   qu'avertie — le N, lui, est exigible sans base et reste une erreur partout.
E. **branches** — avertissement si la PR touche le périmètre latence
   (`PERIMETRE_LATENCE` : budget, cette porte, `parse_isee_lat.py`) alors que des
   branches `lat-*` résiduelles (non fusionnées dans la base) existent : risque de
   conflit, rebase avant merge. Best-effort git local, silencieux sans git ; jamais
   une erreur.

L'archive est le seul endroit où une mesure entre dans ce dépôt : elle porte la date, la
source, la valeur par étage et le N. Une campagne sans N est refusée en avertissement
(une moyenne dont on ne connaît pas l'effectif n'est pas un p50/p95 vérifiable).

Réutilisation : les chiffres publiés viennent de `scripts/budget_latence.py` (source unique),
chargé comme module — cette porte ne réécrit aucun nombre, elle les confronte ; les lectures
git viennent de `scripts/git_lecture.py` (best-effort, partageable entre portes).

Usage :
    python3 scripts/check-budget-provenance.py            # contrôle du document
    python3 scripts/check-budget-provenance.py --strict   # avertissements = erreurs
    python3 scripts/check-budget-provenance.py --list     # archive et étiquettes publiées
    python3 scripts/check-budget-provenance.py --selftest # assertions, sans document

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
Git n'est lu qu'en best-effort (base, branches) : sans lui, D dégrade en
avertissement et E se tait.
"""

import argparse
import importlib.util
import os
import re
import sys

import git_lecture

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC_DEFAUT = os.path.join(RACINE, "docs", "LATENCE-BUDGET.md")
BUDGET = os.path.join(RACINE, "scripts", "budget_latence.py")
BLOC = re.compile(r"<!-- table:étages -->\n(.*?)<!-- /table:étages -->", re.S)
BLOC_MESURES = re.compile(r"<!-- table:mesures -->\n(.*?)<!-- /table:mesures -->", re.S)
PERIMETRE_LATENCE = ("docs/LATENCE-BUDGET.md", "scripts/budget_latence.py",
                     "scripts/check-budget-provenance.py", "scripts/parse_isee_lat.py")
PROVENANCES = ("mesuré", "calculé", "estimé", "simulation", "hypothèse")
DATE = re.compile(r"\b(?:\d{2}/\d{2}/\d{4}|\d{4}-\d{2}-\d{2})\b")
NOMBRE = re.compile(r"\d+(?:[.,]\d+)?")
TOLERANCE = 0.10                       # 10 % : au-delà, ce n'est plus un arrondi
NETTOYAGE = re.compile(r"[*`]")

# --- l'archive : la seule porte d'entrée d'une mesure dans ce dépôt ----------
# (nom, date, {étage: ms mesurée}, source, N)  — N None : dump non archivé.
CAMPAGNES = (
    ("ISEE_LAT capsplit", "01/11/2026",
     {"CAPTURE": 86, "ENCODE": 27},
     "`ISEE_LAT: capsplit` (`TimingsText.capsplit`, 1 frame sur 20), téléphone SM-G780G, build "
     "`1.0.1139-da6531a`, écran iSee",
     38),
)


def charge_budget(chemin=BUDGET):
    """Charge `scripts/budget_latence.py` comme module (chiffres publiés, source unique)."""
    spec = importlib.util.spec_from_file_location("budget_latence", chemin)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def lis_texte(chemin):
    """Contenu d'un fichier, ou None s'il est absent."""
    try:
        with open(chemin, encoding="utf-8", errors="replace") as fichier:
            return fichier.read()
    except OSError:
        return None


def cellules(ligne):
    """« | a | b | c | » -> ["a", "b", "c"] (les bords vides sont retirés)."""
    morceaux = ligne.strip().split("|")
    return [m.strip() for m in morceaux[1:-1]] if len(morceaux) >= 3 else []


def etages_du_doc(texte):
    """[(numéro, étage, valeur, provenance)] du bloc `table:étages`, ou None si absent."""
    bloc = BLOC.search(texte)
    if not bloc:
        return None
    depart = texte[:bloc.start()].count("\n") + 2
    entete, i_valeur, i_prov, sortie = None, None, None, []
    for rang, brute in enumerate(bloc.group(1).splitlines()):
        numero = depart + rang
        if not brute.strip().startswith("|"):
            continue
        cols = cellules(brute)
        if entete is None:
            entete = [NETTOYAGE.sub("", c).lower() for c in cols]
            i_valeur = next((i for i, c in enumerate(entete) if c.startswith("valeur")), None)
            i_prov = next((i for i, c in enumerate(entete) if "provenance" in c), None)
            continue
        if set(NETTOYAGE.sub("", brute).strip()) <= set("-:|"):
            continue                                   # ligne séparatrice
        if not cols or (i_prov is not None and i_prov >= len(cols)):
            continue
        etage = NETTOYAGE.sub("", cols[0]).strip()
        valeur = cols[i_valeur] if i_valeur is not None and i_valeur < len(cols) else ""
        sortie.append((numero, etage, valeur, cols[i_prov] if i_prov is not None else None))
    if entete is None or i_prov is None:
        return [("?", "—", "—", None)]                 # tableau sans colonne provenance
    return sortie


def etiquettes(cellule):
    """Étiquettes du vocabulaire présentes dans une cellule (mots entiers, `**` retirés)."""
    net = NETTOYAGE.sub("", cellule or "")
    return [mot for mot in PROVENANCES
            if re.search(r"(?<![\wé])" + re.escape(mot) + r"(?![\wé])", net)]


def valeurs(cellule):
    """Les nombres publiés dans une cellule de valeur : (min, max), ou None."""
    trouves = [float(n.replace(",", ".")) for n in NOMBRE.findall(cellule or "")]
    return (min(trouves), max(trouves)) if trouves else None


def n_valide(cellule):
    """Un N est un entier strictement positif (« 1 257 » accepté)."""
    chiffres = "".join(c for c in (cellule or "") if c.isdecimal())
    return chiffres != "" and int(chiffres) > 0


def normalise_campagne(nom):
    """Nom de campagne comparé sans casse ni décoration."""
    return NETTOYAGE.sub("", nom or "").strip().lower()


def campagnes_du_doc(texte):
    """[(numéro, campagne, N, conditions)] du bloc `table:mesures`, ou None si absent."""
    if not texte:
        return None
    bloc = BLOC_MESURES.search(texte)
    if not bloc:
        return None
    depart = texte[:bloc.start()].count("\n") + 2
    entete, i_n, i_cond, sortie = None, None, None, []
    for rang, brute in enumerate(bloc.group(1).splitlines()):
        if not brute.strip().startswith("|"):
            continue
        cols = cellules(brute)
        if entete is None:
            entete = [NETTOYAGE.sub("", c).lower().strip() for c in cols]
            i_n = next((i for i, c in enumerate(entete) if c == "n"), None)
            i_cond = next((i for i, c in enumerate(entete) if c.startswith("conditions")), None)
            continue
        if set(NETTOYAGE.sub("", brute).strip()) <= set("-:|"):
            continue                                   # ligne séparatrice
        if not cols:
            continue
        nom = NETTOYAGE.sub("", cols[0]).strip()
        n_cell = cols[i_n] if i_n is not None and i_n < len(cols) else ""
        cond = cols[i_cond] if i_cond is not None and i_cond < len(cols) else ""
        sortie.append((depart + rang, nom, n_cell, cond))
    if entete is None or i_n is None or i_cond is None:
        return [("?", "—", "", "")]                   # tableau sans N ni conditions datables
    return sortie


def controle_campagnes(actuelles, noms_ref):
    """Contrôle D : N partout ; conditions datées pour toute NOUVELLE campagne."""
    if actuelles is None:
        return [], []
    erreurs, avertissements = [], []
    for numero, nom, cellule_n, conditions in actuelles:
        if not n_valide(cellule_n):
            erreurs.append("ligne %s : campagne « %s » sans N (« %s ») : une moyenne sans "
                           "effectif n'est pas un p50/p95 vérifiable"
                           % (numero, nom, (cellule_n or "—").strip() or "—"))
        if not DATE.search(conditions or ""):
            message = ("ligne %s : campagne « %s » — conditions sans date (« %s ») : une "
                       "campagne date ses conditions (`JJ/MM/AAAA`)"
                       % (numero, nom, (conditions or "—")[:40]))
            if noms_ref is not None and normalise_campagne(nom) not in noms_ref:
                erreurs.append(message)
            else:
                avertissements.append(message + (" (antérieure à la porte : à dater)"
                                                 if noms_ref is not None
                                                 else " (base git absente : nouveauté non vérifiée)"))
    return erreurs, avertissements


def controle_etiquette(numero, etage, provenance):
    """Contrôle A : une étiquette, et une seule, parmi le vocabulaire fermé."""
    trouvees = etiquettes(provenance)
    if provenance is None:
        return ["étage « %s » — le tableau `étages` n'a pas de colonne « provenance » : "
                "aucun chiffre ne dit d'où il vient" % etage]
    if not trouvees:
        return ["ligne %d : étage « %s » — provenance « %s » hors vocabulaire (%s)"
                % (numero, etage, provenance[:40], "|".join(PROVENANCES))]
    if len(trouvees) > 1:
        return ["ligne %d : étage « %s » — deux étiquettes (%s) : une provenance n'est pas "
                "négociable" % (numero, etage, ", ".join(trouvees))]
    return []


def controle_source(numero, etage, provenance, trouvees):
    """Contrôle B : « mesuré » sans date n'est pas une mesure."""
    if trouvees == ["mesuré"] and not DATE.search(provenance or "") \
            and "date non consignée" not in (provenance or ""):
        return ["ligne %d : étage « %s » — « mesuré » sans date : ajoute le jour de la "
                "campagne (`JJ/MM/AAAA`) ou écris « date non consignée »" % (numero, etage)]
    return []


def controle_contradiction(numero, etage, valeur, trouvees):
    """Contrôle C : une campagne archivée interdit de publier une hypothèse sur le même étage."""
    erreurs = []
    for nom, date, mesures, _, _ in CAMPAGNES:
        mesure = mesures.get(etage)
        if mesure is None:
            continue
        publiees = valeurs(valeur)
        if publiees is None:
            continue
        bas, haut = publiees
        if trouvees and trouvees[0] not in ("mesuré", "calculé"):
            erreurs.append("ligne %d : étage « %s » publié en « %s » alors que « %s » du %s "
                           "le mesure à %g ms — une mesure chasse l'hypothèse"
                           % (numero, etage, trouvees[0], nom, date, mesure))
        elif bas > mesure * (1 + TOLERANCE) or haut < mesure * (1 - TOLERANCE):
            erreurs.append("ligne %d : étage « %s » publié %g–%g ms contre %g ms mesurés par "
                           "« %s » du %s (écart > %d %%)"
                           % (numero, etage, bas, haut, mesure, nom, date, TOLERANCE * 100))
    return erreurs


def controle_branches(residuelles, fichiers_pr, par_branche):
    """Contrôle E : warning si la PR touche le périmètre latence encore couvert."""
    if not residuelles or not fichiers_pr:
        return []
    if not (set(PERIMETRE_LATENCE) & set(fichiers_pr)):
        return []
    avertissements = []
    for branche in residuelles:
        fichiers = (par_branche or {}).get(branche)
        if fichiers is None:
            continue
        communs = sorted(set(fichiers) & set(fichiers_pr))
        if communs:
            avertissements.append("PR touche %s, aussi modifié(s) par la branche latence "
                                  "résiduelle « %s » : rebase avant merge, sinon conflit"
                                  % (", ".join("« %s »" % f for f in communs), branche))
    if not avertissements:
        avertissements.append("PR touche le périmètre latence alors que %d branche(s) "
                              "résiduelle(s) existe(nt) (%s) : rebase avant merge"
                              % (len(residuelles), ", ".join(residuelles)))
    return avertissements


def verifie(texte, noms_ref=None, residuelles=(), fichiers_pr=frozenset(),
            par_branche=None):
    """Les cinq contrôles sur le document : (erreurs, avertissements)."""
    etages = etages_du_doc(texte)
    if etages is None:
        erreurs = []
        avertissements = ["tableau « étages » absent : aucune provenance à contrôler"]
    else:
        erreurs, avertissements = [], []
        for numero, etage, valeur, provenance in etages:
            trouvees = etiquettes(provenance)
            erreurs += controle_etiquette(numero, etage, provenance)
            erreurs += controle_source(numero, etage, provenance, trouvees)
            erreurs += controle_contradiction(numero, etage, valeur, trouvees)
    for nom, date, _, source, effectif in CAMPAGNES:
        if effectif is None:
            avertissements.append("campagne « %s » du %s sans N : la moyenne n'est pas un "
                                  "p50/p95 vérifiable (%s)" % (nom, date, source))
    actuelles = campagnes_du_doc(texte)
    err_d, avt_d = controle_campagnes(actuelles, noms_ref)
    return erreurs + err_d, avertissements + avt_d + controle_branches(
        residuelles, fichiers_pr, par_branche)


def grand_total(budget):
    """Étiquettes d'une campagne archivée, pour `--list`."""
    for nom, date, mesures, source, effectif in CAMPAGNES:
        yield "%s (%s) : %s — %s, N=%s" % (
            nom, date, ", ".join("%s %g ms" % (e, m) for e, m in mesures.items()),
            source, "non archivé" if effectif is None else effectif)


def liste(texte, residuelles=()):
    """`--list` : l'archive, les étiquettes publiées, les branches résiduelles."""
    print("archive des campagnes :")
    for ligne in grand_total(None):
        print("  " + ligne)
    print("étiquettes publiées :")
    for numero, etage, valeur, provenance in etages_du_doc(texte) or ():
        print("  %-9s %-14s %s" % (etage, "/".join(etiquettes(provenance)) or "?",
                                   (valeur or "—")[:24]))
    print("branches latence résiduelles :")
    print("  " + (", ".join(residuelles) if residuelles else "aucune"))
    return []


def rapport(erreurs, avertissements, strict):
    """Annotations GitHub puis code de sortie (1 si une erreur survit)."""
    if strict:
        erreurs, avertissements = erreurs + avertissements, []
    for message in avertissements:
        print("::warning::%s" % message)
    for message in erreurs:
        print("::error::%s" % message)
    print("check-budget-provenance : %d campagne(s) archivée(s), %d erreur(s), "
          "%d avertissement(s)" % (len(CAMPAGNES), len(erreurs), len(avertissements)))
    if erreurs:
        print("Règle : un étage dit d'où vient son chiffre, ou dit qu'il ne vient de rien.")
        return 1
    print("OK — chaque étage porte une provenance, et aucune hypothèse ne survit à une mesure.")
    return 0


def bloc_test(table, entete, lignes):
    """Un bloc `table:…` en mémoire pour le selftest."""
    return ("x\n<!-- table:%s -->\n%s\n%s\n<!-- /table:%s -->\n"
            % (table, entete, "\n".join(lignes), table))


def selftest_campagnes():
    """Contrôle D en mémoire : N exigé partout, date exigée des seules nouvelles."""
    entete = "| campagne | N | moyenne (ms) | conditions |\n|---|---:|---:|---|"
    cas = [
        ("N + date", ["| c1 | 38 | 100 | écran iSee, le 01/11/2026 |"], set(), 0, 0),
        ("sans N", ["| c2 | — | 100 | banc, le 01/11/2026 |"], set(), 1, 0),
        ("N nul", ["| c3 | 0 | 100 | banc, le 01/11/2026 |"], set(), 1, 0),
        ("nouvelle sans date", ["| c4 | 38 | 100 | scène vide |"], set(), 1, 0),
        ("ancienne sans date", ["| c4 | 38 | 100 | scène vide |"], {"c4"}, 0, 1),
        ("base inconnue", ["| c4 | 38 | 100 | scène vide |"], None, 0, 1),
        ("N espacé + ISO", ["| c5 | 1 257 | 100 | banc 2026-11-01 |"], set(), 0, 0),
    ]
    echecs = 0
    for libelle, lignes, noms_ref, att_err, att_avt in cas:
        erreurs, avertis = controle_campagnes(
            campagnes_du_doc(bloc_test("mesures", entete, lignes)), noms_ref)
        ok = len(erreurs) == att_err and len(avertis) == att_avt
        echecs += 0 if ok else 1
        print("%s %-18s %d erreur(s)/%d avert. attendu(s), %d/%d trouvé(s)"
              % ("OK  " if ok else "KO  ", libelle, att_err, att_avt,
                 len(erreurs), len(avertis)))
    assert campagnes_du_doc("aucun tableau ici") is None
    assert controle_campagnes(None, set()) == ([], [])
    assert n_valide("38 lignes") and not n_valide("—") and not n_valide("")
    print("OK   %d assertions campagnes" % (len(cas) + 3))
    return echecs


def selftest_branches():
    """Contrôle E en mémoire : warning si la PR touche le périmètre encore couvert."""
    perimetre = {"docs/LATENCE-BUDGET.md"}
    cas = [
        ("chevauchement", ["lat-parse"], perimetre,
         {"lat-parse": {"docs/LATENCE-BUDGET.md"}}, 1),
        ("sans chevauchement", ["lat-parse"], perimetre,
         {"lat-parse": {"app/x.kt"}}, 1),
        ("hors périmètre", ["lat-parse"], {"app/x.kt"},
         {"lat-parse": {"app/x.kt"}}, 0),
        ("sans résiduelle", [], perimetre, {}, 0),
        ("PR vide", ["lat-parse"], set(),
         {"lat-parse": {"docs/LATENCE-BUDGET.md"}}, 0),
        ("fichiers inconnus", ["lat-parse"], {"scripts/budget_latence.py"}, {}, 1),
    ]
    echecs = 0
    for libelle, residuelles, fichiers, par_branche, attendu in cas:
        trouves = controle_branches(residuelles, fichiers, par_branche)
        ok = len(trouves) == attendu
        echecs += 0 if ok else 1
        print("%s %-18s %d avertissement(s) attendu(s), %d trouvé(s)"
              % ("OK  " if ok else "KO  ", libelle, attendu, len(trouves)))
    print("OK   %d assertions branches" % len(cas))
    return echecs


def selftest():
    """Assertions sur des tableaux en mémoire : aucune lecture de document."""
    entete = "| étage | couvre | valeur (ms) | provenance | plancher |\n|---|---|---:|---|---|"
    cas = [
        ("mesuré daté", "| `CAPTURE` | c | 86 | **mesuré** 86 le 01/11/2026 (ISEE_LAT) | p |", 0, 0),
        ("mesuré sans date", "| `X` | c | 81 | **mesuré** sur un banc | p |", 1, 0),
        ("mesuré, date non consignée",
         "| `X2` | c | 81 | **mesuré** (banc, date non consignée) | p |", 0, 0),
        ("simulation", "| `Y` | c | 25 | u07 **simulation** (jamais lu) | p |", 0, 0),
        ("hors vocabulaire", "| `Z` | c | 25 | bricolage maison | p |", 1, 0),
        ("deux étiquettes", "| `W` | c | 25 | **mesuré** 01/11/2026 puis **estimé** | p |", 1, 0),
        ("contradiction CAPTURE", "| `CAPTURE` | c | 25 | u07 **simulation** | p |", 1, 0),
        ("écart de 200 %", "| `CAPTURE` | c | 189 | **mesuré** 01/11/2026 (x) | p |", 1, 0),
        ("ENCODE mesuré exact", "| `ENCODE` | c | 27 | **mesuré** 01/11/2026 (x) | p |", 0, 0),
        ("ENCODE à 9 %", "| `ENCODE` | c | 29,5 | **mesuré** 01/11/2026 (x) | p |", 0, 0),
        ("plage 5–8 contre 27", "| `ENCODE` | c | 5–8 | u02 **estimé** | p |", 1, 0),
    ]
    echecs = 0
    for libelle, ligne, attendu, _ in cas:
        erreurs, _ = verifie(bloc_test("étages", entete, [ligne]))
        ok = len(erreurs) == attendu
        echecs += 0 if ok else 1
        print("%s %-24s %d erreur(s) attendue(s), %d trouvée(s)"
              % ("OK  " if ok else "KO  ", libelle, attendu, len(erreurs)))
    assert etages_du_doc("aucun tableau ici") is None
    assert len(verifie("aucun tableau ici")[1]) == 1        # avertissement, pas erreur
    assert etages_du_doc(bloc_test(
        "étages", entete, ["| `A` | c | 1 | **mesuré** 01/11/2026 | p |"]))[0][0] == 5
    assert etiquettes("u07 **simulation** (jamais lu)") == ["simulation"]
    assert valeurs("5–8") == (5.0, 8.0) and valeurs("81") == (81.0, 81.0)
    assert valeurs("calculée") is None
    sans_prov = bloc_test("étages", "| étage | couvre | valeur (ms) | plancher |",
                          ["|---|---|---:|---|", "| `A` | c | 1 | p |"])
    assert verifie(sans_prov)[0], "un tableau sans colonne provenance doit échouer"
    echecs += selftest_campagnes()
    echecs += selftest_branches()
    print(f"OK   {len(cas) + 9 + 10 + 6} assertions check-budget-provenance")
    return 1 if echecs else 0


def parse_args(argv):
    """Options de la porte."""
    analyseur = argparse.ArgumentParser(
        description="Provenance du budget de latence (étiquette <-> campagne archivée).")
    analyseur.add_argument("--root", default=".", help="racine du dépôt (défaut : .)")
    analyseur.add_argument("--doc", default=DOC_DEFAUT, help="budget publié à contrôler")
    analyseur.add_argument("--strict", action="store_true", help="avertissements = erreurs")
    analyseur.add_argument("--list", action="store_true", help="archive et étiquettes publiées")
    analyseur.add_argument("--selftest", action="store_true", help="assertions, sans document")
    return analyseur.parse_args(argv)


def main(argv=None):
    """Contrôle le document, rend le code de sortie."""
    args = parse_args(argv)
    if args.selftest:
        return selftest()
    if not os.path.isdir(args.root):
        print("racine introuvable : %s" % args.root, file=sys.stderr)
        return 2
    chemin = args.doc if os.path.isabs(args.doc) else os.path.join(args.root, args.doc)
    texte = lis_texte(chemin)
    if texte is None:
        print("::warning::%s absent : aucune provenance à contrôler." % args.doc)
        return 0
    base = git_lecture.base(args.root)
    reference = git_lecture.fichier_sur_base(args.root, chemin, base) if base else None
    noms_ref = None
    if reference is not None:
        lignes_ref = campagnes_du_doc(reference)
        noms_ref = set() if lignes_ref is None else {normalise_campagne(n)
                                                     for _, n, _, _ in lignes_ref}
    residuelles = git_lecture.branches_non_fusionnees(
        args.root, base, ("lat-*",), ("origin/lat-*",),
        git_lecture.branche_courante(args.root))
    if args.list:
        return rapport(liste(texte, residuelles), [], args.strict)
    fichiers = git_lecture.fichiers_diff(args.root, base, "HEAD") or set()
    par_branche = {b: git_lecture.fichiers_diff(args.root, base, b) for b in residuelles}
    return rapport(*verifie(texte, noms_ref, residuelles, fichiers, par_branche),
                   strict=args.strict)


if __name__ == "__main__":
    sys.exit(main())
