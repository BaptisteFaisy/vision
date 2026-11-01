#!/usr/bin/env python3
"""lat_stats.py — stats d'une campagne longue ISEE_LAT (p50/p95/p99/max, gigue, jetées, DECODE).

Entrée : un dump `adb logcat -s ISEE_LAT sam3vision` (fichier ou stdin), collecté
pendant une session iSee longue sur téléphone réel. Le parsing des lignes
`ISEE_LAT` est réutilisé de `parse_isee_lat.py` (importé, jamais recopié) ; ce
script n'ajoute que ce que le verdict ne donne pas :

- p50/p95/**p99**/max par poste (`latency` + `capsplit`), avec effectifs ;
- **gigue** = écart-type échantillon de `latency` (et `p95 - p50` en rappel) ;
- **frames jetées** : compteurs cumulés des lignes `sam3vision` (`conversion:`
  `jetées`/`veto nuit`/`floues`, `analyse:` porte fermée) — delta premier→dernier ;
- **coût DECODE vs masques** : échantillons `masques décodés: N en M ms` (1/20),
  moyenne par N, pente ms/masque (moindres carrés), coût à 12 masques observé
  ou, à défaut, interpolé (étiqueté comme tel).

Sortie : tableaux texte sur stdout ; `--csv PREFIXE` écrit en plus trois CSV
(`PREFIXE-frames.csv`, `PREFIXE-capsplit.csv`, `PREFIXE-decode.csv`), une ligne
par mesure, warmup exclu comme dans `parse_isee_lat` (`--skip`, défaut 5).

Usage :
    python3 scripts/lat_stats.py dump.txt [--skip K] [--csv PREFIXE]
    adb -s <tel> logcat -d -s ISEE_LAT sam3vision | python3 scripts/lat_stats.py
    python3 scripts/lat_stats.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun appareil.
"""

import importlib.util
import os
import re
import statistics
import sys

# `parse_isee_lat` vit à côté : import par chemin (même motif que
# `check-budget-provenance.py`), pas de copie des regex ni des centiles.
_PL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "parse_isee_lat.py")
_spec = importlib.util.spec_from_file_location("parse_isee_lat", _PL)
parse_isee_lat = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(parse_isee_lat)

LIGNE_DECODE = re.compile(r"masques décodés:\s*(\d+)\s*en\s*(\d+)\s*ms")
LIGNE_CONV = re.compile(r"conversion:\s*(\d+)\s*ms\s*\(.*jetées\s*(\d+),\s*veto nuit\s*(\d+),\s*floues\s*(\d+)\)")
LIGNE_JETEES = re.compile(r"analyse:\s*(\d+)\s*frames jetées \(porte fermée\), veto nuit\s*(\d+)")
WARMUP_DEFAUT = 5


def resume_p99(valeurs):
    """(n, moyenne, p50, p95, p99, max, écart-type) d'une série non vide."""
    tri = sorted(valeurs)
    n, moyenne, p50, p95, maxi = parse_isee_lat.resume(valeurs)
    p99 = parse_isee_lat.centile(tri, 99)
    ecart = statistics.stdev(tri) if len(tri) >= 2 else 0.0
    return (n, moyenne, p50, p95, p99, maxi, ecart)


def tableau_postes(titre, postes, series):
    """Tableau n/moyenne/p50/p95/p99/max par poste ; postes vides marqués `—`."""
    lignes = [titre, "poste    n  moyenne   p50   p95   p99   max"]
    for poste in postes:
        valeurs = series(poste)
        if not valeurs:
            lignes.append(f"{poste:7} 0  —")
            continue
        n, moyenne, p50, p95, p99, maxi, _ = resume_p99(valeurs)
        lignes.append(f"{poste:7} {n:3} {moyenne:7.1f} {p50:5} {p95:5} {p99:5} {maxi:5}")
    return lignes


def ligne_gigue(frames):
    """Gigue de `latency` : écart-type échantillon + rappel `p95 - p50`."""
    valeurs = [f["latency"] for f in frames if "latency" in f]
    if len(valeurs) < 2:
        return "gigue : n < 2, non calculable"
    _, _, p50, p95, _, _, ecart = resume_p99(valeurs)
    return (f"gigue latency (n={len(valeurs)}) : écart-type {ecart:.1f} ms · "
            f"p95-p50 {p95 - p50} ms")


def parse_sam3vision(texte):
    """Échantillons `sam3vision` : décodages (masques, ms) + compteurs cumulés."""
    decodages = [(int(n), int(ms)) for n, ms in LIGNE_DECODE.findall(texte)]
    conversions = [tuple(int(v) for v in m) for m in LIGNE_CONV.findall(texte)]
    jetees = [(int(n), int(v)) for n, v in LIGNE_JETEES.findall(texte)]
    return decodages, conversions, jetees


