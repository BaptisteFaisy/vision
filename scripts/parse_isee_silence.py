"""Silence total : activite voix sans aucun son joue (ni PLAY ni TTS done).

Un log qui montre des paroles acceptees (`bus-parole`), des `PLAYER stop`
ou des jets — mais aucun `PLAY` ni `TTS done` — prouve un moteur muet
(lecteur mort, 02/11), pas une session calme : le verdict-coupe doit dire
COUPE, jamais PROPRE. Pur, bibliotheque standard seule.
"""


def est_silence_total(evs, mots_activite):
    """Vrai si [evs] montre de l'activite voix mais aucun son joue.

    [evs] : evenements `(ts, mot, premier, second)` ; [mots_activite] :
    mots qui prouvent une tentative de parole (ex. MOTS_COUPE).
    """
    joue = any(mot == "PLAY" for _, mot, _, _ in evs)
    tts_dit = any(mot == "TTS" and premier == "done" for _, mot, premier, _ in evs)
    actif = any(mot in mots_activite for _, mot, _, _ in evs)
    return actif and not joue and not tts_dit
