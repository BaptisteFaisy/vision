#!/usr/bin/env python3
"""parse_glasses_e2e_selftest.py — assertions de la jointure E2E, hors du module.

Extrait de `scripts/parse_glasses_e2e.py` pour tenir la limite maison (< 500 lignes
par fichier) : les cinq blocs (formes, jointure, verdict, secours, diagnostic)
vivent ici, le parseur garde un appel paresseux (`--selftest`). Aucune logique
dupliquée : ce module ne fait qu'importer et asserter.

Usage :
    python3 scripts/parse_glasses_e2e.py --selftest   # voie normale (CI)
    python3 scripts/parse_glasses_e2e_selftest.py     # direct, même verdict

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import sys

from parse_glasses_e2e import (appariees, extrait_voisin, fusion, joint, ligne_seq,
                               ligne_series, pas_median, parse_lat, parse_link, plage,
                               recouvrement, series, verdict, voisine)


def selftest_formes():
    """Formes logcat : jointes, jetées, sans seq, autre tag."""
    cas_lat = [
        ("I ISEE_LAT: latency 148 · capt 42 · pixel 8 · seq 12", (12, 148)),
        ("10-31 12:00:00.000 1 2 I ISEE_LAT: latency 12 · capt — · pixel 3 · seq 7",
         (7, 12)),
        ("I ISEE_LAT: latency — · cause timeout", None),
        ("I ISEE_LAT: latency 148 · pixel 8", None),
        ("I ISEE_LAT: server: detr 52 ms", None),
        ("I sam3vision: conversion: 5 ms", None),
    ]
    for brute, attendu in cas_lat:
        assert parse_lat(brute) == attendu, brute
    cas_link = [
        ("I GlassesLink: link seq 12 · lien 30123 · dec 8421 · aff 7110 · percu 45654",
         (12, 30123)),
        ("I GlassesLink: link seq 3 · lien — · dec 1 · aff 2 · percu 3", None),
        ("I GlassesLink: resume p50 57 · p95 111", None),
    ]
    for brute, attendu in cas_link:
        assert parse_link(brute) == attendu, brute
    return len(cas_lat) + len(cas_link)


def selftest_jointure():
    """Jointure sur seq : ordre, warmup, trous d'un côté, E2E = lien/1000 + lat."""
    bloc = "\n".join([
        "I GlassesLink: link seq 2 · lien 20000 · dec 1 · aff 1 · percu 1",
        "I ISEE_LAT: latency 100 · pixel 1 · seq 1",
        "I ISEE_LAT: latency 200 · pixel 1 · seq 2",
        "I GlassesLink: link seq 1 · lien 10000 · dec 1 · aff 1 · percu 1",
        "I ISEE_LAT: latency 300 · pixel 1 · seq 9",
    ])
    e2e, n_lat, n_link = joint(bloc, skip=0)
    assert (n_lat, n_link) == (3, 2)
    assert e2e == [110.0, 220.0], e2e  # seq 9 sans lien : non jointe
    e2e, _, _ = joint(bloc, skip=1)
    assert e2e == [220.0], e2e
    return 4


def selftest_verdict():
    """Verdict : les trois seuils ensemble (p50<=200, p95<=250, max<=400)."""
    base = [100.0] * 95 + [200.0] * 4 + [300.0]
    lignes, ok = verdict(base)
    assert ok, lignes
    assert lignes[-1].startswith("verdict : TENU"), lignes
    assert not verdict([100.0] * 49 + [300.0] * 51)[1]  # p50 saute
    assert not verdict([100.0] * 90 + [300.0] * 10)[1]  # p95 seul saute
    assert not verdict([100.0] * 99 + [401.0])[1]  # max saute
    limites = [200.0] * 50 + [250.0] * 45 + [400.0] * 5
    assert verdict(limites)[1], limites  # seuils inclus : TENU
    return 6


