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

Les deux côtés échantillonnent la même séquence indépendamment (lien : 1
frame affichée sur 5 ; sonde : les frames envoyées à SAM) : les seq exactes
communes sont rares (37/180 s en recette v18). Le secours apparie chaque
`latency` sans lien exact au `lien` de la seq loggée la plus proche, à
`±voisin` près (défaut 3, ~200 ms à 15 i/s) : le gros morceau (latency SAM)
reste exact, seul le petit terme radio est interpolé (ordre 0). La preuve
passe d'abord : si les exactes atteignent N après warmup, le verdict les
juge seules ; sinon il juge la fusion, documentée (part d'exactes, Δmax) —
`--voisin 0` retrouve le strict d'avant, diagnostic en plus.

Sortie : n jointes, moyenne, p50, p95, max, puis le verdict (`TENU` si
`p50 <= 200` et `p95 <= 250` et `max <= 400`, warmup 5, N >= 100 après
warmup). La ligne `seq :` (spans, pas médians, recouvrement, doublons) dit
pourquoi les jointes manquent quand elles manquent. Code 0 si tenu,
1 sinon, 2 si injouable (< 100 jointes, stats affichées quand même).

Usage :
    python3 scripts/parse_glasses_e2e.py joint.txt
    adb -s <tel> logcat -d -s ISEE_LAT,GlassesLink | python3 scripts/parse_glasses_e2e.py
    python3 scripts/parse_glasses_e2e.py joint.txt --skip 5 --voisin 2
    python3 scripts/parse_glasses_e2e.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import bisect
import re
import sys

from parse_isee_lat import centile, extrait_options, lit_entree

LIGNE_LAT = re.compile(r"ISEE_LAT:\s*latency\s+(\d+|—).*?\bseq\s+(\d+)")
LIGNE_LINK = re.compile(r"GlassesLink:\s*link seq (\d+) · lien (\d+|—)")
SEUIL_P50 = 200
SEUIL_P95 = 250
SEUIL_MAX = 400
WARMUP_DEFAUT = 5
VOISIN_DEFAUT = 3
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


def series(texte):
    """Les deux séries : (lat, link, doublons_lat, doublons_link).

    `lat`/`link` = dict seq -> mesure (dernière ligne gagne comme avant) ;
    les doublons (même seq loggée deux fois) sont comptés pour le diagnostic.
    """
    lat, link, doubles = {}, {}, [0, 0]
    for ligne in texte.splitlines():
        mesure = parse_lat(ligne)
        if mesure:
            doubles[0] += mesure[0] in lat
            lat[mesure[0]] = mesure[1]
        mesure = parse_link(ligne)
        if mesure:
            doubles[1] += mesure[0] in link
            link[mesure[0]] = mesure[1]
    return lat, link, doubles[0], doubles[1]


def joint(texte, skip=WARMUP_DEFAUT):
    """E2E jointes par seq (ordre croissant, `skip` premières au warmup).

    Rend (e2e_ms, n_lat, n_link) : les E2E en ms (floats), puis les comptes
    bruts des deux séries — l'écart aux jointes dit les trous de chaque côté.
    Exactes seules : la preuve stricte, inchangée.
    """
    lat, link, _, _ = series(texte)
    seqs = sorted(set(lat) & set(link))
    e2e = [link[s] / 1000 + lat[s] for s in seqs]
    return (e2e[skip:] if skip > 0 else e2e), len(lat), len(link)


def voisine(seq, triees, tolerance):
    """Seq loggée la plus proche de `seq` à `±tolerance`, ou None.

    Ex æquo (|Δ| égal des deux côtés) : la plus petite seq (causalité —
    le jitter déjà observé). Un lien peut apparier plusieurs latences
    (interpolation d'ordre 0, sans invention de valeur).
    """
    if not triees or tolerance <= 0:
        return None
    i = bisect.bisect_left(triees, seq)
    candidats = [s for s in (triees[i - 1] if i > 0 else None,
                             triees[i] if i < len(triees) else None) if s is not None]
    proches = [(abs(s - seq), s) for s in candidats if abs(s - seq) <= tolerance]
    return min(proches)[1] if proches else None


def appariees(lat, link, tolerance=VOISIN_DEFAUT):
    """E2E de secours : [(seq_lat, e2e_ms, delta)] pour les lat sans lien exact."""
    triees = sorted(link)
    paires = []
    for seq in sorted(set(lat) - set(link)):
        proche = voisine(seq, triees, tolerance)
        if proche is not None:
            paires.append((seq, link[proche] / 1000 + lat[seq], abs(proche - seq)))
    return paires


def pas_median(seqs):
    """Pas médian entre seqs consécutives (cadence effective), ou None (< 2 seqs)."""
    if len(seqs) < 2:
        return None
    triees = sorted(seqs)
    return centile(sorted(b - a for a, b in zip(triees, triees[1:])), 50)


def plage(seqs):
    """`min–max` des seqs, ou `—` sans série (jamais de span inventé)."""
    return f"{min(seqs)}–{max(seqs)}" if seqs else "—"


def recouvrement(lat, link):
    """Span commun aux deux séries, ou `aucun` (séries disjointes ou vides)."""
    if not lat or not link:
        return "aucun"
    debut, fin = max(min(lat), min(link)), min(max(lat), max(link))
    return f"{debut}–{fin}" if debut <= fin else "aucun"


def ligne_seq(lat, link, doubles_lat, doubles_link):
    """Ligne `seq :` : spans, pas médians, recouvrement, doublons."""
    pas_lat, pas_link = pas_median(lat), pas_median(link)
    return (f"seq : lat {plage(lat)} (pas {pas_lat if pas_lat else '—'}) · "
            f"link {plage(link)} (pas {pas_link if pas_link else '—'}) · "
            f"recouvrement {recouvrement(lat, link)} · "
            f"doublons lat {doubles_lat}, link {doubles_link}")


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


def fusion(lat, link, skip=WARMUP_DEFAUT, voisin=VOISIN_DEFAUT):
    """Union exactes + secours, warmup sur l'union triée par seq.

    Rend (preuve, total, compte) : E2E exactes restantes, E2E fusionnées
    restantes, et (n_exactes, n_secours, delta_max) pour l'affichage.
    `--voisin 0` = exactes seules, comme `joint`.
    """
    exactes = {s: link[s] / 1000 + lat[s] for s in set(lat) & set(link)}
    union = [(s, e, True, 0) for s, e in exactes.items()]
    union += [(s, e, False, d) for s, e, d in appariees(lat, link, voisin)]
    union = sorted(union)
    union = union[skip:] if skip > 0 else union
    preuve = [e for _, e, exacte, _ in union if exacte]
    deltas = [d for _, _, exacte, d in union if not exacte]
    return preuve, [e for _, e, _, _ in union], (len(preuve),
                                                 len(union) - len(preuve),
                                                 max(deltas) if deltas else 0)


def ligne_series(n_lat, n_link, exactes, secours, dmax, voisin, skip):
    """Ligne `séries :` : comptes bruts, jointes détaillées, warmup."""
    if secours:
        detail = (f"{exactes + secours} (exactes {exactes} + appariées "
                  f"±{voisin} {secours}, Δmax {dmax})")
    else:
        detail = f"{exactes} (100 % exactes, preuve)" if exactes else "0 (aucune)"
    return (f"séries : ISEE_LAT {n_lat} · GlassesLink {n_link} · jointes {detail} "
            f"(warmup {skip})")


def selftest():
    """Assertions du parseur : cinq blocs, dans le module compagnon."""
    import parse_glasses_e2e_selftest
    return parse_glasses_e2e_selftest.selftest()


def extrait_voisin(args):
    """Consomme `--voisin V` (défaut 3) ; (voisin, args restants)."""
    args, voisin = list(args), VOISIN_DEFAUT
    while "--voisin" in args:
        i = args.index("--voisin")
        voisin = int(args[i + 1])
        del args[i:i + 2]
    return voisin, args


def main(argv):
    """CLI : [fichier] [--skip K] [--voisin V] | --selftest."""
    if "--selftest" in argv:
        return selftest()
    voisin, args = extrait_voisin(argv)
    options, reste = extrait_options(args)
    chemin = reste.pop(0) if reste and not reste[0].startswith("-") else None
    if reste or (chemin is None and sys.stdin.isatty()):
        print("usage : parse_glasses_e2e.py [fichier] [--skip K] [--voisin V]",
              file=sys.stderr)
        return 2
    lat, link, doubles_lat, doubles_link = series(lit_entree(chemin))
    preuve, total, (ex, sec, dmax) = fusion(lat, link, options["--skip"], voisin)
    print(ligne_series(len(lat), len(link), ex, sec, dmax, voisin, options["--skip"]))
    print(ligne_seq(lat, link, doubles_lat, doubles_link))
    if len(preuve) >= N_MIN:
        print(f"preuve : 100 % exactes ({len(preuve)} E2E)")
        lignes, ok = verdict(preuve)
        print("\n".join(lignes))
        return 0 if ok else 1
    if len(total) >= N_MIN:
        print(f"secours : {ex} exactes + {sec} appariées ±{voisin} (Δmax {dmax})")
        lignes, ok = verdict(total)
        print("\n".join(lignes))
        return 0 if ok else 1
    print(f"injouable : {len(total)} jointes < {N_MIN} (campagne trop courte ?)",
          file=sys.stderr)
    if total:
        print(f"indicatif ({len(total)} E2E, {ex} exactes) :")
        print("\n".join(verdict(total)[0]))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
