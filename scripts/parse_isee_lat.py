#!/usr/bin/env python3
"""parse_isee_lat.py — verdict 500 ms max / 300 ms en moyenne sur une campagne ISEE_LAT.

Entrée : un dump `adb logcat -s ISEE_LAT` (fichier ou stdin), une ligne par frame
complète, écrite par `LiveLatencyLog` (`app/live/`) — la même chaîne que l'écran :

    latency 348 · capt 22 · up 61 · srv 92 · rtt 153 · mask 37 · pixel 8

Un poste non mesuré vaut `—` (trait cadratin) : la frame reste comptée, le poste
est ignoré pour ce poste-là. Les K premières frames partent au warmup
(`--skip`, défaut 5 : réveil GPU, caches froids).

Sortie : n, moyenne, p50, p95, max par poste, puis le verdict
(`moyenne(latency) <= 300` et `max(latency) <= 500`, modifiables par
`--mean`/`--max`). Code 0 si tenu, 1 sinon.

Mode `--servers` : même entrée, mais agrège les lignes `server:` (détail des
étages, `LiveLatencyLog.serverLine` sous le même tag) au lieu du verdict —
n, moyenne, p50, p95, max par étage (`preprocess`, `vision`, `detr`,
`depth`, `masks`, `total`, puis les inconnus triés). Les frames sans ligne
`server:` (FAST_TIMING éteint) ne comptent dans aucun étage. Code 0 si au
moins une ligne `server:` est trouvée, 2 sinon. Le défaut reste le verdict.

Mode `--capsplit` : même dump, mais agrège les lignes `capsplit` (décomposition du
poste `capt`, `TimingsText.capsplit` sous le même tag, loggée 1 frame sur 20) :
`age` = capteur → entrée analyseur (expo, readout, livraison, attente fil),
`enc` = NV21 → JPEG, `b64` = base64 + horodatage, `cb` = rappel `onFrame`
(profondeur, mouvement, réserve/envoi), `total` = analyse complète (durée du
callback). `age + enc + b64 + cb` retrouve le `capt` de la sonde à ~1 ms près —
c'est cette somme, et non `age` seul, qui explique les dizaines de ms hors
encode et hors base64. Le tableau finit par la comparaison avec le `capt` mesuré
du dump (`capt non mesuré dans ce dump` si la sonde n'a rien écrit). Code 0 si
au moins une ligne `capsplit` est trouvée, 2 sinon. `--servers` et `--capsplit`
sont exclusifs (usage, code 2).

Campagne de référence (écran iSee, A100 eu-west, GPU chaud, Wi-Fi figé,
N >= 100 frames chaudes) : voir l'en-tête — `~/bin/phone run ... -- adb -s
<tel> logcat`, jamais d'`adb` nu.

Usage :
    python3 scripts/parse_isee_lat.py lat.txt
    adb -s <tel> logcat -d -s ISEE_LAT | python3 scripts/parse_isee_lat.py
    python3 scripts/parse_isee_lat.py --servers lat.txt
    python3 scripts/parse_isee_lat.py --capsplit lat.txt
    adb -s <tel> logcat -d -s ISEE_LAT | python3 scripts/parse_isee_lat.py --capsplit
    python3 scripts/parse_isee_lat.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import re
import sys

POSTES = ("latency", "capt", "up", "srv", "rtt", "mask", "pixel")
LIGNE = re.compile(r"ISEE_LAT:\s*(latency\s+\S.*)")
POSTE = re.compile(r"(latency|capt|up|srv|rtt|mask|pixel)\s+(\d+|—)")
ETAGES = ("preprocess", "vision", "detr", "depth", "masks", "total")
LIGNE_SERVEUR = re.compile(r"ISEE_LAT:\s*server:\s*(.*)")
ETAGE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s+(\d+|—)\s*ms")
POSTES_CAPSPLIT = ("age", "enc", "b64", "cb", "total")
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
    """(n, moyenne, p50, p95, max) d'une série non vide."""
    tri = sorted(valeurs)
    return (len(tri), sum(tri) / len(tri), centile(tri, 50),
            centile(tri, 95), tri[-1])