def tableau_jetees(conversions, jetees):
    """Deltas premier→dernier des compteurs cumulés (jetées, veto, floues)."""
    lignes = ["jetées"]
    if conversions:
        premiers, derniers = conversions[0], conversions[-1]
        noms = ("jetées", "veto nuit", "floues")
        for i, nom in enumerate(noms):
            lignes.append(f"{nom:9} {derniers[i + 1] - premiers[i + 1]:+d} "
                          f"({premiers[i + 1]} → {derniers[i + 1]}, "
                          f"{len(conversions)} relevés)")
    else:
        lignes.append("conversion: aucun relevé (tag sam3vision filtré ?)")
    if jetees:
        lignes.append(f"porte fermée : {jetees[0][0]} → {jetees[-1][0]} "
                      f"({len(jetees)} relevés)")
    return lignes


def pente_moindres_carres(points):
    """Pente ms/masque (moindres carrés) ; 0.0 si un seul N observé."""
    ns = [p[0] for p in points]
    if len(set(ns)) < 2:
        return 0.0
    n = len(points)
    mx = sum(ns) / n
    my = sum(p[1] for p in points) / n
    denom = sum((x - mx) ** 2 for x in ns)
    return sum((x - mx) * (y - my) for x, y in points) / denom if denom else 0.0


def tableau_decode(decodages):
    """Coût DECODE par nombre de masques + coût à 12 (observé ou interpolé)."""
    lignes = ["decode (masques décodés: N en M ms, 1 paquet sur 20)"]
    if not decodages:
        return lignes + ["aucun échantillon (tag sam3vision filtré ?)"]
    par_n = {}
    for n, ms in decodages:
        par_n.setdefault(n, []).append(ms)
    lignes.append("N masques  n  moyenne   p50   max")
    for n in sorted(par_n):
        vals = par_n[n]
        _, moyenne, p50, _, _, maxi, _ = resume_p99(vals)
        lignes.append(f"{n:9} {len(vals):3} {moyenne:7.1f} {p50:5} {maxi:5}")
    lignes.append(f"pente : {pente_moindres_carres(decodages):.2f} ms/masque "
                  f"({len(decodages)} échantillons)")
    lignes.append(cout_a_douze(decodages, par_n))
    return lignes


def cout_a_douze(decodages, par_n):
    """Coût à 12 masques : moyenne observée si N=12 vu, sinon interpolation."""
    if 12 in par_n:
        vals = par_n[12]
        return (f"coût à 12 masques : OBSERVÉ {sum(vals) / len(vals):.1f} ms "
                f"(n={len(vals)})")
    proches = sorted(decodages, key=lambda p: abs(p[0] - 12))[:8]
    if len({p[0] for p in proches}) < 2:
        return "coût à 12 masques : non estimable (un seul N observé)"
    pente = pente_moindres_carres(proches)
    mx = sum(p[0] for p in proches) / len(proches)
    my = sum(p[1] for p in proches) / len(proches)
    return (f"coût à 12 masques : INTERPOLÉ {my + pente * (12 - mx):.1f} ms "
            f"(pente {pente:.2f}, {len(proches)} pts proches)")


def lignes_csv(entete, rangees):
    """En-tête + rangées en une seule liste (un `writelines`, pas de boucle)."""
    return [entete + "\n"] + [",".join(r) + "\n" for r in rangees]


def ecrire_csv(prefixe, frames, lignes_capsplit, decodages):
    """Trois CSV (frames, capsplit, decode), warmup déjà exclu par l'appelant."""
    postes = parse_isee_lat.POSTES
    with open(f"{prefixe}-frames.csv", "w", encoding="utf-8") as f:
        f.writelines(lignes_csv("idx," + ",".join(postes),
                                [[str(i)] + [str(m.get(p, "")) for p in postes]
                                 for i, m in enumerate(frames)]))
    postes_cap = parse_isee_lat.POSTES_CAPSPLIT
    with open(f"{prefixe}-capsplit.csv", "w", encoding="utf-8") as f:
        f.writelines(lignes_csv("idx," + ",".join(postes_cap),
                                [[str(i)] + [str(m.get(p, "")) for p in postes_cap]
                                 for i, m in enumerate(lignes_capsplit)]))
    with open(f"{prefixe}-decode.csv", "w", encoding="utf-8") as f:
        f.writelines(lignes_csv("idx,masques,ms",
                                [[str(i), str(n), str(ms)]
                                 for i, (n, ms) in enumerate(decodages)]))
    return [f"{prefixe}-frames.csv", f"{prefixe}-capsplit.csv", f"{prefixe}-decode.csv"]


