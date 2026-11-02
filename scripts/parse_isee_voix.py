#!/usr/bin/env python3
"""parse_isee_voix.py — orphelins par phrase + verdict COUPE/PROPRE, sur un dump voix.

Entrée : un dump `adb logcat -s ISEE_VOIX,VoiceMute` (fichier ou stdin),
lignes écrites par `SpeechLog` (`app/isee/`, étape 1a) — une ligne par
étape de vie d'une phrase parlée :

    EXPECT voie=FENCE phrase=[Stop.]
    PLAY source=clip detail=[Stop.]
    DONE phrase=[Stop.]
    FAIL ou=bus cause=[écrasé]
    PLAYER play | done | stop | cut
    TTS done | error | driver-false | closed | blank
    bus-parole level=2 texte=[Stop.] ... (tag VoiceMute)
    PAROLE JETEE level=2 texte=[Stop.] ... (tag VoiceMute)

Sortie : par phrase, le nombre d'EXPECT, de PLAY, de DONE et d'orphelins
(EXPECT sans PLAY ni DONE : `max(0, EXPECT - PLAY - DONE)` — un PLAY ou
un DONE couvre un EXPECT), puis le verdict orphelins, puis la section
coupes et le verdict-coupe (`COUPE` si un son a été interrompu, `PROPRE`
sinon — les `stop` à vide sont ignorés). Code 0 si tenu et propre,
1 sinon, 2 si rien de reconnu (tag filtré ? voix muette ?).

Appariement : DONE couvre à phrase égale ; PLAY couvre à détail égal,
ou quand la phrase est contenue dans le détail (`detail` est la phrase
jouée, la tolérance couvre un détail verbeux). Les FAIL sont informatifs
(compteur par lieu, jamais dans le verdict : un raté expliqué reste un
EXPECT sans PLAY ni DONE).

Coupes : `PLAYER cut` prouve un son interrompu (`SpeechPlayer.stop` ne
l'émet que sur lecture en vol ou file en attente) ; `stop` sans `cut`
= stop à vide, ignoré. Seule source TTS : `repli-système` (fin = `TTS
done`, `error` et refus = fin expliquée) ; `clip` et `synthèse` passent
par le lecteur (fin = `PLAYER done`). Les deltas demandent l'horodatage
logcat (`MM-DD HH:MM:SS.mmm`) ; sans lui, l'ordre seul parle (durées
`?`, ni rafale ni rapprochement temporel).
Silences : écarts PLAY→PLAY > 5 s (seuil, micro tenu exclu), max, taux
de parole (PLAY→fin) ; verdict-coupe final : PROPRE = 0 coupe + 0 silence-total.

Usage :
    python3 scripts/parse_isee_voix.py voix.txt
    adb -s <tel> logcat -d -s ISEE_VOIX,VoiceMute | python3 scripts/parse_isee_voix.py
    python3 scripts/parse_isee_voix.py --selftest

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import math
import re
import statistics
import sys

LIGNE_EXPECT = re.compile(r"ISEE_VOIX:\s*EXPECT\s+voie=(\S+)\s+phrase=\[(.*)\]\s*$")
LIGNE_PLAY = re.compile(r"ISEE_VOIX:\s*PLAY\s+source=(\S+)\s+detail=\[(.*)\]\s*$")
LIGNE_DONE = re.compile(r"ISEE_VOIX:\s*DONE\s+phrase=\[(.*)\]\s*$")
LIGNE_FAIL = re.compile(r"ISEE_VOIX:\s*FAIL\s+ou=(\S+)\s+cause=\[(.*)\]\s*$")
LIGNE_PLAYER = re.compile(r"ISEE_VOIX:\s*PLAYER\s+(\w+)\s*$")
LIGNE_TTS = re.compile(r"ISEE_VOIX:\s*TTS\s+(\S+)\s*$")
LIGNE_BUS_PAROLE = re.compile(r"VoiceMute:\s*bus-parole\s+level=(\S+)\s+texte=\[(.*?)\]")
LIGNE_PAROLE_JETEE = re.compile(r"VoiceMute:\s*PAROLE JETEE\s+level=(\S+)\s+texte=\[(.*?)\]")
#: Jet micro tenu : phrase jetée car le user parle (bus) ou transcrit (proximité).
LIGNE_MICRO = re.compile(r"VoiceMute:\s*(?:bus-jet motif=micro-user|prox-jet \S+ motif=parole-user)")
HORODATAGE = re.compile(r"(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})")

#: Seule source PLAY parlée par le moteur TTS (fin = `TTS done`).
SOURCE_TTS = "repli-système"
#: Cuts consécutifs sous ce délai = une rafale.
SEUIL_RAFALE_S = 1.0
#: Deux `bus-parole` même niveau sous ce délai = recoupe à niveau égal.
SEUIL_RAPPROCHES_S = 2.0
#: `PAROLE JETEE` sous ce délai après un cut = coupe + silence (M5).
FENETRE_M5_S = 2.0
SEUIL_SILENCE_S = 5.0  # écart PLAY→PLAY fautif au-delà (voix jamais muette)
#: Mots d'événement qui portent l'analyse coupe (hors orphelins).
MOTS_COUPE = frozenset({"PLAYER", "TTS", "BUS-PAROLE", "PAROLE-JETEE"})
#: Cadences typiques des rafales : 300 ms = mots continus, 1 s = compte, 2 s = verdict.
SUSPECTS_CADENCE = ((0.3, "mots continus"), (1.0, "compte"), (2.0, "verdict"))


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
    trouve = LIGNE_PLAYER.search(brute)
    if trouve:
        return ("PLAYER", trouve.group(1), "")
    trouve = LIGNE_TTS.search(brute)
    if trouve:
        return ("TTS", trouve.group(1), "")
    trouve = LIGNE_BUS_PAROLE.search(brute)
    if trouve:
        return ("BUS-PAROLE", trouve.group(1), trouve.group(2))
    trouve = LIGNE_PAROLE_JETEE.search(brute)
    if trouve:
        return ("PAROLE-JETEE", trouve.group(1), trouve.group(2))
    trouve = LIGNE_MICRO.search(brute)
    if trouve:
        return ("MICRO", "", "")
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
        elif mot == "FAIL":
            fails.append(premier)
        # PLAYER, TTS, BUS-PAROLE, PAROLE-JETEE : analyse coupe, hors orphelins.
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


def horodatage(brute):
    """Secondes du jour lues sur la ligne (`MM-DD HH:MM:SS.mmm`), ou None."""
    trouve = HORODATAGE.search(brute)
    if not trouve:
        return None
    heure, minute, seconde, milli = (int(trouve.group(n)) for n in (3, 4, 5, 6))
    return heure * 3600 + minute * 60 + seconde + milli / 1000


def ecart(avant, apres):
    """`apres - avant` en secondes (wrap minuit), ou None sans horodatage."""
    if avant is None or apres is None:
        return None
    delta = apres - avant
    return delta + 86400 if delta < 0 else delta


def fmt_duree(delta):
    """`0.42s`, ou `?` sans horodatage."""
    return f"{delta:.2f}s" if delta is not None else "?"


def fmt_ts(ts):
    """`HH:MM:SS.mmm` depuis les secondes du jour, ou `?`."""
    return "?" if ts is None else f"{int(ts // 3600):02}:{int(ts // 60) % 60:02}:{ts % 60:06.3f}"


def evenements(texte):
    """Tous les événements reconnus, dans l'ordre : (ts, mot, champ1, champ2)."""
    trouves = []
    for ligne in texte.splitlines():
        trouve = parse_ligne(ligne)
        if trouve is not None:
            trouves.append((horodatage(ligne),) + trouve)
    return trouves


