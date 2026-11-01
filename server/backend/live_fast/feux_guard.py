"""Garde serveur des feux FR et du passage piéton : petits objets jamais évincés.

Cinq gardes, sans GPU ni poids (testées par ``scripts/tests_units/``) :

* **anti-éviction** — :func:`finalize_guarded` enveloppe ``segmentation.finalize`` :
  si des objets « feu » ou « passage piéton » sont détectés mais qu'aucun n'entre
  dans le top ``max_objects`` (petit feu lointain, zébrures à 0,36), le meilleur
  de chaque famille prend une place réservée. Les seuils globaux
  (``SCORE_THRESHOLD``, ``MASK_THRESHOLD``) sont inchangés : on réserve des
  places, on ne baisse rien ;
* **routage des labels** — :func:`routed_concept` mappe les variantes anglaises
  « pedestrian light/signal » (que la table ``concepts`` ne connaît pas) vers
  ``pedestrian traffic light`` pour le modèle, les labels renvoyés restant tels
  que saisis. :func:`is_feu_label` reconnaît la même famille pour l'anti-éviction ;
* **famille bordure** — :func:`is_curb_label` reconnaît le rebord et le
  bateau (« curb », « curb ramp », « step » + variantes FR/EN) pour la même
  réserve : le relief de 10 cm ne se fait plus évincer par les gros objets ;
* **famille passage** — :func:`is_crosswalk_label` reconnaît les variantes que la
  table ``concepts`` laisse passer telles quelles (« zebra crossing », « zebra »,
  « bandes »…) pour l'anti-éviction — sans toucher au routage modèle (le CLIP
  reçoit les prompts inchangés, seule la réserve s'élargit) ;
* **cohérence work_edge** — :func:`coherent_work_edge` ramène le ``work_edge``
  déclaré au plus grand côté de l'image reçue (le resize n'agrandit jamais, donc
  c'est la taille réellement appliquée) et loggue l'écart.

Vit dans ``live_fast/`` seul (fichiers vendored ``segmentation.py``/``concepts.py``
intouchés : la parité byte-identique avec ``backend/`` reste prouvée par la CI).
"""

from __future__ import annotations

from concepts import model_concept

# Concept « feu » gardé côté modèle : le piéton seul (la réserve
# anti-éviction ne protège que lui). Les tricolores (`traffic light`, suivi
# manuel) restent détectés mais ne prennent plus de place réservée — sinon
# une boîte voiture dans le top marquait la famille « gardée » et le vrai
# feu piéton faible score était évincé sans réserve (01/11).
FEU_CONCEPTS = frozenset({"pedestrian traffic light"})

# Variantes anglaises absentes de la table ``concepts`` : routées vers le feu
# piéton (la Traversée ne suit que ceux-là, ordre user 31/10) au lieu d'être
# remises telles quelles au CLIP — et reconnues par l'anti-éviction.
# `signal` seul exclu (01/11) : mot ambigu (vise parfois un tricolore) qui
# réservait un slot pour une boîte que le client ignore.
ROUTED_LABELS = {
    "pedestrian light": "pedestrian traffic light",
    "pedestrian lights": "pedestrian traffic light",
    "pedestrian signal": "pedestrian traffic light",
    "pedestrian signals": "pedestrian traffic light",
}

# Concept « passage piéton » côté modèle (la table ``concepts`` y mène déjà
# « passage piéton » et « passage clouté »).
CROSSWALK_CONCEPTS = frozenset({"crosswalk"})

# Concept « bordure » côté modèle : le rebord, le bateau et la marche
# (la table ``concepts`` y mène déjà bordure/rebord/bateau/marche).
# « sidewalk » exclu : le plan entier, pas le bord — le réserver
# gaspillerait la place (miroir de ``CurbConcepts`` côté client).
CURB_CONCEPTS = frozenset({"curb", "curb ramp", "step"})

# Variantes brutes reconnues pour la réserve (tri serveur seul).
CURB_ROUTED = {
    "steps": "step",
    "curb cut": "curb",
}


# Variantes que la table ``concepts`` laisse passer telles quelles, reconnues
# pour la réserve anti-éviction (clés = sortie de ``model_concept`` : minuscules
# désaccentuées). Miroir de ``CrossingOffer.LABELS`` côté client — ici pour le
# tri serveur seul, jamais pour le modèle (voir ``is_crosswalk_label``).
CROSSWALK_ROUTED = {
    "cross walk": "crosswalk",
    "zebra crossing": "crosswalk",
    "pedestrian crossing": "crosswalk",
    "zebra": "crosswalk",
    "zebre": "crosswalk",
    "cloutage": "crosswalk",
    "bande": "crosswalk",
    "bandes": "crosswalk",
    "bande blanche": "crosswalk",
    "bandes blanches": "crosswalk",
}


def routed_concept(prompt: str) -> str:
    """Concept modèle d'un prompt, variantes EN « pedestrian light/signal » routées.

    Même normalisation que ``model_concept`` (casse, articles, accents), puis
    routage des variantes ci-dessus ; le reste passe inchangé (alias FR et
    inconnus préservés).
    """
    norm = model_concept(prompt)
    return ROUTED_LABELS.get(norm, norm)


