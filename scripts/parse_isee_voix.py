#!/usr/bin/env python3
"""parse_isee_voix.py — EXPECT sans PLAY ni DONE par phrase, sur un dump ISEE_VOIX.

Entrée : un dump `adb logcat -s ISEE_VOIX` (fichier ou stdin), lignes
écrites par `SpeechLog` (`app/isee/`, étape 1a) — une ligne par étape
de vie d'une phrase parlée :

    EXPECT voie=FENCE phrase=[Stop.]
    PLAY source=clip detail=[Stop.]
    DONE phrase=[Stop.]
    FAIL ou=bus cause=[écrasé]

Sortie : par phrase, le nombre d'EXPECT, de PLAY, de DONE et d'orphelins
(EXPECT sans PLAY ni DONE : `max(0, EXPECT - PLAY - DONE)` — un PLAY ou
un DONE couvre un EXPECT), puis le verdict (`aucun orphelin` par défaut).
Code 0 si tenu, 1 sinon, 2 si aucun EXPECT (tag filtré ? voix muette ?).

Appariement : DONE couvre à phrase égale ; PLAY couvre à détail égal,
ou quand la phrase est contenue dans le détail (`detail` est la phrase
jouée, la tolérance couvre un détail verbeux). Les FAIL sont informatifs
(compteur par lieu, jamais dans le verdict : un raté expliqué reste un
EXPECT sans PLAY ni DONE).

Usage :
    python3 scripts/parse_isee_voix.py voix.txt
    adb -s <tel> logcat -d -s ISEE_VOIX | python3 scripts/parse_isee_voix.py
    python3 scripts/parse_isee_voix.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import re
import sys

LIGNE_EXPECT = re.compile(r"ISEE_VOIX:\s*EXPECT\s+voie=(\S+)\s+phrase=\[(.*)\]\s*$")
LIGNE_PLAY = re.compile(r"ISEE_VOIX:\s*PLAY\s+source=(\S+)\s+detail=\[(.*)\]\s*$")
LIGNE_DONE = re.compile(r"ISEE_VOIX:\s*DONE\s+phrase=\[(.*)\]\s*$")
LIGNE_FAIL = re.compile(r"ISEE_VOIX:\s*FAIL\s+ou=(\S+)\s+cause=\[(.*)\]\s*$")


def parse_ligne(brute):
    """Une ligne logcat -> (mot-clé, champ1, champ2) ou None (bruit)."""
    trouve = LIGNE_EXPECT.search(brute)
    if trouve:
        return ("EXPECT", trouve.group(1), trouve.group(2))
    trouve = LIGNE_PLAY.search(brute)
    if trouve:
        return ("PLAY", trouve.group(1), trouve.group(2))
    trouve = LIGNE_DONE.search(brute)
    if trouve:
        return ("DONE", "", trouve.group(1))
    trouve = LIGNE_FAIL.search(brute)
    if trouve:
        return ("FAIL", trouve.group(1), trouve.group(2))
    return None


def parse_texte(texte):
    """Toutes les lignes ISEE_VOIX : (expects, details_play, dones, fails).

    `expects` = phrases attendues (doublons gardés : une attente = une
    parole) ; `details_play` = détails des PLAY ; `dones` = phrases
    terminées ; `fails` = lieux des FAIL (informatifs).
    """
    expects, plays, dones, fails = [], [], [], []
    for ligne in texte.splitlines():
        trouve = parse_ligne(ligne)
        if trouve is None:
            continue
        mot, premier, second = trouve
        if mot == "EXPECT":
            expects.append(second)
        elif mot == "PLAY":
            plays.append(second)
        elif mot == "DONE":
            dones.append(second)
        else:
            fails.append(premier)
    return expects, plays, dones, fails


def couvre_play(phrase, details):
    """PLAY couvrant [phrase] : détail égal, ou phrase contenue (non vide)."""
    if not phrase:
        return 0
    return sum(1 for detail in details if detail == phrase or phrase in detail)


def orphelins_par_phrase(expects, plays, dones):
    """Par phrase : (EXPECT, PLAY, DONE, orphelins), trié par orphelins décroissants."""
    tableau = []
    for phrase in sorted(set(expects)):
        attendus = expects.count(phrase)
        joues = couvre_play(phrase, plays)
        termines = dones.count(phrase)
        tableau.append((phrase, attendus, joues, termines,
                        max(0, attendus - joues - termines)))
    return sorted(tableau, key=lambda rang: (-rang[4], rang[0]))


def verdict(expects, plays, dones, fails):
    """(lignes, ok) : tableau par phrase, FAIL informatifs, verdict orphelins."""
    lignes = ["phrase  EXPECT  PLAY  DONE  orphelins"]
    total = 0
    for phrase, attendus, joues, termines, orphelins in orphelins_par_phrase(expects, plays, dones):
        lignes.append(f"[{phrase}]  {attendus:6} {joues:5} {termines:5}  {orphelins:9}")
        total += orphelins
    if fails:
        lieux = sorted(set(fails))
        lignes.append(f"ratés {len(fails)} (informatifs) : " +
                      ", ".join(f"{lieu}×{fails.count(lieu)}" for lieu in lieux))
    lignes.append(f"verdict : {'TENU' if total == 0 else 'NON TENU'} "
                  f"({total} orphelin(s) sur {len(expects)} EXPECT)")
    return lignes, total == 0


def lit_entree(chemin):
    """Contenu du dump : fichier UTF-8, ou stdin quand aucun chemin n'est donné."""
    if chemin is None:
        return sys.stdin.read()
    with open(chemin, encoding="utf-8") as fichier:
        return fichier.read()


