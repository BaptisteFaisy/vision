#!/usr/bin/env python3
"""parse_isee_lat.py — verdict 500 ms max / 300 ms en moyenne sur une campagne ISEE_LAT.

Entrée : un dump `adb logcat -s ISEE_LAT` (fichier ou stdin), une ligne par frame
complète, écrite par `LiveLatencyLog` (`app/live/`) — la même chaîne que l'écran :

    latency 348 · capt 22 · up 61 · srv 92 · rtt 153 · mask 37 · pixel 8

Un poste non mesuré vaut `—` (trait cadratin) : la frame reste comptée, le poste
est ignoré pour ce poste-là. Les K premières frames partent au warmup
(`--skip`, défaut 5 : réveil GPU, caches froids).

Sortie : n, moyenne, p50, p95, p99, max par poste, puis la gigue
(écart-type de `latency`), les frames jetées (lignes `latency —`,
mesures perdues, hors warmup) et le verdict (`moyenne <= 300` et
`max <= 500` par défaut, modifiables par `--mean`/`--max` ; `--p95`,
`--p99`, `--jitter`, `--drop` ajoutent des seuils optionnels, inactifs
quand ils sont absents). Code 0 si tenu, 1 sinon.

Mode `--servers` : même entrée, mais agrège les lignes `server:` (détail des
étages, `LiveLatencyLog.serverLine` sous le même tag) au lieu du verdict —
n, moyenne, p50, p95, p99, max par étage (`preprocess`, `vision`, `detr`,
`depth`, `masks`, `total`, puis les inconnus triés). Les frames sans ligne
`server:` (FAST_TIMING éteint) ne comptent dans aucun étage. Code 0 si au
moins une ligne `server:` est trouvée, 2 sinon. Le défaut reste le verdict.

Mode `--capsplit` : même dump, mais agrège les lignes `capsplit` (décomposition du
poste `capt`, `TimingsText.capsplit` sous le même tag, loggée 1 frame sur 20) :
`age` = capteur → entrée analyseur (expo, readout, livraison, attente fil),
`enc` = NV21 → JPEG, `b64` = base64 + horodatage, `cb` = rappel `onFrame`
(profondeur, mouvement, réserve/envoi), `total` = analyse complète (durée du
callback), `int` = intervalle depuis l'entrée précédente de l'analyseur, donc la
**cadence réellement obtenue**. `age + enc + b64 + cb` retrouve le `capt` de la
sonde à ~1 ms près — c'est cette somme, et non `age` seul, qui explique les
dizaines de ms hors encode et hors base64. `int` ne s'additionne à rien : il sert
à trancher, pas à totaliser. À 30 fps l'exposition ne peut pas dépasser 33 ms ;
si `int` vaut 33 et `age` 81, alors l'écart entre les deux n'est ni l'exposition
ni la cadence, et il faut chercher ailleurs (`int` ≫ 33 = cadence perdue ;
`int` ≈ 33 = pipeline à l'heure, donc le délai est en amont de l'analyseur). Le tableau finit par la comparaison avec le `capt` mesuré
du dump (`capt non mesuré dans ce dump` si la sonde n'a rien écrit). Code 0 si
au moins une ligne `capsplit` est trouvée, 2 sinon. `--servers` et `--capsplit`
sont exclusifs (usage, code 2).

Campagne de référence (écran iSee, A100 eu-west, GPU chaud, Wi-Fi figé,
N >= 100 frames chaudes) : voir l'en-tête — `~/bin/phone run ... -- adb -s
<tel> logcat`, jamais d'`adb` nu.

Usage :
    python3 scripts/parse_isee_lat.py lat.txt
    python3 scripts/parse_isee_lat.py lat.txt --mean 250 --p95 450 --drop 3
    adb -s <tel> logcat -d -s ISEE_LAT | python3 scripts/parse_isee_lat.py
    python3 scripts/parse_isee_lat.py --servers lat.txt
    python3 scripts/parse_isee_lat.py --capsplit lat.txt
    adb -s <tel> logcat -d -s ISEE_LAT | python3 scripts/parse_isee_lat.py --capsplit
    python3 scripts/parse_isee_lat.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import re
import statistics
import sys

POSTES = ("latency", "capt", "up", "srv", "rtt", "mask", "pixel")
LIGNE = re.compile(r"ISEE_LAT:\s*(latency\s+\S.*)")
POSTE = re.compile(r"(latency|capt|up|srv|rtt|mask|pixel)\s+(\d+|—)")
ETAGES = ("preprocess", "vision", "detr", "depth", "masks", "total")
LIGNE_SERVEUR = re.compile(r"ISEE_LAT:\s*server:\s*(.*)")
ETAGE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s+(\d+|—)\s*ms")
# `total` et `int` ferment la ligne mais ne s'additionnent pas au `capt` :
# seuls les quatre premiers postes le composent (`POSTES_CAPSPLIT[:4]`).
POSTES_CAPSPLIT = ("age", "enc", "b64", "cb", "total", "int")
LIGNE_CAPSPLIT = re.compile(r"ISEE_LAT:\s*capsplit\s+(.*)")
# Postes capsplit sans suffixe `ms` ; `POSTES_CAPSPLIT[:4]` se somme en `capt`.
POSTE_CAPSPLIT = re.compile(r"([a-z0-9]+)\s+(\d+|—)")
CIBLE_MOYENNE = 300
CIBLE_MAX = 500
WARMUP_DEFAUT = 5


def parse_ligne(brute):
    """Une ligne logcat -> dict poste->ms (postes `—` absents) ou None."""
    trouve = LIGNE.search(brute)
    if not trouve:
        return None
    mesures = {}
    for nom, valeur in POSTE.findall(trouve.group(1)):
        if valeur != "—":
            mesures[nom] = int(valeur)
    return mesures if "latency" in mesures else None


def parse_texte(texte, skip=WARMUP_DEFAUT):
    """Toutes les frames complètes, moins les `skip` premières (warmup)."""
    frames = [m for ligne in texte.splitlines() if (m := parse_ligne(ligne))]
    return frames[skip:] if skip > 0 else frames


def centile(tri, pourcent):
    """Centile rang-proche : trié croissant, rang `ceil(q*n)` (1-based)."""
    rang = (pourcent * len(tri) + 99) // 100
    return tri[min(max(rang, 1), len(tri)) - 1]


def resume(valeurs):
    """(n, moyenne, p50, p95, p99, max) d'une série non vide."""
    tri = sorted(valeurs)
    return (len(tri), sum(tri) / len(tri), centile(tri, 50),
            centile(tri, 95), centile(tri, 99), tri[-1])