def lit_entree(chemin):
    """Contenu du dump : fichier sous context manager, sinon stdin."""
    if chemin is None:
        return sys.stdin.read()
    with open(chemin, encoding="utf-8") as f:
        return f.read()


def analyser(texte, skip):
    """Les quatre tableaux d'une campagne : postes, gigue, jetées, decode."""
    frames = parse_isee_lat.parse_texte(texte, skip)
    lignes = parse_isee_lat.parse_texte_capsplit(texte, skip)
    decodages, conversions, jetees = parse_sam3vision(texte)
    if not frames:
        return None, (frames, lignes, decodages)
    tableaux = []
    tableaux += tableau_postes("postes ISEE_LAT", parse_isee_lat.POSTES,
                               lambda p: [f[p] for f in frames if p in f])
    tableaux.append(ligne_gigue(frames))
    tableaux += tableau_postes("postes capsplit", parse_isee_lat.POSTES_CAPSPLIT,
                               lambda p: [m[p] for m in lignes if p in m])
    tableaux += tableau_jetees(conversions, jetees)
    tableaux += tableau_decode(decodages)
    return tableaux, (frames, lignes, decodages)


def selftest():
    """Dump synthétique : percentiles, gigue, jetées, decode, CSV, pente."""
    dump = "".join(
        f"I ISEE_LAT: latency {100 + i} · capt 80 · up 30 · srv 90 · rtt 150 · "
        f"mask {5 + (i % 3)} · pixel 8\n" for i in range(10))
    dump += ("I ISEE_LAT: capsplit age 86 · enc 9 · b64 1 · cb 18 · total 32 · int 33\n"
             "I sam3vision: conversion: 32 ms (capteur 512×206, jpeg 20 Ko, jetées 10, veto nuit 2, floues 1)\n"
             "I sam3vision: masques décodés: 12 en 14 ms\n"
             "I sam3vision: masques décodés: 6 en 8 ms\n"
             "I sam3vision: conversion: 40 ms (capteur 512×206, jpeg 20 Ko, jetées 25, veto nuit 3, floues 4)\n"
             "I sam3vision: analyse: 60 frames jetées (porte fermée), veto nuit 1\n")
    tableaux, (frames, lignes, decodages) = analyser(dump, skip=0)
    assert len(frames) == 10 and len(lignes) == 1 and len(decodages) == 2
    assert any("p99" in l for l in tableaux)
    assert any("gigue latency (n=10)" in l for l in tableaux)
    assert any("jetées" in l and "+15" in l for l in tableaux), tableaux
    assert any("OBSERVÉ 14.0 ms (n=1)" in l for l in tableaux), tableaux
    assert pente_moindres_carres([(6, 8), (12, 14)]) == 1.0
    assert pente_moindres_carres([(6, 8), (6, 9)]) == 0.0
    assert "INTERPOLÉ" in cout_a_douze([(6, 8), (4, 6)], {6: [8], 4: [6]})
    assert "non estimable" in cout_a_douze([(6, 8)], {6: [8]})
    assert analyser("I sam3vision: rien\n", 0)[0] is None
    assert tableau_decode([])[-1].startswith("aucun échantillon")
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        fichiers = ecrire_csv(f"{tmp}/c", frames, lignes, decodages)
        contenu = lit_entree(fichiers[0]).splitlines()
        assert contenu[0] == "idx,latency,capt,up,srv,rtt,mask,pixel"
        assert contenu[1].split(",")[1] == "100" and len(contenu) == 11
        assert lit_entree(fichiers[1]).count("\n") == 2
        assert lit_entree(fichiers[2]).splitlines()[2] == "1,6,8"
    print("OK   14 assertions lat_stats")
    return 0


def main(argv):
    """CLI : [fichier] [--skip K] [--csv PREFIXE] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    skip, chemin, prefixe = WARMUP_DEFAUT, None, None
    args = list(argv)
    while "--skip" in args:
        i = args.index("--skip")
        skip = int(args[i + 1])
        del args[i:i + 2]
    while "--csv" in args:
        i = args.index("--csv")
        prefixe = args[i + 1]
        del args[i:i + 2]
    if args and not args[0].startswith("-"):
        chemin = args.pop(0)
    if args or (chemin is None and sys.stdin.isatty()):
        print("usage : lat_stats.py [fichier] [--skip K] [--csv PREFIXE]",
              file=sys.stderr)
        return 2
    tableaux, (frames, lignes, decodages) = analyser(lit_entree(chemin), skip)
    if tableaux is None:
        print("aucune frame ISEE_LAT complète (GPU froid ? tag filtré ?)",
              file=sys.stderr)
        return 2
    print("\n".join(tableaux))
    if prefixe:
        for f in ecrire_csv(prefixe, frames, lignes, decodages):
            print(f"csv : {f}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
