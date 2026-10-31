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

Campagne de référence (écran iSee, A100 eu-west, GPU chaud, Wi-Fi figé,
N >= 100 frames chaudes) : voir l'en-tête — `~/bin/phone run ... -- adb -s
<tel> logcat`, jamais d'`adb` nu.

Usage :
    python3 scripts/parse_isee_lat.py lat.txt
    adb -s <tel> logcat -d -s ISEE_LAT | python3 scripts/parse_isee_lat.py
    python3 scripts/parse_isee_lat.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import re
import sys

POSTES = ("latency", "capt", "up", "srv", "rtt", "mask", "pixel")
LIGNE = re.compile(r"ISEE_LAT:\s*(latency\s+\S.*)")
POSTE = re.compile(r"(latency|capt|up|srv|rtt|mask|pixel)\s+(\d+|—)")
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


def selftest():
    """Assertions du parseur : formes, warmup, centiles, verdict."""
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
    print(f"OK   {len(cas) + 7} assertions parse_isee_lat")
    return 0


def main(argv):
    """CLI : [fichier] [--skip K] [--mean M] [--max X] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    skip, cible_moyenne, cible_max, chemin = WARMUP_DEFAUT, CIBLE_MOYENNE, CIBLE_MAX, None
    args = list(argv)
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
        print("usage : parse_isee_lat.py [fichier] [--skip K] [--mean M] [--max X]",
              file=sys.stderr)
        return 2
    texte = open(chemin, encoding="utf-8").read() if chemin else sys.stdin.read()
    frames = parse_texte(texte, skip)
    if not frames:
        print("aucune frame ISEE_LAT complète (GPU froid ? tag filtré ?)", file=sys.stderr)
        return 2
    lignes, ok = verdict(frames, cible_moyenne, cible_max)
    print("\n".join(lignes))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