def verdict(frames, cible_moyenne=CIBLE_MOYENNE, cible_max=CIBLE_MAX):
    """(lignes, ok) : tableau par poste + verdict sur `latency`."""
    lignes = ["poste    n  moyenne   p50   p95   max"]
    ok = True
    for poste in POSTES:
        valeurs = [f[poste] for f in frames if poste in f]
        if not valeurs:
            lignes.append(f"{poste:7} 0  —")
            continue
        n, moyenne, p50, p95, maxi = resume(valeurs)
        lignes.append(f"{poste:7} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {maxi:5}")
        if poste == "latency":
            ok = moyenne <= cible_moyenne and maxi <= cible_max
            lignes.append(f"cible : moyenne <= {cible_moyenne}, max <= {cible_max}")
            lignes.append(f"verdict : {'TENU' if ok else 'NON TENU'} "
                          f"(moyenne {moyenne:.1f}, max {maxi})")
    return lignes, ok


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
    """n/moyenne/p50/p95/max par étage (connus, puis inconnus triés)."""
    table = ["étage        n  moyenne   p50   p95   max"]
    autres = sorted({nom for m in lignes for nom in m} - set(ETAGES))
    for nom in ETAGES + tuple(autres):
        valeurs = [m[nom] for m in lignes if nom in m]
        if not valeurs:
            table.append(f"{nom:10}   0  —")
            continue
        n, moyenne, p50, p95, maxi = resume(valeurs)
        table.append(f"{nom:10} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {maxi:5}")
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
    """n/moyenne/p50/p95/max par poste, la `somme`, puis la comparaison `capt`."""
    table = ["poste        n  moyenne   p50   p95   max"]
    for poste in POSTES_CAPSPLIT:
        valeurs = [m[poste] for m in lignes if poste in m]
        if not valeurs:
            table.append(f"{poste:10}   0  —")
            continue
        n, moyenne, p50, p95, maxi = resume(valeurs)
        table.append(f"{poste:10} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {maxi:5}")
    # Somme frame par frame : seules les frames où les quatre postes existent.
    sommes = [m["age"] + m["enc"] + m["b64"] + m["cb"] for m in lignes
              if all(p in m for p in POSTES_CAPSPLIT[:4])]
    moyenne_somme = None
    if sommes:
        n, moyenne_somme, p50, p95, maxi = resume(sommes)
        table.append(f"{'somme':10} {n:3} {moyenne_somme:7.1f} {p50:5} {p95:5} {maxi:5}")
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
    assert resume([1, 2, 3, 4, 5]) == (5, 3.0, 3, 5, 5)
    assert centile([10, 20], 50) == 10 and centile([10, 20], 95) == 20
    tenu, ok = verdict([{"latency": v} for v in (100, 200, 300)], 300, 500)
    rate, ko = verdict([{"latency": v} for v in (100, 600)], 300, 500)
    assert ok and not ko and any("TENU" in l for l in tenu)
    assert any("NON TENU" in l for l in rate)
    return len(cas)


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
        ["detr", "5", "30.0", "30", "50", "50"]]
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
        ["somme", "1", "110.0", "110", "110", "110"]]
    assert any("écart -1.0" in l for l in cap_table)
    assert tableau_capsplit([], [])[-1] == "capt non mesuré dans ce dump"
    import contextlib, io
    muet, parle = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(muet), contextlib.redirect_stderr(muet):
        assert main_capsplit("I ISEE_LAT: latency 1 · pixel 1\n", 0) == 2
    with contextlib.redirect_stdout(parle), contextlib.redirect_stderr(parle):
        assert main_capsplit(cap + "\n", 0) == 0
    assert "somme" in parle.getvalue() and "aucune ligne" in muet.getvalue()


def selftest():
    """Assertions du parseur : trois blocs indépendants (verdict, serveurs, capsplit)."""
    cas = selftest_frames()
    selftest_serveurs()
    selftest_capsplit()
    print(f"OK   {cas + 32} assertions parse_isee_lat")  # 32 asserts + 4 cas
    return 0


def main(argv):
    """CLI : [fichier] [--skip K] [--mean M] [--max X] [--servers|--capsplit] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    skip, cible_moyenne, cible_max, chemin = WARMUP_DEFAUT, CIBLE_MOYENNE, CIBLE_MAX, None
    args = list(argv)
    mode_serveurs = "--servers" in args
    mode_capsplit = "--capsplit" in args
    args = [a for a in args if a not in ("--servers", "--capsplit")]
    if mode_serveurs and mode_capsplit:
        print("usage : --servers et --capsplit sont exclusifs (un dump, un mode)",
              file=sys.stderr)
        return 2
    for flag, place in (("--skip", 0), ("--mean", 1), ("--max", 2)):
        while flag in args:
            i = args.index(flag)
            valeur = int(args[i + 1])
            del args[i:i + 2]
            (skip, cible_moyenne, cible_max) = (
                valeur if place == 0 else skip, valeur if place == 1 else cible_moyenne,
                valeur if place == 2 else cible_max)
    if args and not args[0].startswith("-"):
        chemin = args.pop(0)
    if args or (chemin is None and sys.stdin.isatty()):
        print("usage : parse_isee_lat.py [fichier] [--skip K] [--mean M] [--max X]"
              " [--servers|--capsplit]", file=sys.stderr)
        return 2
    texte = open(chemin, encoding="utf-8").read() if chemin else sys.stdin.read()
    if mode_serveurs:
        return main_serveurs(texte, skip)
    if mode_capsplit:
        return main_capsplit(texte, skip)
    frames = parse_texte(texte, skip)
    if not frames:
        print("aucune frame ISEE_LAT complète (GPU froid ? tag filtré ?)", file=sys.stderr)
        return 2
    lignes, ok = verdict(frames, cible_moyenne, cible_max)
    print("\n".join(lignes))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
