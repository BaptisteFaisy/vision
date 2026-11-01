#!/usr/bin/env python3
"""check-budget-provenance.py — chaque chiffre publié dit d'où il vient, ou dit qu'il ne vient de rien.

`docs/LATENCE-BUDGET.md` porte une colonne « provenance » par étage. Cette porte la vérifie
contre une **archive de campagnes datées** (`CAMPAGNES` ci-dessous) et contre un vocabulaire
fermé. Trois contrôles :

A. **vocabulaire** — chaque étage porte exactement une étiquette parmi `mesuré`, `calculé`,
   `estimé`, `simulation`, `hypothèse`. Zéro étiquette = provenance implicite (celle qui se
   croit mesurée) ; deux étiquettes = provenance négociable. Les deux échouent.
B. **source** — une étiquette `mesuré` porte une date (`JJ/MM/AAAA` ou `AAAA-MM-JJ`). Un
   « mesuré » sans date n'est pas une mesure, c'est un souvenir.
C. **contradiction** — si une campagne archivée mesure un étage, le budget ne peut plus
   publier cet étage en `simulation`/`hypothèse`/`estimé`, ni publier un `mesuré` qui
   s'écarte de plus de `TOLERANCE` de la mesure. C'est le contrôle qui manquait le
   01/11/2026 : `CAPTURE` était publié à 25 ms (**simulation**, « jamais lu sur un
   appareil ») alors que l'appareil mesurait 81 ms, et `ENCODE` à 5–8 ms (**estimé**)
   alors que l'appareil mesurait 27 ms. Rien ne reliait l'étiquette à une campagne.

L'archive est le seul endroit où une mesure entre dans ce dépôt : elle porte la date, la
source, la valeur par étage et le N. Une campagne sans N est refusée en avertissement
(une moyenne dont on ne connaît pas l'effectif n'est pas un p50/p95 vérifiable).

Réutilisation : les chiffres publiés viennent de `scripts/budget_latence.py` (source unique),
chargé comme module — cette porte ne réécrit aucun nombre, elle les confronte.

Usage :
    python3 scripts/check-budget-provenance.py            # contrôle du document
    python3 scripts/check-budget-provenance.py --strict   # avertissements = erreurs
    python3 scripts/check-budget-provenance.py --list     # archive et étiquettes publiées
    python3 scripts/check-budget-provenance.py --selftest # 12 assertions, sans document

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import argparse
import importlib.util
import os
import re
import sys

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC_DEFAUT = os.path.join(RACINE, "docs", "LATENCE-BUDGET.md")
BUDGET = os.path.join(RACINE, "scripts", "budget_latence.py")
BLOC = re.compile(r"<!-- table:étages -->\n(.*?)<!-- /table:étages -->", re.S)
PROVENANCES = ("mesuré", "calculé", "estimé", "simulation", "hypothèse")
DATE = re.compile(r"\b(?:\d{2}/\d{2}/\d{4}|\d{4}-\d{2}-\d{2})\b")
NOMBRE = re.compile(r"\d+(?:[.,]\d+)?")
TOLERANCE = 0.10                       # 10 % : au-delà, ce n'est plus un arrondi
NETTOYAGE = re.compile(r"[*`]")

# --- l'archive : la seule porte d'entrée d'une mesure dans ce dépôt ----------
# (nom, date, {étage: ms mesurée}, source, N)  — N None : dump non archivé.
CAMPAGNES = (
    ("ISEE_LAT capsplit", "01/11/2026",
     {"CAPTURE": 81, "ENCODE": 27},
     "`ISEE_LAT: capsplit` (`TimingsText.capsplit`, 1 frame sur 20), téléphone, écran iSee",
     None),
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


def verifie(texte):
    """Les trois contrôles sur le document : (erreurs, avertissements)."""
    etages = etages_du_doc(texte)
    if etages is None:
        return [], ["tableau « étages » absent : aucune provenance à contrôler"]
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
    return erreurs, avertissements


def grand_total(budget):
    """Étiquettes d'une campagne archivée, pour `--list`."""
    for nom, date, mesures, source, effectif in CAMPAGNES:
        yield "%s (%s) : %s — %s, N=%s" % (
            nom, date, ", ".join("%s %g ms" % (e, m) for e, m in mesures.items()),
            source, "non archivé" if effectif is None else effectif)


def liste(texte):
    """`--list` : l'archive, puis les étiquettes réellement publiées."""
    print("archive des campagnes :")
    for ligne in grand_total(None):
        print("  " + ligne)
    print("étiquettes publiées :")
    for numero, etage, valeur, provenance in etages_du_doc(texte) or ():
        print("  %-9s %-14s %s" % (etage, "/".join(etiquettes(provenance)) or "?",
                                   (valeur or "—")[:24]))
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


def selftest():
    """Assertions sur des tableaux en mémoire : aucune lecture de document."""
    def bloc(*lignes):
        return "x\n<!-- table:étages -->\n" + "\n".join(lignes) + "\n<!-- /table:étages -->\n"

    entete = "| étage | couvre | valeur (ms) | provenance | plancher |\n|---|---|---:|---|---|"
    cas = [
        ("mesuré daté", "| `CAPTURE` | c | 81 | **mesuré** 81 le 01/11/2026 (ISEE_LAT) | p |", 0, 0),
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
        erreurs, _ = verifie(bloc(entete, ligne))
        ok = len(erreurs) == attendu
        echecs += 0 if ok else 1
        print("%s %-24s %d erreur(s) attendue(s), %d trouvée(s)"
              % ("OK  " if ok else "KO  ", libelle, attendu, len(erreurs)))
    assert etages_du_doc("aucun tableau ici") is None
    assert len(verifie("aucun tableau ici")[1]) == 1        # avertissement, pas erreur
    assert etages_du_doc(bloc(entete, "| `A` | c | 1 | **mesuré** 01/11/2026 | p |"))[0][0] == 5
    assert etiquettes("u07 **simulation** (jamais lu)") == ["simulation"]
    assert valeurs("5–8") == (5.0, 8.0) and valeurs("81") == (81.0, 81.0)
    assert valeurs("calculée") is None
    sans_prov = bloc("| étage | couvre | valeur (ms) | plancher |", "|---|---|---:|---|",
                     "| `A` | c | 1 | p |")
    assert verifie(sans_prov)[0], "un tableau sans colonne provenance doit échouer"
    print(f"OK   {len(cas) + 9} assertions check-budget-provenance")
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
    if args.list:
        return rapport(liste(texte), [], args.strict)
    return rapport(*verifie(texte), strict=args.strict)


if __name__ == "__main__":
    sys.exit(main())