def gigue(valeurs):
    """Gigue d'une série non vide : écart-type population (0,0 si n = 1)."""
    return statistics.pstdev(valeurs) if len(valeurs) > 1 else 0.0


def jetees(texte, skip=WARMUP_DEFAUT):
    """Frames jetées : lignes `latency` sans mesure (`—`), hors warmup.

    Le warmup mange les `skip` premières lignes `ISEE_LAT: latency`
    (complètes ou non), comme `parse_texte` mange les `skip` premières
    frames complètes — GPU froid des deux côtés.
    """
    lignes = [l for l in texte.splitlines() if LIGNE.search(l)]
    utiles = lignes[skip:] if skip > 0 else lignes
    return sum(1 for l in utiles if parse_ligne(l) is None)


def verdict(frames, cible_moyenne=CIBLE_MOYENNE, cible_max=CIBLE_MAX,
            seuil_p95=None, seuil_p99=None, seuil_gigue=None,
            seuil_jetees=None, nb_jetees=0):
    """(lignes, ok) : tableau par poste + verdict sur `latency`.

    Les seuils `None` sont inactifs : sans eux, le verdict reste
    `moyenne <= --mean` et `max <= --max` (défauts 300 / 500).
    `nb_jetees` vient de `jetees()` (le dump brut, pas les frames).
    """
    lignes = ["poste    n  moyenne   p50   p95   p99   max"]
    ok = True
    for poste in POSTES:
        valeurs = [f[poste] for f in frames if poste in f]
        if not valeurs:
            lignes.append(f"{poste:7} 0  —")
            continue
        n, moyenne, p50, p95, p99, maxi = resume(valeurs)
        lignes.append(f"{poste:7} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {p99:5} {maxi:5}")
        if poste == "latency":
            lignes.extend(bloc_verdict(valeurs, moyenne, p95, p99, maxi,
                                       cible_moyenne, cible_max, seuil_p95,
                                       seuil_p99, seuil_gigue, seuil_jetees,
                                       nb_jetees))
            ok = lignes[-1].startswith("verdict : TENU")
    return lignes, ok