def fini_entre(evs, debut, fin, phrase):
    """Fin normale entre deux positions : `DONE phrase` ou `PLAYER done`."""
    for ts, mot, premier, second in evs[debut + 1:fin]:
        if (mot == "DONE" and second == phrase) or (mot == "PLAYER" and premier == "done"):
            return True
    return False


def coupe_attribuee(evs, position):
    """(coupé, remplaçant, parlé-avant, délai) du cut en [position].

    Coupé = PLAY (hors TTS) le plus récent sans fin entre lui et le cut
    (`""` si fini ou inconnu : chat, intro, hors trace) ; remplaçant =
    premier PLAY derrière le cut (aucun `PLAYER done` entre, sinon la
    lecture coupée n'a pas de successeur direct).
    """
    plays_avant = [i for i in range(position) if evs[i][1] == "PLAY"]
    coupe, parle = "", None
    for i in reversed(plays_avant):
        if evs[i][2] == SOURCE_TTS:
            continue
        if not fini_entre(evs, i, position, evs[i][3]):
            coupe, parle = evs[i][3], ecart(evs[i][0], evs[position][0])
        break
    plays_apres = [i for i in range(position + 1, len(evs)) if evs[i][1] == "PLAY"]
    remplacant, delai = "", None
    if plays_apres and not fini_entre(evs, position, plays_apres[0], coupe):
        suivant = plays_apres[0]
        remplacant, delai = evs[suivant][3], ecart(evs[position][0], evs[suivant][0])
    return coupe, remplacant, parle, delai