def selftest():
    """Cycle complet, orphelin, détail verbeux, bruit : 0 si tout passe, 1 sinon."""
    tenu = """I ISEE_VOIX: EXPECT voie=FENCE phrase=[Stop.]
I ISEE_VOIX: PLAY source=clip detail=[Stop.]
I ISEE_VOIX: DONE phrase=[Stop.]
I sam3vision: bruit hors tag
ISEE_VOIX: EXPECT voie=repli phrase=[Gauche.]
ISEE_VOIX: PLAY source=synthèse detail=[joue : Gauche.]
ISEE_VOIX: FAIL ou=bus cause=[écrasé]
"""
    expects, plays, dones, fails = parse_texte(tenu)
    assert expects == ["Stop.", "Gauche."], expects
    assert plays == ["Stop.", "joue : Gauche."], plays
    assert dones == ["Stop."], dones
    assert fails == ["bus"], fails
    assert couvre_play("Gauche.", plays) == 1
    assert couvre_play("absente.", plays) == 0
    lignes, ok = verdict(expects, plays, dones, fails)
    assert ok, lignes
    perdu = "ISEE_VOIX: EXPECT voie=FENCE phrase=[Stop.]\n"
    lignes, ok = verdict(*parse_texte(perdu)[:3], [])
    assert not ok and "1 orphelin(s)" in lignes[-1], lignes
    assert orphelins_par_phrase(["a", "a"], ["a"], []) == [("a", 2, 1, 0, 1)]
    assert parse_ligne("ISEE_VOIX: EXPECT voie=FENCE") is None
    print("selftest voix : 10 assertions OK")
    return 0


def main(argv):
    """CLI : [fichier] | --selftest (stdin quand aucun fichier n'est donné)."""
    if "--selftest" in argv:
        return selftest()
    chemin = argv[0] if argv and not argv[0].startswith("-") else None
    if [a for a in argv if a != chemin]:
        print("usage : parse_isee_voix.py [fichier] | --selftest", file=sys.stderr)
        return 2
    if chemin is None and sys.stdin.isatty():
        print("usage : parse_isee_voix.py [fichier] | --selftest", file=sys.stderr)
        return 2
    expects, plays, dones, fails = parse_texte(lit_entree(chemin))
    if not expects:
        print("aucun EXPECT ISEE_VOIX (tag filtré ? voix muette ?)", file=sys.stderr)
        return 2
    lignes, ok = verdict(expects, plays, dones, fails)
    print("\n".join(lignes))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