def bloc_verdict(valeurs, moyenne, p95, p99, maxi, cible_moyenne, cible_max,
                 seuil_p95, seuil_p99, seuil_gigue, seuil_jetees, nb_jetees):
    """Cible, gigue, jetées et verdict final sur la série `latency`."""
    ecart = gigue(valeurs)
    controles = [(moyenne <= cible_moyenne, f"moyenne {moyenne:.1f} <= {cible_moyenne}"),
                 (maxi <= cible_max, f"max {maxi} <= {cible_max}")]
    optionnels = [(p95, seuil_p95, "p95"), (p99, seuil_p99, "p99"),
                  (ecart, seuil_gigue, "gigue"), (nb_jetees, seuil_jetees, "jetées")]
    for valeur, seuil, nom in optionnels:
        if seuil is not None:
            affiche = f"{valeur:.1f}" if nom == "gigue" else str(valeur)
            controles.append((valeur <= seuil, f"{nom} {affiche} <= {seuil}"))
    tenu = all(passe for passe, _ in controles)
    return [f"cible : moyenne <= {cible_moyenne}, max <= {cible_max}",
            f"gigue(latency) {ecart:.1f} ms · jetées {nb_jetees} "
            f"(lignes `latency —`)",
            f"verdict : {'TENU' if tenu else 'NON TENU'} "
            f"({'; '.join(texte for _, texte in controles)})"]


def parse_ligne_server(brute):
    """Une ligne logcat -> dict étage->ms (étages `—` absents) ou None."""
    trouve = LIGNE_SERVEUR.search(brute)
    if not trouve:
        return None
    mesures = {}
    for nom, valeur in ETAGE.findall(trouve.group(1)):
        if valeur != "—":
            mesures[nom] = int(valeur)
    return mesures or None


def parse_texte_server(texte, skip=WARMUP_DEFAUT):
    """Toutes les lignes `server:`, moins les `skip` premières (warmup)."""
    lignes = [m for ligne in texte.splitlines() if (m := parse_ligne_server(ligne))]
    return lignes[skip:] if skip > 0 else lignes


def tableau_serveurs(lignes):
    """n/moyenne/p50/p95/p99/max par étage (connus, puis inconnus triés)."""
    table = ["étage        n  moyenne   p50   p95   p99   max"]
    autres = sorted({nom for m in lignes for nom in m} - set(ETAGES))
    for nom in ETAGES + tuple(autres):
        valeurs = [m[nom] for m in lignes if nom in m]
        if not valeurs:
            table.append(f"{nom:10}   0  —")
            continue
        n, moyenne, p50, p95, p99, maxi = resume(valeurs)
        table.append(f"{nom:10} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {p99:5} {maxi:5}")
    return table