def coupes_player(evs):
    """(auto-coupes, sèches) : chaque `PLAYER cut` classé par son contexte.

    Cut avec PLAY derrière (sans fin entre) = auto-coupe (couple
    coupé/remplaçant + deltas) ; sans PLAY derrière = coupe sèche.
    """
    autos, seches = [], []
    for position, (ts, mot, premier, second) in enumerate(evs):
        if mot != "PLAYER" or premier != "cut":
            continue
        coupe, remplacant, parle, delai = coupe_attribuee(evs, position)
        if remplacant:
            autos.append((coupe, remplacant, parle, delai))
        else:
            seches.append((coupe, parle))
    return autos, seches


def suspect_cadence(mediane):
    """Suspect le plus proche de la cadence (écart relatif au log)."""
    return min(SUSPECTS_CADENCE, key=lambda s: abs(math.log(mediane / s[0])))[1]


def rafales_coupe(evs):
    """Groupes de cuts rapprochés : (nombre, cadence médiane, suspect).

    Groupe maximal de cuts consécutifs espacés de moins de
    [SEUIL_RAFALE_S] (horodatage requis : sans lui, pas de rafale).
    """
    horodates = [evs[i][0] for i in range(len(evs))
                 if evs[i][1] == "PLAYER" and evs[i][2] == "cut" and evs[i][0] is not None]
    groupes, groupe = [], []
    for ts in horodates:
        if groupe and ecart(groupe[-1], ts) >= SEUIL_RAFALE_S:
            groupes.append(groupe)
            groupe = []
        groupe.append(ts)
    if groupe:
        groupes.append(groupe)
    rafales = []
    for coupe_ts in groupes:
        if len(coupe_ts) < 2:
            continue
        cadence = statistics.median(ecart(a, b) for a, b in zip(coupe_ts, coupe_ts[1:]))
        rafales.append((len(coupe_ts), cadence, suspect_cadence(cadence)))
    return rafales


def recoups_niveau(evs):
    """`bus-parole` même niveau re-posté : (niveau, delta, cut-entre).

    Chaque `say()` accepté stoppe le lecteur : deux posts rapprochés du
    même niveau, c'est la première parole recoupée par la seconde. `cut
    entre les deux` confirme (sinon la première a pu finir à temps).
    """
    posts = [(i, evs[i][0], evs[i][2], evs[i][3]) for i in range(len(evs))
             if evs[i][1] == "BUS-PAROLE"]
    recoups = []
    for (i1, t1, niveau, _), (i2, t2, niveau2, _) in zip(posts, posts[1:]):
        if niveau != niveau2:
            continue
        delta = ecart(t1, t2)
        if delta is not None and delta >= SEUIL_RAPPROCHES_S:
            continue
        coupe = any(evs[i][1] == "PLAYER" and evs[i][2] == "cut" for i in range(i1 + 1, i2))
        recoups.append((niveau, delta, coupe))
    return recoups


def silences_m5(evs):
    """`PAROLE JETEE` après un cut : (delta, niveau, texte) — coupe + silence.

    Le cut a tué le son en vol, la remplaçante ne dit rien (clip +
    synthèse KO) : le pire cas (M5). Fenêtre [FENETRE_M5_S] avec
    horodatage, sinon aucun PLAY entre le cut et le jet.
    """
    jets = [(i, evs[i][0], evs[i][2], evs[i][3]) for i in range(len(evs))
            if evs[i][1] == "PAROLE-JETEE"]
    cuts = [(i, evs[i][0]) for i in range(len(evs))
            if evs[i][1] == "PLAYER" and evs[i][2] == "cut"]
    coupes = []
    for j, tj, niveau, texte in jets:
        avants = [(i, t) for i, t in cuts if i < j]
        if not avants:
            continue
        i, t = avants[-1]
        delta = ecart(t, tj)
        if delta is not None:
            if delta <= FENETRE_M5_S:
                coupes.append((delta, niveau, texte))
        elif not any(evs[k][1] == "PLAY" for k in range(i + 1, j)):
            coupes.append((None, niveau, texte))
    return coupes


