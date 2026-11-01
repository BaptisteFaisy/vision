#!/usr/bin/env python3
"""latence-ab-compare.py — compare les deux dumps ISEE_LAT d'un A/B M1.

Entrée : deux dumps `adb logcat -s ISEE_LAT` produits par `scripts/latence-ab.sh`
(une jambe chacun, même vidéo, même N, même warmup). Les chiffres viennent de
`scripts/parse_isee_lat.py` (importé, jamais recopié) : mêmes centiles
rang-proche, même warmup.

Validité : chaque dump ne doit porter qu'une seule jambe (lignes `leg binary` /
`leg text`, voir `TimingsText.leg`) — un dump mixte (repli texte en pleine jambe
binaire) refuse la comparaison (code 1). Sans aucun marqueur (vieil APK), la
comparaison passe avec un avertissement (jambes attribuées par l'ordre des
arguments). Les postes `capt`, `mask` et `pixel` sont des contrôles : le binaire
ne les touche pas, une dérive > 10 ms de moyenne signale des campagnes non
comparables (avertissement, code 0 — la synthèse tranche).

Usage :
    python3 scripts/latence-ab-compare.py [--skip K] [--min-frames N] dump-text.txt dump-binary.txt
    python3 scripts/latence-ab-compare.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun appareil.
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parse_isee_lat as P

MARQUEUR = re.compile(r"\bleg (binary|text)\b")
CONTROLES = ("capt", "mask", "pixel")
DERIVE_CONTROLE_MS = 10.0
MINI_DEFAUT = 500


def jambes(texte):
    """Jambes distinctes annoncées dans un dump (`binary`/`text`), triées."""
    return sorted(set(MARQUEUR.findall(texte)))


def colonne(frames, poste):
    """(n, moyenne, p50, p95, max) d'un poste, ou None si jamais mesuré."""
    valeurs = [f[poste] for f in frames if poste in f]
    return P.resume(valeurs) if valeurs else None


def ligne_poste(poste, texte, binaire):
    """Une ligne du tableau : poste + colonnes text/binary + deltas (binaire-texte)."""
    t, b = colonne(texte, poste), colonne(binaire, poste)
    if t is None or b is None:
        return f"{poste:7} —"
    dmoy, dp50 = b[1] - t[1], b[2] - t[2]
    return (f"{poste:7} {t[0]:4} {t[1]:7.1f} {t[2]:5} {t[3]:5} {t[4]:5} |"
            f" {b[0]:4} {b[1]:7.1f} {b[2]:5} {b[3]:5} {b[4]:5} |"
            f" {dmoy:+6.1f} {dp50:+5}")


def tableau(texte, binaire):
    """Tableau comparatif, un poste par ligne (ordre du trajet)."""
    lignes = [("poste      text  n     moy   p50   p95   max |"
               " binary  n     moy   p50   p95   max |  Δmoy  Δp50")]
    return lignes + [ligne_poste(p, texte, binaire) for p in P.POSTES]


def derives(texte, binaire):
    """Avertissements de dérive des postes de contrôle (capt/mask/pixel)."""
    avertis = []
    for poste in CONTROLES:
        t, b = colonne(texte, poste), colonne(binaire, poste)
        if t is None or b is None:
            continue
        if abs(b[1] - t[1]) > DERIVE_CONTROLE_MS:
            avertis.append(f"CONTROLE {poste} : Δmoy {b[1] - t[1]:+.1f} ms "
                           f"(text {t[1]:.1f}, binary {b[1]:.1f}) — campagnes à vérifier")
    return avertis


def usage():
    return ("usage : latence-ab-compare.py [--skip K] [--min-frames N] "
            "dump-text.txt dump-binary.txt")


class UsageError(Exception):
    """Arguments inutilisables : le détail suit l'usage sur stderr."""


def lit(chemin):
    """Contenu UTF-8 d'un dump (gestionnaire de contexte, jamais de fuite)."""
    with open(chemin, encoding="utf-8") as f:
        return f.read()


def ecrit(chemin, texte):
    """Écrit un dump UTF-8 (selftest ; gestionnaire de contexte)."""
    with open(chemin, "w", encoding="utf-8") as f:
        f.write(texte)


def parse_args(argv):
    """(skip, mini, [dump-text, dump-binary]) ou lève [UsageError]."""
    skip, mini, args = P.WARMUP_DEFAUT, MINI_DEFAUT, list(argv)
    for flag in ("--skip", "--min-frames"):
        while flag in args:
            i = args.index(flag)
            try:
                valeur = int(args[i + 1])
            except (IndexError, ValueError):
                raise UsageError(f"{flag} attend un entier")
            del args[i:i + 2]
            if flag == "--skip":
                skip = valeur
            else:
                mini = valeur
    if len(args) != 2 or any(c.startswith("-") for c in args):
        raise UsageError("deux dumps attendus")
    return skip, mini, args