def main_serveurs(texte, skip):
    """Affiche le tableau serveurs ; 0 si trouvé, 2 sinon (FAST_TIMING ?)."""
    lignes = parse_texte_server(texte, skip)
    if not lignes:
        print("aucune ligne ISEE_LAT server: (FAST_TIMING éteint ? tag filtré ?)",
              file=sys.stderr)
        return 2
    print("\n".join(tableau_serveurs(lignes)))
    return 0


def parse_ligne_capsplit(brute):
    """Une ligne logcat -> dict poste->ms (postes `—` absents) ou None."""
    trouve = LIGNE_CAPSPLIT.search(brute)
    if not trouve:
        return None
    mesures = {}
    for nom, valeur in POSTE_CAPSPLIT.findall(trouve.group(1)):
        if valeur != "—":
            mesures[nom] = int(valeur)
    return mesures or None


def parse_texte_capsplit(texte, skip=WARMUP_DEFAUT):
    """Toutes les lignes `capsplit`, moins les `skip` premières (warmup)."""
    lignes = [m for ligne in texte.splitlines() if (m := parse_ligne_capsplit(ligne))]
    return lignes[skip:] if skip > 0 else lignes


def ligne_capt(frames, moyenne_somme):
    """Ligne comparative : `capt` mesuré vs somme capsplit, écart signé en ms."""
    capts = [f["capt"] for f in frames if "capt" in f]
    if not capts:
        return "capt non mesuré dans ce dump"
    moyenne_capt = resume(capts)[1]
    if moyenne_somme is None:
        return f"capt mesuré {moyenne_capt:.1f} · somme non calculable"
    return (f"capt mesuré {moyenne_capt:.1f} · somme {moyenne_somme:.1f} · "
            f"écart {moyenne_somme - moyenne_capt:+.1f} ms (somme - capt)")


def tableau_capsplit(lignes, frames):
    """n/moyenne/p50/p95/p99/max par poste, la `somme`, puis la comparaison `capt`."""
    table = ["poste        n  moyenne   p50   p95   p99   max"]
    for poste in POSTES_CAPSPLIT:
        valeurs = [m[poste] for m in lignes if poste in m]
        if not valeurs:
            table.append(f"{poste:10}   0  —")
            continue
        n, moyenne, p50, p95, p99, maxi = resume(valeurs)
        table.append(f"{poste:10} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {p99:5} {maxi:5}")
    # Somme frame par frame : seules les frames où les quatre postes existent.
    sommes = [m["age"] + m["enc"] + m["b64"] + m["cb"] for m in lignes
              if all(p in m for p in POSTES_CAPSPLIT[:4])]
    moyenne_somme = None
    if sommes:
        n, moyenne_somme, p50, p95, p99, maxi = resume(sommes)
        table.append(f"{'somme':10} {n:3} {moyenne_somme:7.1f} {p50:5} {p95:5} {p99:5} {maxi:5}")
    else:
        table.append(f"{'somme':10}   0  —")
    table.append(ligne_capt(frames, moyenne_somme))
    return table


def main_capsplit(texte, skip):
    """Affiche le tableau capsplit ; 0 si trouvé, 2 sinon (log 1 frame sur 20 ?)."""
    lignes = parse_texte_capsplit(texte, skip)
    if not lignes:
        print("aucune ligne ISEE_LAT capsplit (log 1 frame sur 20 ? tag filtré ?)",
              file=sys.stderr)
        return 2
    print("\n".join(tableau_capsplit(lignes, parse_texte(texte, skip))))
    return 0