def selftest_secours():
    """Secours : plus proche borné, ex æquo causal, warmup sur l'union, strict à 0."""
    triees = [10, 20, 30]
    assert voisine(12, triees, 3) == 10
    assert voisine(18, triees, 3) == 20
    assert voisine(15, triees, 5) == 10  # ex æquo : la plus petite (causalité)
    assert voisine(14, triees, 3) is None  # hors tolérance
    assert voisine(12, triees, 0) is None  # --voisin 0 = strict
    assert voisine(12, [], 3) is None
    lat = {10: 100, 12: 100, 17: 100, 40: 100}
    link = {10: 20000, 20: 20000}
    paires = appariees(lat, link, 3)
    assert paires == [(12, 120.0, 2), (17, 120.0, 3)], paires  # 40 trop loin
    assert appariees(lat, link, 0) == []
    preuve, total, compte = fusion(lat, link, skip=0, voisin=3)
    assert preuve == [120.0] and total == [120.0, 120.0, 120.0] and compte == (1, 2, 3)
    preuve, total, compte = fusion(lat, link, skip=1, voisin=3)
    assert compte == (0, 2, 3) and total == [120.0, 120.0]  # warmup mange seq 10
    preuve, total, compte = fusion(lat, link, skip=0, voisin=0)
    assert (preuve, total, compte) == ([120.0], [120.0], (1, 0, 0))
    assert extrait_voisin(["f.txt", "--voisin", "2"]) == (2, ["f.txt"])
    assert extrait_voisin(["f.txt"]) == (3, ["f.txt"])
    return 13


def selftest_diagnostic():
    """Diagnostic : séries, doublons, spans, pas médians, recouvrement, lignes."""
    bloc = "\n".join([
        "I GlassesLink: link seq 10 · lien 20000 · dec 1 · aff 1 · percu 1",
        "I ISEE_LAT: latency 100 · pixel 1 · seq 10",
        "I ISEE_LAT: latency 110 · pixel 1 · seq 10",
        "I ISEE_LAT: latency 200 · pixel 1 · seq 12",
        "I ISEE_LAT: latency 300 · pixel 1 · seq 14",
    ])
    lat, link, doubles_lat, doubles_link = series(bloc)
    assert lat == {10: 110, 12: 200, 14: 300}  # dernière gagne, comme avant
    assert (doubles_lat, doubles_link) == (1, 0)
    assert plage(lat) == "10–14" and plage({}) == "—"
    assert pas_median(lat) == 2 and pas_median(link) is None
    assert pas_median({10: 1, 15: 1, 25: 1}) == 5  # [5, 10], rang-proche : borne basse
    assert recouvrement(lat, link) == "10–10"
    assert recouvrement(lat, {}) == "aucun"
    assert recouvrement({1: 1}, {5: 1}) == "aucun"
    ligne = ligne_seq(lat, link, doubles_lat, doubles_link)
    assert ligne == ("seq : lat 10–14 (pas 2) · link 10–10 (pas —) · "
                     "recouvrement 10–10 · doublons lat 1, link 0"), ligne
    assert ligne_series(3, 1, 1, 2, 2, 3, 5) == (
        "séries : ISEE_LAT 3 · GlassesLink 1 · jointes 3 "
        "(exactes 1 + appariées ±3 2, Δmax 2) (warmup 5)")
    assert ligne_series(3, 3, 3, 0, 0, 3, 5).endswith("jointes 3 (100 % exactes, preuve) (warmup 5)")
    assert ligne_series(0, 0, 0, 0, 0, 3, 5).endswith("jointes 0 (aucune) (warmup 5)")
    return 13


def selftest():
    """Assertions de la jointure : cinq blocs (formes, jointure, verdict, secours, diagnostic)."""
    cas = (selftest_formes() + selftest_jointure() + selftest_verdict()
           + selftest_secours() + selftest_diagnostic())
    print(f"OK   {cas} assertions parse_glasses_e2e")
    return 0


if __name__ == "__main__":
    sys.exit(selftest())
