#!/usr/bin/env python3
"""parse_isee_lat_selftest.py — assertions du parseur ISEE_LAT, hors du module.

Extrait de `scripts/parse_isee_lat.py` pour tenir la limite maison (< 500 lignes
par fichier) : les cinq blocs (frames, seuils, serveurs, capsplit, decsplit)
vivent ici, le parseur garde un appel paresseux (`--selftest`). Aucune logique
dupliquée : ce module ne fait qu'importer et asserter.

Usage :
    python3 scripts/parse_isee_lat.py --selftest   # voie normale (CI)
    python3 scripts/parse_isee_lat_selftest.py     # direct, même verdict

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import sys

from parse_isee_lat import (centile, extrait_options, gigue, jetees, main_capsplit,
                            main_decsplit, main_serveurs, parse_ligne, parse_ligne_capsplit,
                            parse_ligne_decsplit, parse_ligne_server, parse_texte,
                            parse_texte_capsplit, parse_texte_decsplit, parse_texte_server,
                            resume, tableau_capsplit, tableau_decsplit, tableau_serveurs,
                            verdict)


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


def selftest_decsplit():
    """Decsplit : formes, warmup, somme frame par frame, comparaison au `mask`."""
    dec = "I ISEE_LAT: decsplit b64 2 · img 28 · up 1 · paint 6 · total 37 · masks 12"
    cap = "I ISEE_LAT: capsplit age 81 · enc 24 · b64 3 · cb 2 · total 30 · int 33"
    assert parse_ligne_decsplit(dec) == {"b64": 2, "img": 28, "up": 1, "paint": 6,
                                         "total": 37, "masks": 12}
    assert parse_ligne_decsplit(
        "I ISEE_LAT: decsplit b64 2 · img — · up 1 · paint 6 · total 37 · masks 12"
    ) == {"b64": 2, "up": 1, "paint": 6, "total": 37, "masks": 12}
    assert parse_ligne_decsplit("I ISEE_LAT: decsplit —") is None
    assert parse_ligne_decsplit("I ISEE_LAT: latency 348 · mask 37 · pixel 8") is None
    assert parse_ligne_decsplit(cap) is None
    assert parse_ligne(dec) is None and parse_ligne_server(dec) is None
    assert parse_ligne_capsplit(dec) is None
    bloc_dec = "\n".join(f"I ISEE_LAT: decsplit b64 {v} · img 1 · up 1 · paint 1"
                         f" · total 5 · masks 2" for v in (900, 800, 10, 11))
    assert [l["b64"] for l in parse_texte_decsplit(bloc_dec, skip=2)] == [10, 11]
    dec_table = tableau_decsplit(
        parse_texte_decsplit(dec + "\n", 0),
        parse_texte("I ISEE_LAT: latency 348 · mask 36 · pixel 8\n", skip=0))
    assert [l.split() for l in dec_table if l.split()[0] == "somme"] == [
        ["somme", "1", "37.0", "37", "37", "37", "37"]]
    assert any("écart +1.0" in l for l in dec_table)
    assert tableau_decsplit([], [])[-1] == "mask non mesuré dans ce dump"
    # `masks` : lu, affiché, jamais additionné au `mask`.
    assert [l.split() for l in dec_table if l.split()[0] == "masks"] == [
        ["masks", "1", "12.0", "12", "12", "12", "12"]]
    import contextlib
    import io
    muet, parle = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(muet), contextlib.redirect_stderr(muet):
        assert main_decsplit("I ISEE_LAT: latency 1 · pixel 1\n", 0) == 2
    with contextlib.redirect_stdout(parle), contextlib.redirect_stderr(parle):
        assert main_decsplit(dec + "\n", 0) == 0
    assert "somme" in parle.getvalue() and "aucune ligne" in muet.getvalue()


def selftest():
    """Assertions du parseur : cinq blocs (frames, seuils, serveurs, capsplit, decsplit)."""
    cas = selftest_frames()
    selftest_seuils()
    selftest_serveurs()
    selftest_capsplit()
    selftest_decsplit()
    print(f"OK   {cas + 70} assertions parse_isee_lat")  # 70 asserts + 4 cas
    return 0


if __name__ == "__main__":
    sys.exit(selftest())