def selftest_frames():
    """Formes, warmup, centiles, verdict : les assertions du mode par défaut."""
    cas = [
        ("I ISEE_LAT: latency 348 · capt 22 · up 61 · srv 92 · rtt 153 · mask 37 · pixel 8",
         {"latency": 348, "capt": 22, "up": 61, "srv": 92, "rtt": 153,
          "mask": 37, "pixel": 8}),
        ("10-31 12:00:00.000 1 2 I ISEE_LAT: latency 12 · capt — · pixel 3",
         {"latency": 12, "pixel": 3}),
        ("I sam3vision: conversion: 5 ms", None),
        ("I ISEE_LAT: latency — · capt 1", None),
    ]
    for brute, attendu in cas:
        assert parse_ligne(brute) == attendu, brute
    bloc = "\n".join(f"I ISEE_LAT: latency {v} · pixel 1" for v in (900, 800, 1, 2, 3, 4, 5))
    assert [f["latency"] for f in parse_texte(bloc, skip=2)] == [1, 2, 3, 4, 5]
    assert parse_texte(bloc, skip=0)[0]["latency"] == 900
    assert resume([1, 2, 3, 4, 5]) == (5, 3.0, 3, 5, 5, 5)
    assert resume(list(range(1, 101))) == (100, 50.5, 50, 95, 99, 100)
    assert centile([10, 20], 50) == 10 and centile([10, 20], 95) == 20
    assert centile(list(range(1, 101)), 99) == 99
    assert gigue([100, 100, 100]) == 0.0 and gigue([42]) == 0.0
    assert abs(gigue([100, 200]) - 50.0) < 1e-9
    melange = ("I ISEE_LAT: latency — · capt 1\n"
               "I ISEE_LAT: latency 12 · pixel 3\n"
               "I sam3vision: conversion: 5 ms\n")
    assert jetees(melange, skip=0) == 1
    assert jetees(melange, skip=1) == 0    # le warmup mange la ligne jetée
    assert jetees("I ISEE_LAT: latency 12 · pixel 3\n", skip=0) == 0
    tenu, ok = verdict([{"latency": v} for v in (100, 200, 300)], 300, 500)
    rate, ko = verdict([{"latency": v} for v in (100, 600)], 300, 500)
    assert ok and not ko and any("TENU" in l for l in tenu)
    assert any("NON TENU" in l for l in rate)
    assert any("gigue(latency)" in l and "jetées 0" in l for l in tenu)
    assert any("gigue(latency) 81,6" in l.replace(".", ",") for l in tenu)
    return len(cas)


def selftest_seuils():
    """Verdict paramétrable : seuils optionnels inactifs à None, décisifs posés."""
    base = [{"latency": v} for v in (100, 200, 300)]
    assert verdict(base, 300, 500, seuil_p95=400)[1]
    assert not verdict(base, 300, 500, seuil_p95=200)[1]
    assert not verdict(base, 300, 500, seuil_p99=200)[1]
    assert not verdict(base, 300, 500, seuil_gigue=10.0)[1]
    assert verdict(base, 300, 500, seuil_jetees=0)[1]
    assert not verdict(base, 300, 500, seuil_jetees=0, nb_jetees=2)[1]
    assert verdict(base, 300, 500, seuil_jetees=2, nb_jetees=2)[1]
    lignes_jetees = verdict(base, 300, 500, seuil_jetees=0, nb_jetees=2)[0]
    assert any("jetées 2" in l for l in lignes_jetees)
    # CLI : les nouveaux flags traversent jusqu'au verdict.
    options, positionnels = extrait_options(["lat.txt", "--p95", "450", "--jitter", "12.5"])
    assert options["--p95"] == 450 and options["--jitter"] == 12.5
    assert options["--p99"] is None and positionnels == ["lat.txt"]


