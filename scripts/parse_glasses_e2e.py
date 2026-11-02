#!/usr/bin/env python3
"""parse_glasses_e2e.py — verdict E2E lunettes → pixel SAM sur une campagne jointe.

Entrée : un dump `adb logcat -s ISEE_LAT,GlassesLink` (fichier ou stdin) avec les
deux séries, jointes sur `seq` (la sonde écrit `seq` depuis SPEC-1/SPEC-2) :

    ISEE_LAT: latency 148 · capt 42 · ... · pixel 8 · seq 12
    GlassesLink: link seq 12 · lien 30123 · dec 8421 · aff 7110 · percu 45654

E2E(seq) = lien/1000 + latency : le jitter radio (`lien`, µs) plus
l'arrivée → pixel SAM (`latency`, ms) — sans double-compte (`dec`/`aff` du
lien sont la vue live, hors chemin SAM). E2E reste une borne basse (biais
radio ~+30 ms, transit absolu inconnu).

Sortie : n jointes, moyenne, p50, p95, max, puis le verdict (`TENU` si
`p50 <= 200` et `p95 <= 250` et `max <= 400`, warmup 5, N >= 100 après
warmup). Code 0 si tenu, 1 sinon, 2 si injouable (< 100 jointes).

Usage :
    python3 scripts/parse_glasses_e2e.py joint.txt
    adb -s <tel> logcat -d -s ISEE_LAT,GlassesLink | python3 scripts/parse_glasses_e2e.py
    python3 scripts/parse_glasses_e2e.py joint.txt --skip 5
    python3 scripts/parse_glasses_e2e.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import re
import sys

from parse_isee_lat import centile, extrait_options, lit_entree

LIGNE_LAT = re.compile(r"ISEE_LAT:\s*latency\s+(\d+|—).*?\bseq\s+(\d+)")
LIGNE_LINK = re.compile(r"GlassesLink:\s*link seq (\d+) · lien (\d+|—)")
SEUIL_P50 = 200
SEUIL_P95 = 250
SEUIL_MAX = 400
WARMUP_DEFAUT = 5
N_MIN = 100


def parse_lat(brute):
    """Une ligne logcat -> (seq, latency_ms) ou None (jetée, sans seq, autre tag)."""
    trouve = LIGNE_LAT.search(brute)
    if not trouve or trouve.group(1) == "—":
        return None
    return int(trouve.group(2)), int(trouve.group(1))


def parse_link(brute):
    """Une ligne logcat -> (seq, lien_us) ou None (non mesuré, autre tag)."""
    trouve = LIGNE_LINK.search(brute)
    if not trouve or trouve.group(2) == "—":
        return None
    return int(trouve.group(1)), int(trouve.group(2))


def joint(texte, skip=WARMUP_DEFAUT):
    """E2E jointes par seq (ordre croissant, `skip` premières au warmup).

    Rend (e2e_ms, n_lat, n_link) : les E2E en ms (floats), puis les comptes
    bruts des deux séries — l'écart aux jointes dit les trous de chaque côté.
    """
    lat, link = {}, {}
    for ligne in texte.splitlines():
        mesure = parse_lat(ligne)
        if mesure:
            lat[mesure[0]] = mesure[1]
        mesure = parse_link(ligne)
        if mesure:
            link[mesure[0]] = mesure[1]
    seqs = sorted(set(lat) & set(link))
    e2e = [link[s] / 1000 + lat[s] for s in seqs]
    return (e2e[skip:] if skip > 0 else e2e), len(lat), len(link)


def verdict(e2e):
    """(lignes, ok) : comptes, p50/p95/max et verdict sur la série jointe."""
    tri = sorted(e2e)
    moyenne = sum(tri) / len(tri)
    p50, p95, maxi = centile(tri, 50), centile(tri, 95), tri[-1]
    controles = [(p50 <= SEUIL_P50, f"p50 {p50:.1f} <= {SEUIL_P50}"),
                 (p95 <= SEUIL_P95, f"p95 {p95:.1f} <= {SEUIL_P95}"),
                 (maxi <= SEUIL_MAX, f"max {maxi:.1f} <= {SEUIL_MAX}")]
    tenu = all(passe for passe, _ in controles)
    lignes = [f"e2e jointes {len(tri)} · moyenne {moyenne:.1f} ms",
              f"p50 {p50:.1f} · p95 {p95:.1f} · max {maxi:.1f} ms",
              f"verdict : {'TENU' if tenu else 'NON TENU'} "
              f"({'; '.join(texte for _, texte in controles)})"]
    return lignes, tenu


def selftest():
    """Assertions du parseur : trois blocs, dans le module compagnon."""
    import parse_glasses_e2e_selftest
    return parse_glasses_e2e_selftest.selftest()


def main(argv):
    """CLI : [fichier] [--skip K] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    options, args = extrait_options(argv)
    chemin = args.pop(0) if args and not args[0].startswith("-") else None
    if args or (chemin is None and sys.stdin.isatty()):
        print("usage : parse_glasses_e2e.py [fichier] [--skip K]", file=sys.stderr)
        return 2
    e2e, n_lat, n_link = joint(lit_entree(chemin), options["--skip"])
    print(f"séries : ISEE_LAT {n_lat} · GlassesLink {n_link} · jointes {len(e2e)} "
          f"(warmup {options['--skip']})")
    if len(e2e) < N_MIN:
        print(f"injouable : {len(e2e)} jointes < {N_MIN} (campagne trop courte ?)",
              file=sys.stderr)
        return 2
    lignes, ok = verdict(e2e)
    print("\n".join(lignes))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