def is_feu_label(label: object) -> bool:
    """Vrai si [label] désigne un feu piéton (FR/EN, casse/accents tolérés).

    Les wordings voiture (`feu tricolore`, `traffic light`…) sont exclus :
    la réserve anti-éviction ne protège que le piéton (01/11).
    """
    if not isinstance(label, str) or not label.strip():
        return False
    return routed_concept(label) in FEU_CONCEPTS


def is_curb_label(label: object) -> bool:
    """Vrai si [label] désigne une bordure/bateau/marche (FR/EN, tolérant)."""
    if not isinstance(label, str) or not label.strip():
        return False
    norm = model_concept(label)
    return CURB_ROUTED.get(norm, norm) in CURB_CONCEPTS


def is_crosswalk_label(label: object) -> bool:
    """Vrai si [label] désigne un passage piéton (FR/EN, casse/accents tolérés).

    La table ``concepts`` couvre « passage piéton/clouté » ; ``CROSSWALK_ROUTED``
    ajoute les variantes brutes (« zebra », « bandes »…). Ce routage ne sert
    qu'à la réserve anti-éviction : le CLIP reçoit les prompts inchangés.
    """
    if not isinstance(label, str) or not label.strip():
        return False
    norm = model_concept(label)
    return CROSSWALK_ROUTED.get(norm, norm) in CROSSWALK_CONCEPTS


def coherent_work_edge(work: int, image_size) -> int:
    """``work_edge`` déclaré ramené au plus grand côté reçu ; log si incohérent.

    Le resize n'agrandit jamais (``resize_max_edge``) : déclarer 768 pour une
    image de 640 px appliquait déjà 640 — on l'écrit et on le dit, sans changer
    le comportement. Taille illisible : inchangé, jamais d'exception (une frame
    ne vaut pas un 500).
    """
    try:
        longest = max(int(image_size[0]), int(image_size[1]))
    except (TypeError, ValueError, IndexError):
        return work
    if longest <= 0 or work <= longest:
        return work
    print(f"live: work_edge déclaré {work} > image reçue {longest}px, clampé à {longest}",
          flush=True)
    return longest


def _split_guarded(entries):
    """Entrées partagées en (autres, feux, passages, bordures), ordre stable."""
    others, feu, walk, curb = [], [], [], []
    for entry in entries:
        label = entry.get("label")
        if is_feu_label(label):
            feu.append(entry)
        elif is_crosswalk_label(label):
            walk.append(entry)
        elif is_curb_label(label):
            curb.append(entry)
        else:
            others.append(entry)
    return others, feu, walk, curb


def _top_keeps(entries: list[dict], max_objects: int, is_guarded) -> bool:
    """Le tri pur de ``finalize`` garde-t-il déjà un objet gardé dans le top ?"""
    top = sorted(entries, key=lambda item: item["score"], reverse=True)[:max_objects]
    return any(is_guarded(item.get("label")) for item in top)


def finalize_guarded(finalize, entries: list[dict], image_size, max_objects: int,
                     mask_encoding: str, mask_format=None, mask_edge: int = 0) -> dict:
    """``finalize`` + une place réservée par famille gardée qui serait évincée.

    Une famille (feux, passages, bordures) absente des détections, déjà dans
    le top, ou
    ``max_objects`` trop petit pour réserver sans tout évincer (≤ une place par
    famille manquante) : ``finalize`` pur, réponse strictement identique. Sinon
    un appel par famille manquante (première place de chacune) plus les autres
    sur le reste, fusionnés par score décroissant — mêmes masques, mêmes
    ``mask_scale`` RLE, mêmes clés ``depth``.

    Les familles **déjà** dans le top concourent normalement, dans le même pool
    que les autres objets : les exclure toutes avant de ne réserver qu'aux
    familles manquantes effaçait un feu pourtant premier au score (0,99) dès
    qu'un passage à 0,10 prenait sa place réservée — la garde évinçait
    précisément ce qu'elle protège (reel audit foule 01/11).
    """
    others, feu, walk, curb = _split_guarded(entries)
    families = ((feu, is_feu_label), (walk, is_crosswalk_label), (curb, is_curb_label))
    missing = [fam for fam, is_guarded in families
               if fam and not _top_keeps(entries, max_objects, is_guarded)]
    if not missing or max_objects <= len(missing):
        return finalize(entries, image_size, max_objects, mask_encoding,
                        mask_format=mask_format, mask_edge=mask_edge)
    pool = list(others)
    for fam, _is_guarded in families:
        if fam and all(fam is not reserved for reserved in missing):
            pool.extend(fam)
    kept = finalize(pool, image_size, max_objects - len(missing), mask_encoding,
                    mask_format=mask_format, mask_edge=mask_edge)
    merged = list(kept["objects"])
    for family in missing:
        part = finalize(family, image_size, 1, mask_encoding,
                        mask_format=mask_format, mask_edge=mask_edge)
        merged.extend(part["objects"])
    merged = sorted(merged, key=lambda item: item["score"], reverse=True)
    return {"width": kept["width"], "height": kept["height"],
            "mask_encoding": kept["mask_encoding"], "objects": merged}