def coupes_tts(evs):
    """Phrases TTS annoncées sans `TTS done` avant la suivante : (avant, après).

    Seule `source=repli-système` parle via le moteur TTS ; `done`, `error`
    et les refus (`driver-false`, `closed`, `blank`) closent l'attente —
    limite connue : le canal TTS est partagé (repli + navigation), un
    `done` de l'autre voix peut clore à tort.
    """
    en_vol, coupes = None, []
    for ts, mot, premier, second in evs:
        if mot == "PLAY" and premier == SOURCE_TTS:
            if en_vol is not None:
                coupes.append((en_vol, second))
            en_vol = second
        elif mot == "TTS":
            en_vol = None
    return coupes


def ecarts_silence(evs):
    """(écart, ts, détail, ts, détail, micro) entre PLAY horodatés voisins."""
    plays = [(i, t, d) for i, (t, mot, _, d) in enumerate(evs) if mot == "PLAY" and t is not None]
    return [(ecart(a, b), a, da, b, db, any(evs[k][1] == "MICRO" for k in range(i1 + 1, i2)))
            for (i1, a, da), (i2, b, db) in zip(plays, plays[1:])]


def taux_parole(evs):
    """(% couvert, couvert, total) : part du temps avec du son (PLAY→fin).

    Son allumé au PLAY, éteint au premier DONE / PLAYER (hors play) / TTS (dernier ts sinon)."""
    dates = [e for e in evs if e[0] is not None]
    plays = [e[0] for e in dates if e[1] == "PLAY"]
    if not dates or not plays:
        return None, None, None
    couvert, ouvert = 0.0, None
    # Sentinelle DONE : ferme une parole encore en vol au dernier horodatage.
    for ts, mot, premier, _ in dates + [(dates[-1][0], "DONE", "", "")]:
        if mot == "PLAY":
            ouvert = ts if ouvert is None else ouvert
        elif ouvert is not None and (mot in ("DONE", "TTS") or
                                     (mot == "PLAYER" and premier != "play")):
            couvert += ecart(ouvert, ts)
            ouvert = None
    total = ecart(plays[0], dates[-1][0])
    return (100.0 * couvert / total if total > 0 else None), couvert, total


def lignes_autos(autos):
    """Une ligne par auto-coupe : couple coupé/remplaçant + deltas."""
    lignes = []
    for coupe, remplacant, parle, delai in autos:
        lignes.append(f"auto-coupe [{coupe or '?'}] → [{remplacant}] "
                      f"(parlait depuis {fmt_duree(parle)}, remplaçant +{fmt_duree(delai)})")
    return lignes


def lignes_motifs(seches, rafales, recoups, silences, tts):
    """Une ligne par motif secondaire (sèches, rafales, recoups, M5, TTS)."""
    lignes = []
    for coupe, parle in seches:
        lignes.append(f"coupe sèche [{coupe or '?'}] (parlait depuis {fmt_duree(parle)})")
    for nombre, cadence, suspect in rafales:
        lignes.append(f"rafale : {nombre} cut(s), cadence {cadence:.2f}s → suspect {suspect}")
    for niveau, delta, coupe in recoups:
        confirme = "cut entre les deux" if coupe else "sans cut entre"
        lignes.append(f"recoupe niveau {niveau} (+{fmt_duree(delta)}, {confirme})")
    for delta, niveau, texte in silences:
        lignes.append(f"coupe+silence M5 : PAROLE JETEE level={niveau} [{texte}] "
                      f"+{fmt_duree(delta)} après cut")
    for avant, apres in tts:
        lignes.append(f"coupe-TTS probable : [{avant}] sans TTS done avant [{apres}]")
    return lignes