def main(argv):
    """CLI : compare deux dumps (code 0) ou refuse (code 1/2)."""
    if "--selftest" in argv:
        return selftest()
    try:
        skip, mini, chemins = parse_args(argv)
    except UsageError as e:
        print(f"{usage()} ({e})", file=sys.stderr)
        return 2
    try:
        bruts = [lit(c) for c in chemins]
    except OSError as e:
        print(f"dump illisible : {e}", file=sys.stderr)
        return 2
    for brut, attendu in zip(bruts, ("text", "binary")):
        marqueurs = jambes(brut)
        if not marqueurs:
            print(f"ATTENTION : {attendu} sans marqueur 'leg' "
                  f"(jambe attribuée par l'ordre des arguments)", file=sys.stderr)
        elif marqueurs != [attendu]:
            print(f"dump {attendu} inutilisable : jambes {marqueurs} "
                  f"(attendu : ['{attendu}'])", file=sys.stderr)
            return 1
    frames = [P.parse_texte(b, skip) for b in bruts]
    for f, attendu in zip(frames, ("text", "binary")):
        if len(f) < mini:
            print(f"dump {attendu} trop court : {len(f)} frames mesurées "
                  f"(skip {skip}, minimum {mini})", file=sys.stderr)
            return 1
    print(f"text   : {chemins[0]} ({len(frames[0])} frames, skip {skip})")
    print(f"binary : {chemins[1]} ({len(frames[1])} frames, skip {skip})")
    print("\n".join(tableau(frames[0], frames[1])))
    for nom, f in (("text", frames[0]), ("binary", frames[1])):
        lignes, _ = P.verdict(f)
        tenu = next(l for l in lignes if l.startswith("verdict :"))
        print(f"--- {nom} : {' '.join(tenu.split())}")
    for averti in derives(frames[0], frames[1]):
        print(f"ATTENTION : {averti}")
    return 0


def fabrique_dumps():
    """Deux dumps synthétiques (jambes marquées) aux chiffres connus."""
    entete = "11-01 17:00:00.000  1234  1234 I ISEE_LAT: "
    text = [entete + "leg text"]
    binary = [entete + "leg binary"]
    for i in range(12):
        text.append(entete + f"latency {300 + i} · capt 20 · up 30 · srv 90 · "
                            f"rtt 150 · mask 37 · pixel 8")
        binary.append(entete + f"latency {290 + i} · capt 20 · up 29 · srv 89 · "
                              f"rtt 148 · mask 37 · pixel 8")
    return "\n".join(text) + "\n", "\n".join(binary) + "\n"


def selftest():
    """Assertions : unités (tableau, jambes) puis CLI (refus, nominal)."""
    n = 0

    def check(cond, quoi):
        nonlocal n
        assert cond, quoi
        n += 1
    selftest_unites(check)
    selftest_mains(check)
    print(f"OK   {n} assertions latence-ab-compare")
    return 0


def selftest_unites(check):
    """Maths du tableau, lecture des jambes, dérives de contrôle."""
    text, binary = fabrique_dumps()
    check(jambes(text) == ["text"], "jambe text lue")
    check(jambes(binary) == ["binary"], "jambe binary lue")
    check(jambes("rien ici") == [], "sans marqueur = []")
    check(jambes("leg binary puis leg text") == ["binary", "text"], "mixte = 2")
    ft = P.parse_texte(text, 2)
    fb = P.parse_texte(binary, 2)
    check((len(ft), len(fb)) == (10, 10), "skip retiré des deux côtés")
    check(colonne(ft, "up") is not None and colonne(ft, "up")[1] == 30.0, "moy up text")
    ligne = ligne_poste("up", ft, fb)
    check("30.0" in ligne and "29.0" in ligne, "colonnes up")
    check("-1.0" in ligne, "delta up binaire-texte")
    check(ligne_poste("up", [], fb) == "up      —", "poste absent = —")
    tab = tableau(ft, fb)
    check(len(tab) == 1 + len(P.POSTES), "une ligne par poste + en-tête")
    check(tab[0].startswith("poste"), "en-tête du tableau")
    check(derives(ft, fb) == [], "contrôles stables = silence")
    fb2 = P.parse_texte(binary.replace("capt 20", "capt 45"), 2)
    check(len(derives(ft, fb2)) == 1 and "capt" in derives(ft, fb2)[0], "dérive capt signalée")


def selftest_mains(check):
    """CLI sur dumps temporaires : nominal, mixte, court, sans-marqueur, usage."""
    import io
    import tempfile
    from contextlib import redirect_stdout
    text, binary = fabrique_dumps()
    with tempfile.TemporaryDirectory() as tmp:
        ct = os.path.join(tmp, "t.txt")
        cb = os.path.join(tmp, "b.txt")
        ecrit(ct, text)
        ecrit(cb, binary)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = main(["--skip", "2", "--min-frames", "10", ct, cb])
        check(rc == 0, "comparaison nominale = 0")
        check("Δmoy" in buf.getvalue(), "tableau imprimé")
        ecrit(cb, binary + "ISEE_LAT: leg text\n")
        with redirect_stdout(io.StringIO()):
            check(main(["--skip", "2", ct, cb]) == 1, "dump mixte refusé")
        ecrit(cb, binary)
        with redirect_stdout(io.StringIO()):
            check(main(["--skip", "2", "--min-frames", "11", ct, cb]) == 1, "dump court refusé")
        ecrit(cb, binary.replace("leg binary", "x"))
        with redirect_stdout(io.StringIO()):
            check(main(["--skip", "2", "--min-frames", "10", ct, cb]) == 0, "sans marqueur = 0 + warning")
        with redirect_stdout(io.StringIO()):
            check(main([ct]) == 2, "un seul dump = usage")
            check(main(["--skip", "x", ct, cb]) == 2, "--skip non numérique = usage")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
