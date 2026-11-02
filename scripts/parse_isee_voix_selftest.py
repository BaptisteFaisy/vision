#!/usr/bin/env python3
"""parse_isee_voix_selftest.py — assertions du parseur ISEE_VOIX, hors du module.

Extrait de `scripts/parse_isee_voix.py` pour tenir la limite maison (< 500 lignes
par fichier, < 50 par fonction) : les trois blocs (orphelins, coupes, tts-propre)
vivent ici, le parseur garde un appel paresseux (`--selftest`). Aucune logique
dupliquée : ce module ne fait qu'importer et asserter.

Usage :
    python3 scripts/parse_isee_voix.py --selftest   # voie normale (CI)
    python3 scripts/parse_isee_voix_selftest.py     # direct, même verdict

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import sys

from parse_isee_voix import (coupes_player, coupes_tts, couvre_play, ecart, evenements,
                             horodatage, orphelins_par_phrase, parse_ligne, parse_texte,
                             rafales_coupe, recoups_niveau, silences_m5, verdict, verdict_coupe)

TRACE_TENU = """I ISEE_VOIX: EXPECT voie=FENCE phrase=[Stop.]
I ISEE_VOIX: PLAY source=clip detail=[Stop.]
I ISEE_VOIX: DONE phrase=[Stop.]
I sam3vision: bruit hors tag
ISEE_VOIX: EXPECT voie=repli phrase=[Gauche.]
ISEE_VOIX: PLAY source=synthèse detail=[joue : Gauche.]
ISEE_VOIX: FAIL ou=bus cause=[écrasé]
"""

TRACE_COUPE = """11-02 15:00:00.000  1000  1000 I ISEE_VOIX: EXPECT voie=FENCE phrase=[Porte.]
11-02 15:00:00.010  1000  1000 I ISEE_VOIX: PLAY source=clip detail=[Porte.]
11-02 15:00:00.020  1000  1000 D VoiceMute: bus-parole level=2 texte=[Porte.] scope-actif=True loc=1
11-02 15:00:00.400  1000  1000 I ISEE_VOIX: PLAYER stop
11-02 15:00:00.401  1000  1000 I ISEE_VOIX: PLAYER cut
11-02 15:00:00.410  1000  1000 D VoiceMute: bus-parole level=2 texte=[Voiture.] scope-actif=True loc=1
11-02 15:00:00.450  1000  1000 I ISEE_VOIX: PLAY source=clip detail=[Voiture.]
11-02 15:00:00.700  1000  1000 I ISEE_VOIX: PLAYER stop
11-02 15:00:00.701  1000  1000 I ISEE_VOIX: PLAYER cut
11-02 15:00:00.750  1000  1000 I ISEE_VOIX: PLAY source=clip detail=[Monsieur.]
11-02 15:00:01.000  1000  1000 I ISEE_VOIX: PLAYER stop
11-02 15:00:01.001  1000  1000 I ISEE_VOIX: PLAYER cut
11-02 15:00:01.050  1000  1000 I ISEE_VOIX: PLAY source=clip detail=[Ralentir.]
11-02 15:00:02.500  1000  1000 I ISEE_VOIX: PLAYER stop
11-02 15:00:02.501  1000  1000 I ISEE_VOIX: PLAYER cut
11-02 15:00:03.300  1000  1000 W VoiceMute: PAROLE JETEE level=2 texte=[Ralentir.] clip=[None] resolu=[None]
"""

TRACE_TTS = """ISEE_VOIX: PLAY source=repli-système detail=[Rue de la Paix.]
ISEE_VOIX: DONE phrase=[Rue de la Paix.]
ISEE_VOIX: PLAY source=repli-système detail=[Place du Châtelet.]
"""

TRACE_TTS_CLOS = ("ISEE_VOIX: PLAY source=repli-système detail=[A.]\nISEE_VOIX: TTS done\n"
                  "ISEE_VOIX: PLAY source=repli-système detail=[B.]\nISEE_VOIX: TTS error\n")

TRACE_PROPRE = """ISEE_VOIX: EXPECT voie=FENCE phrase=[Stop.]
ISEE_VOIX: PLAY source=clip detail=[Stop.]
ISEE_VOIX: PLAYER play
ISEE_VOIX: PLAYER done
ISEE_VOIX: DONE phrase=[Stop.]
ISEE_VOIX: PLAYER stop
"""


def selftest_orphelins():
    """Cycle complet, orphelin, détail verbeux, bruit : 10 assertions."""
    expects, plays, dones, fails = parse_texte(TRACE_TENU)
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
    return 10


def selftest_coupes():
    """Auto-coupes, sèche, rafale, recoupe, M5, orphelins intacts : 12 assertions."""
    evs = evenements(TRACE_COUPE)
    autos, seches = coupes_player(evs)
    assert len(autos) == 3 and len(seches) == 1, (autos, seches)
    assert autos[0][:2] == ("Porte.", "Voiture."), autos
    assert abs(autos[0][2] - 0.39) < 0.01 and abs(autos[0][3] - 0.05) < 0.01, autos
    assert seches[0][0] == "Ralentir.", seches
    rafales = rafales_coupe(evs)
    assert len(rafales) == 1 and rafales[0][0] == 3 and rafales[0][2] == "mots continus", rafales
    assert abs(rafales[0][1] - 0.3) < 0.01, rafales
    recoups = recoups_niveau(evs)
    assert len(recoups) == 1 and recoups[0][0] == "2" and recoups[0][2], recoups
    assert abs(recoups[0][1] - 0.39) < 0.01, recoups
    silences = silences_m5(evs)
    assert len(silences) == 1 and silences[0][1:] == ("2", "Ralentir."), silences
    assert abs(silences[0][0] - 0.80) < 0.01, silences
    lignes, propre = verdict_coupe(evs)
    assert not propre and "COUPE (3 auto-coupe(s)" in lignes[-1], lignes
    assert parse_texte(TRACE_COUPE) == (["Porte."],
                                        ["Porte.", "Voiture.", "Monsieur.", "Ralentir."], [], [])
    return 12


def selftest_tts_propre():
    """Coupe TTS, TTS clos, propre, parseurs, horodatage : 9 assertions."""
    assert coupes_tts(evenements(TRACE_TTS)) == [("Rue de la Paix.", "Place du Châtelet.")]
    assert coupes_tts(evenements(TRACE_TTS_CLOS)) == []
    lignes, est_propre = verdict_coupe(evenements(TRACE_PROPRE))
    assert est_propre and "PROPRE" in lignes[-1] and "1 stop(s) à vide" in lignes[-1], lignes
    assert parse_ligne("W VoiceMute: bus-parole level=2 texte=[A.] scope-actif=True") == \
        ("BUS-PAROLE", "2", "A.")
    assert parse_ligne("W VoiceMute: PAROLE JETEE level=2 texte=[A.] clip=[x] resolu=[y]") == \
        ("PAROLE-JETEE", "2", "A.")
    assert parse_ligne("I ISEE_VOIX: PLAYER cut") == ("PLAYER", "cut", "")
    assert parse_ligne("I ISEE_VOIX: TTS done") == ("TTS", "done", "")
    assert horodatage("sans date ISEE_VOIX: PLAYER cut") is None
    assert ecart(86399.5, 0.5) == 1.0
    return 9


def selftest():
    """Assertions du parseur : trois blocs (orphelins, coupes, tts-propre)."""
    total = selftest_orphelins() + selftest_coupes() + selftest_tts_propre()
    print(f"selftest voix : {total} assertions OK")
    return 0


if __name__ == "__main__":
    sys.exit(selftest())