def selftest_serveurs():
    """Étages serveur : formes, ordre des rangs, CLI muette et parlante."""
    srv = "I ISEE_LAT: server: preprocess 7 ms · detr 52 ms"
    assert parse_ligne_server(srv) == {"preprocess": 7, "detr": 52}
    assert parse_ligne_server("10-31 12:00:00.000 1 2 I ISEE_LAT: server: —") is None
    assert parse_ligne_server("I ISEE_LAT: latency 12 · pixel 3") is None
    assert parse_ligne("I ISEE_LAT: server: preprocess 7 ms") is None
    melange = ("I ISEE_LAT: latency 100 · pixel 1\n" + srv + "\n"
               "I ISEE_LAT: server: vision 47 ms · queue 3 ms · decode 2 ms\n")
    assert parse_texte_server("I ISEE_LAT: latency 100 · pixel 1\n", skip=0) == []
    assert parse_texte_server(melange, skip=1) == [{"vision": 47, "queue": 3, "decode": 2}]
    rangs = tableau_serveurs(parse_texte_server(melange, skip=0))[1:]
    noms = [l.split()[0] for l in rangs]
    assert noms == ["preprocess", "vision", "detr", "depth", "masks", "total",
                    "decode", "queue"]
    tableau = tableau_serveurs([{"detr": v} for v in (10, 20, 30, 40, 50)])
    assert [l.split() for l in tableau if l.split()[0] == "detr"] == [
        ["detr", "5", "30.0", "30", "50", "50", "50"]]
    import contextlib, io
    vide, plein = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(vide), contextlib.redirect_stderr(vide):
        assert main_serveurs("I ISEE_LAT: latency 1 · pixel 1\n", 0) == 2
    with contextlib.redirect_stdout(plein), contextlib.redirect_stderr(plein):
        assert main_serveurs(srv + "\n", 0) == 0
    assert "detr" in plein.getvalue() and "aucune ligne" in vide.getvalue()


def selftest_capsplit():
    """Capsplit : formes, warmup, somme frame par frame, comparaison au `capt`."""
    cap = "I ISEE_LAT: capsplit age 81 · enc 24 · b64 3 · cb 2 · total 30"
    srv = "I ISEE_LAT: server: preprocess 7 ms · detr 52 ms"
    assert parse_ligne_capsplit(cap) == {"age": 81, "enc": 24, "b64": 3, "cb": 2,
                                         "total": 30}
    assert "total" in parse_ligne_capsplit(cap)
    assert parse_ligne_capsplit("I ISEE_LAT: capsplit age 81 · enc 24 · b64 3 · cb 2"
                                " · total —") == {"age": 81, "enc": 24, "b64": 3, "cb": 2}
    assert parse_ligne_capsplit("I ISEE_LAT: capsplit —") is None
    assert parse_ligne_capsplit("I ISEE_LAT: latency 348 · capt 111 · pixel 8") is None
    assert parse_ligne_capsplit(srv) is None
    assert parse_ligne(cap) is None and parse_ligne_server(cap) is None
    bloc_cap = "\n".join(f"I ISEE_LAT: capsplit age {v} · enc 1 · b64 1 · cb 1 · total 5"
                         for v in (900, 800, 10, 11))
    assert [l["age"] for l in parse_texte_capsplit(bloc_cap, skip=2)] == [10, 11]
    assert parse_texte_capsplit(cap + "\n", skip=0) == [
        {"age": 81, "enc": 24, "b64": 3, "cb": 2, "total": 30}]
    cap_table = tableau_capsplit(
        parse_texte_capsplit(cap + "\n", 0),
        parse_texte("I ISEE_LAT: latency 348 · capt 111 · pixel 8\n", skip=0))
    assert [l.split() for l in cap_table if l.split()[0] == "somme"] == [
        ["somme", "1", "110.0", "110", "110", "110", "110"]]
    assert any("écart -1.0" in l for l in cap_table)
    assert tableau_capsplit([], [])[-1] == "capt non mesuré dans ce dump"
    # L'intervalle : lu, affiché, jamais additionné au `capt`.
    avec_int = "I ISEE_LAT: capsplit age 81 · enc 24 · b64 3 · cb 2 · total 30 · int 33"
    assert parse_ligne_capsplit(avec_int) == {"age": 81, "enc": 24, "b64": 3, "cb": 2,
                                              "total": 30, "int": 33}
    table_int = tableau_capsplit(parse_texte_capsplit(avec_int + "\n", 0), [])
    assert [l.split() for l in table_int if l.split()[0] == "int"] == [
        ["int", "1", "33.0", "33", "33", "33", "33"]]
    assert [l.split() for l in table_int if l.split()[0] == "somme"] == [
        ["somme", "1", "110.0", "110", "110", "110", "110"]]   # sans `int`
    # Un dump d'avant l'intervalle reste lisible : le poste est absent, pas faux.
    assert "int" not in parse_ligne_capsplit(cap)
    import contextlib, io
    muet, parle = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(muet), contextlib.redirect_stderr(muet):
        assert main_capsplit("I ISEE_LAT: latency 1 · pixel 1\n", 0) == 2
    with contextlib.redirect_stdout(parle), contextlib.redirect_stderr(parle):
        assert main_capsplit(cap + "\n", 0) == 0
    assert "somme" in parle.getvalue() and "aucune ligne" in muet.getvalue()


