#!/usr/bin/env python3
"""parse_glasses_link.py — verdict B2 lunettes (`LUNETTES-RECETTE-TEL` § 3).

Entrée : un dump `adb logcat -s GlassesLink` (fichier ou stdin) — lignes
per-frame `TimingsText.link`, en microsecondes :

    link seq 12 · lien 30123 · dec 8421 · aff 7110 · percu 45654

plus les résumés `GlassesLink e2e … drops F+D` pour les pertes. Un poste non
mesuré vaut `—` : la frame reste comptée, le poste est ignoré pour ce
poste-là. Les K premières frames partent au warmup (`--skip`, défaut 5).

Sortie : n, moyenne, p50, p95, p99, max par poste, puis le verdict : TENU si
`p95(percu) < 100 ms`, avec les seuils de la recette (warn p95 > 70 ms,
critique p95 > 100 ms ou p99 > 150 ms ; pertes warn > 5 %, critique > 20 %).
Code 0 si tenu, 1 sinon, 2 sans ligne `link`.

Usage :
    python3 scripts/parse_glasses_link.py link.txt
    adb -s <tel> logcat -d -s GlassesLink | python3 scripts/parse_glasses_link.py
    python3 scripts/parse_glasses_link.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import re
import sys

POSTES_LINK = ("lien", "dec", "aff", "percu")
LIGNE_LINK = re.compile(r"GlassesLink:\s*(link\s+seq\s+\S.*)")
POSTE_LINK = re.compile(r"(seq|lien|dec|aff|percu)\s+(\d+|—)")
LIGNE_RESUME_LINK = re.compile(r"GlassesLink e2e p50/p95/p99 \S+.*drops (\d+)\+(\d+)")
CIBLE_P95_LINK_US = 100_000
WARN_P95_LINK_US = 70_000
CRIT_P99_LINK_US = 150_000
WARMUP_DEFAUT = 5


def centile(tri, pourcent):
    """Centile rang-proche : trié croissant, rang `ceil(q*n)` (1-based)."""
    rang = (pourcent * len(tri) + 99) // 100
    return tri[min(max(rang, 1), len(tri)) - 1]


def parse_ligne_link(brute):
    """Une ligne logcat -> dict poste->µs (postes `—` absents) ou None."""
    trouve = LIGNE_LINK.search(brute)
    if not trouve:
        return None
    mesures = {}
    for nom, valeur in POSTE_LINK.findall(trouve.group(1)):
        if valeur != "—":
            mesures[nom] = int(valeur)
    return mesures if "seq" in mesures else None


def parse_texte_link(texte, skip=WARMUP_DEFAUT):
    """Toutes les frames lunettes, moins les `skip` premières (warmup)."""
    frames = [m for ligne in texte.splitlines() if (m := parse_ligne_link(ligne))]
    return frames[skip:] if skip > 0 else frames


def resume_link(valeurs):
    """(n, moyenne, p50, p95, p99, max) d'une série non vide (µs)."""
    tri = sorted(valeurs)
    return (len(tri), sum(tri) / len(tri), centile(tri, 50),
            centile(tri, 95), centile(tri, 99), tri[-1])


def pertes_link(texte):
    """(mesurées, abandonnées) lues sur le dernier résumé `drops F+D`, ou None."""
    vues = None
    for ligne in texte.splitlines():
        trouve = LIGNE_RESUME_LINK.search(ligne)
        if trouve:
            vues = (int(trouve.group(1)), int(trouve.group(2)))
    return vues


def verdict_link(frames, texte):
    """(lignes, ok) : tableau par poste (µs) + verdict B2 sur `p95(percu)`."""
    lignes = ["poste    n  moyenne   p50   p95   p99   max  (µs)"]
    ok, p95, p99 = True, None, None
    for poste in POSTES_LINK:
        valeurs = [f[poste] for f in frames if poste in f]
        if not valeurs:
            lignes.append(f"{poste:7} 0  —")
            continue
        n, moyenne, c50, c95, c99, maxi = resume_link(valeurs)
        lignes.append(f"{poste:7} {n:3} {moyenne:7.1f} {c50:5} {c95:5} {c99:5} {maxi:5}")
        if poste == "percu":
            p95, p99 = c95, c99
    if p95 is None:
        return lignes + ["verdict : NON TENU (aucun perçu mesuré)"], False
    ok = p95 < CIBLE_P95_LINK_US
    lignes.append(f"cible B2 : p95(percu) < 100 ms ({CIBLE_P95_LINK_US} µs)")
    if p95 > WARN_P95_LINK_US:
        lignes.append(f"alerte : p95 {p95 / 1000:.1f} ms > 70 ms (warn recette §3)")
    if p99 is not None and p99 > CRIT_P99_LINK_US:
        lignes.append(f"alerte : p99 {p99 / 1000:.1f} ms > 150 ms (critique recette §3)")
    vues = pertes_link(texte)
    if vues is not None:
        mesurees, abandonnees = vues
        total = mesurees + abandonnees
        taux = 100.0 * abandonnees / total if total else 0.0
        lignes.append(f"pertes : {abandonnees}/{total} = {taux:.1f} % "
                      "(warn > 5 %, critique > 20 %)")
        if taux > 20.0:
            lignes.append("alerte : pertes critiques (> 20 %)")
            ok = False
    lignes.append(f"verdict : {'TENU' if ok else 'NON TENU'} "
                  f"(p95 {p95 / 1000:.1f} ms, p99 {p99 / 1000:.1f} ms)")
    return lignes, ok