def verdict_coupe(evs, seuil=SEUIL_SILENCE_S):
    """(lignes, propre) : coupes + silences + verdict final COUPE/PROPRE.

    COUPE dès qu'un son a été interrompu (auto-coupe, sèche, M5, TTS) ou
    dès qu'un écart PLAY→PLAY dépasse [seuil] (jamais muet en session) ;
    PROPRE sinon (activité sans aucun PLAY ni TTS done = silence-total, COUPE).
    """
    autos, seches = coupes_player(evs)
    rafales = rafales_coupe(evs)
    recoups = recoups_niveau(evs)
    silences = silences_m5(evs)
    tts = coupes_tts(evs)
    ecarts = ecarts_silence(evs)
    fautifs = [g for g in ecarts if g[0] > seuil and not g[5]]
    exclus = [g for g in ecarts if g[0] > seuil and g[5]]
    stops = sum(1 for e in evs if e[1] == "PLAYER" and e[2] == "stop")
    from parse_isee_silence import est_silence_total
    stot = est_silence_total(evs, MOTS_COUPE)
    cuts = len(autos) + len(seches)
    lignes = []
    if cuts or stops or any(e[1] in MOTS_COUPE for e in evs):
        lignes.append(f"coupes : {len(autos)} auto-coupe(s), {len(seches)} sèche(s), "
                      f"{max(0, stops - cuts)} stop(s) à vide ignoré(s)")
    else:
        lignes.append("coupes : aucune trace PLAYER/TTS/VoiceMute")
    lignes += lignes_autos(autos)
    lignes += lignes_motifs(seches, rafales, recoups, silences, tts)
    lignes.append(f"silences : max {fmt_duree(max([g[0] for g in ecarts], default=None))}, " +
                  f"{len(fautifs)} écart(s) > {seuil:g}s ({len(exclus)} exclu(s) micro)" +
                  (", TOTAL : 0 PLAY, 0 TTS done" if stot else ""))
    for gap, ta, da, tb, db, micro in sorted(fautifs + exclus, reverse=True)[:5]:
        lignes.append(f"silence {gap:.2f}s ({'micro tenu, exclu' if micro else 'fautif'}) : "
                      f"[{da}] {fmt_ts(ta)} → [{db}] {fmt_ts(tb)}")
    pct, _, _ = taux_parole(evs)
    lignes.append(f"parole : {pct:.0f}% couverte" if pct is not None else "parole : ?")
    motifs = [("auto-coupe", len(autos)), ("coupe-sèche", len(seches)), ("rafale", len(rafales)),
              ("recoupe-niveau", len(recoups)), ("coupe+silence-M5", len(silences)), ("coupe-TTS", len(tts)),
              ("silence", len(fautifs)), ("silence-total", 1 if stot else 0)]
    dus = sorted(((nom, n) for nom, n in motifs if n), key=lambda m: (-m[1], m[0]))
    propre = not autos and not seches and not silences and not tts and not fautifs and not stot
    if propre:
        lignes.append(f"verdict-coupe : PROPRE (0 cut avec son interrompu, "
                      f"0 écart > {seuil:g}s ; {max(0, stops - cuts)} stop(s) à vide ignoré(s))")
    else:
        lignes.append(f"verdict-coupe : COUPE ({len(autos)} auto-coupe(s), "
                      f"{len(fautifs)} silence(s) ; top : " +
                      ", ".join(f"{nom}×{n}" for nom, n in dus[:3]) + ")")
    return lignes, propre


def lit_entree(chemin):
    """Contenu du dump : fichier UTF-8, ou stdin quand aucun chemin n'est donné."""
    if chemin is None:
        return sys.stdin.read()
    with open(chemin, encoding="utf-8") as fichier:
        return fichier.read()


def selftest():
    """Délègue à parse_isee_voix_selftest (limite maison < 500 lignes)."""
    import parse_isee_voix_selftest
    return parse_isee_voix_selftest.selftest()


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
    texte = lit_entree(chemin)
    expects, plays, dones, fails = parse_texte(texte)
    evs = evenements(texte)
    if not expects and not any(e[1] in MOTS_COUPE for e in evs):
        print("aucun EXPECT ni trace coupe ISEE_VOIX/VoiceMute (tag filtré ? voix muette ?)",
              file=sys.stderr)
        return 2
    lignes, ok = verdict(expects, plays, dones, fails) if expects else ([], True)
    coupe_lignes, propre = verdict_coupe(evs)
    print("\n".join(lignes + coupe_lignes))
    return 0 if ok and propre else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