def selftest():
    """Assertions du parseur : quatre blocs (frames, seuils, serveurs, capsplit)."""
    cas = selftest_frames()
    selftest_seuils()
    selftest_serveurs()
    selftest_capsplit()
    print(f"OK   {cas + 55} assertions parse_isee_lat")  # 55 asserts + 4 cas
    return 0


FLAGS_NOMBRES = {"--skip": int, "--mean": int, "--max": int, "--p95": int,
                 "--p99": int, "--drop": int, "--jitter": float}
OPTIONS_DEFAUT = {"--skip": WARMUP_DEFAUT, "--mean": CIBLE_MOYENNE, "--max": CIBLE_MAX,
                  "--p95": None, "--p99": None, "--drop": None, "--jitter": None}


def lit_entree(chemin):
    """Contenu du dump : fichier UTF-8, ou stdin quand aucun chemin n'est donné."""
    if chemin is None:
        return sys.stdin.read()
    with open(chemin, encoding="utf-8") as fichier:
        return fichier.read()


def extrait_options(args):
    """Consomme les `--flag valeur` numériques ; (options, positionnels restants)."""
    options = dict(OPTIONS_DEFAUT)
    positionnels, i = [], 0
    while i < len(args):
        if args[i] in FLAGS_NOMBRES:
            options[args[i]] = FLAGS_NOMBRES[args[i]](args[i + 1])
            i += 2
        else:
            positionnels.append(args[i])
            i += 1
    return options, positionnels


def main(argv):
    """CLI : [fichier] [--skip K] [--mean M] [--max X] [--p95 P] [--p99 Q]
    [--jitter J] [--drop D] [--servers|--capsplit] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    args = [a for a in argv if a not in ("--servers", "--capsplit")]
    if "--servers" in argv and "--capsplit" in argv:
        print("usage : --servers et --capsplit sont exclusifs (un dump, un mode)",
              file=sys.stderr)
        return 2
    options, args = extrait_options(args)
    chemin = args.pop(0) if args and not args[0].startswith("-") else None
    if args or (chemin is None and sys.stdin.isatty()):
        print("usage : parse_isee_lat.py [fichier] [--skip K] [--mean M] [--max X]"
              " [--p95 P] [--p99 Q] [--jitter J] [--drop D] [--servers|--capsplit]",
              file=sys.stderr)
        return 2
    texte = lit_entree(chemin)
    skip = options["--skip"]
    if "--servers" in argv:
        return main_serveurs(texte, skip)
    if "--capsplit" in argv:
        return main_capsplit(texte, skip)
    frames = parse_texte(texte, skip)
    if not frames:
        print("aucune frame ISEE_LAT complète (GPU froid ? tag filtré ?)", file=sys.stderr)
        return 2
    lignes, ok = verdict(frames, options["--mean"], options["--max"],
                         options["--p95"], options["--p99"],
                         options["--jitter"], options["--drop"],
                         nb_jetees=jetees(texte, skip))
    print("\n".join(lignes))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