def main_link(texte, skip):
    """Affiche le tableau lunettes + verdict B2 ; 0/1/2 (2 = rien à lire)."""
    frames = parse_texte_link(texte, skip)
    if not frames:
        print("aucune frame GlassesLink link seq (flux éteint ? tag filtré ?)",
              file=sys.stderr)
        return 2
    lignes, ok = verdict_link(frames, texte)
    print("\n".join(lignes))
    return 0 if ok else 1


def selftest():
    """Formes link, warmup, verdict p95, alertes, pertes, CLI : 16 assertions."""
    import contextlib
    import io

    vide, plein = io.StringIO(), io.StringIO()
    lien = "10-31 12:00:00.000 1 2 I GlassesLink: link seq 12 · lien 30123 · dec 8421 · aff 7110 · percu 45654"
    assert parse_ligne_link(lien) == {"seq": 12, "lien": 30123, "dec": 8421,
                                      "aff": 7110, "percu": 45654}
    assert parse_ligne_link("I GlassesLink: link seq 3 · lien — · dec 8421 · aff 7110 · percu —") == {
        "seq": 3, "dec": 8421, "aff": 7110}
    assert parse_ligne_link("I ISEE_LAT: latency 12 · pixel 3") is None
    bloc_link = "\n".join(f"I GlassesLink: link seq {i} · lien 1 · dec 1 · aff 1 · percu {v}"
                          for i, v in enumerate((900_000, 800_000, 10_000, 20_000, 30_000)))
    assert [f["percu"] for f in parse_texte_link(bloc_link, skip=2)] == [10_000, 20_000, 30_000]
    assert resume_link([1000, 2000, 3000, 4000, 5000]) == (5, 3000.0, 3000, 5000, 5000, 5000)
    tenu, ok = verdict_link([{"seq": i, "percu": 50_000} for i in range(10)], "")
    rate, ko = verdict_link([{"seq": i, "percu": 120_000} for i in range(10)], "")
    assert ok and not ko and any("TENU" in l and "NON" not in l for l in tenu)
    assert any("NON TENU" in l for l in rate)
    assert any("p95 120.0 ms > 70 ms" in l for l in rate)
    melange = ([{"seq": i, "percu": 10_000} for i in range(95)]
               + [{"seq": 100 + i, "percu": 200_000} for i in range(5)])
    critique, ok_crit = verdict_link(melange, "")
    assert ok_crit and any("p99 200.0 ms > 150 ms" in l for l in critique)
    resume1 = "I GlassesLink: GlassesLink e2e p50/p95/p99 1/2/3 us (lien p95 1 us) drops 10+2"
    resume2 = "I GlassesLink: GlassesLink e2e p50/p95/p99 1/2/3 us (lien p95 1 us) drops 100+5"
    assert pertes_link(resume1 + "\n" + resume2 + "\n") == (100, 5)
    assert pertes_link("I GlassesLink: link seq 1 · lien 1 · dec 1 · aff 1 · percu 1\n") is None
    lourd = ("I GlassesLink: GlassesLink e2e p50/p95/p99 1/2/3 us (lien p95 1 us) drops 70+30\n")
    perdu, ok_pertes = verdict_link([{"seq": i, "percu": 50_000} for i in range(10)], lourd)
    assert not ok_pertes and any("pertes : 30/100 = 30.0 %" in l for l in perdu)
    assert any("pertes critiques" in l for l in perdu)
    with contextlib.redirect_stdout(vide), contextlib.redirect_stderr(vide):
        assert main_link("I GlassesLink: rien\n", 0) == 2
    with contextlib.redirect_stdout(plein), contextlib.redirect_stderr(plein):
        assert main_link(lien + "\n", 0) == 0
        assert main_link("I GlassesLink: link seq 1 · lien 1 · dec 1 · aff 1 · percu 500000\n", 0) == 1
    print("OK   16 assertions parse_glasses_link")
    return 0


def main(argv):
    """CLI : [fichier] [--skip K] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    skip, chemin = WARMUP_DEFAUT, None
    args = list(argv)
    while "--skip" in args:
        i = args.index("--skip")
        skip = int(args[i + 1])
        del args[i:i + 2]
    if args and not args[0].startswith("-"):
        chemin = args.pop(0)
    if args or (chemin is None and sys.stdin.isatty()):
        print("usage : parse_glasses_link.py [fichier] [--skip K]", file=sys.stderr)
        return 2
    texte = open(chemin, encoding="utf-8").read() if chemin else sys.stdin.read()
    return main_link(texte, skip)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
