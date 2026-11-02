#!/usr/bin/env python3
"""parse_glasses_e2e_selftest.py — assertions de la jointure E2E, hors du module.

Extrait de `scripts/parse_glasses_e2e.py` pour tenir la limite maison (< 150 lignes
pour ce script) : les trois blocs (formes, jointure, verdict) vivent ici, le parseur
garde un appel paresseux (`--selftest`). Aucune logique dupliquée : ce module ne fait
qu'importer et asserter.

Usage :
    python3 scripts/parse_glasses_e2e.py --selftest   # voie normale (CI)
    python3 scripts/parse_glasses_e2e_selftest.py     # direct, même verdict

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import sys

from parse_glasses_e2e import joint, parse_lat, parse_link, verdict


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


def selftest():
    """Assertions de la jointure : trois blocs (formes, jointure, verdict)."""
    cas = selftest_formes() + selftest_jointure() + selftest_verdict()
    print(f"OK   {cas} assertions parse_glasses_e2e")
    return 0


if __name__ == "__main__":
    sys.exit(selftest())
