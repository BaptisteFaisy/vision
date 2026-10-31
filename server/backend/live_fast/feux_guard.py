"""Garde serveur des feux FR : petit feu jamais évincé, labels acheminés.

Trois gardes, sans GPU ni poids (testées par ``scripts/tests_units/``) :

* **anti-éviction** — :func:`finalize_guarded` enveloppe ``segmentation.finalize`` :
  si des objets « feu » sont détectés mais qu'aucun n'entre dans le top
  ``max_objects`` (petit feu lointain, score faible), le meilleur feu prend la
  dernière place gardée. Les seuils globaux (``SCORE_THRESHOLD``,
  ``MASK_THRESHOLD``) sont inchangés : on réserve une place, on ne baisse rien ;
* **routage des labels** — :func:`routed_concept` mappe les variantes anglaises
  « pedestrian light/signal » (que la table ``concepts`` ne connaît pas) vers
  ``pedestrian traffic light`` pour le modèle, les labels renvoyés restant tels
  que saisis. :func:`is_feu_label` reconnaît la même famille pour l'anti-éviction ;
* **cohérence work_edge** — :func:`coherent_work_edge` ramène le ``work_edge``
  déclaré au plus grand côté de l'image reçue (le resize n'agrandit jamais, donc
  c'est la taille réellement appliquée) et loggue l'écart.

Vit dans ``live_fast/`` seul (fichiers vendored ``segmentation.py``/``concepts.py``
intouchés : la parité byte-identique avec ``backend/`` reste prouvée par la CI).
"""

from __future__ import annotations

from concepts import model_concept

# Concepts « feu » côté modèle : tricolores (suivi manuel) + piétons (Traversée).
FEU_CONCEPTS = frozenset({"traffic light", "pedestrian traffic light"})

# Variantes anglaises absentes de la table ``concepts`` : routées vers le feu
# piéton (la Traversée ne suit que ceux-là, ordre user 31/10) au lieu d'être
# remises telles quelles au CLIP — et reconnues par l'anti-éviction.
ROUTED_LABELS = {
    "pedestrian light": "pedestrian traffic light",
    "pedestrian lights": "pedestrian traffic light",
    "pedestrian signal": "pedestrian traffic light",
    "pedestrian signals": "pedestrian traffic light",
    "signal": "pedestrian traffic light",
    "signals": "pedestrian traffic light",
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
    """Vrai si [label] désigne un feu (FR/EN, casse et accents tolérés)."""
    if not isinstance(label, str) or not label.strip():
        return False
    return routed_concept(label) in FEU_CONCEPTS


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


def _split_feu(entries: list[dict]) -> tuple[list[dict], list[dict]]:
    """Entrées partagées en (autres, feux), ordre stable conservé des deux côtés."""
    others, feu = [], []
    for entry in entries:
        (feu if is_feu_label(entry.get("label")) else others).append(entry)
    return others, feu


def _top_keeps_feu(entries: list[dict], max_objects: int) -> bool:
    """Le tri pur de ``finalize`` garde-t-il déjà un feu dans les ``max_objects`` ?"""
    top = sorted(entries, key=lambda item: item["score"], reverse=True)[:max_objects]
    return any(is_feu_label(item.get("label")) for item in top)


def finalize_guarded(finalize, entries: list[dict], image_size, max_objects: int,
                     mask_encoding: str, mask_format=None, mask_edge: int = 0) -> dict:
    """``finalize`` + une place réservée au meilleur feu s'il serait évincé.

    Sans feu détecté, feu déjà dans le top, ou ``max_objects < 2`` (pas de place
    à réserver) : ``finalize`` pur, réponse strictement identique. Sinon deux
    appels (autres à ``max_objects - 1``, feux à 1) fusionnés par score
    décroissant — mêmes masques, mêmes ``mask_scale`` RLE, mêmes clés ``depth``.
    """
    others, feu = _split_feu(entries)
    if not feu or max_objects < 2 or _top_keeps_feu(entries, max_objects):
        return finalize(entries, image_size, max_objects, mask_encoding,
                        mask_format=mask_format, mask_edge=mask_edge)
    kept_others = finalize(others, image_size, max_objects - 1, mask_encoding,
                           mask_format=mask_format, mask_edge=mask_edge)
    kept_feu = finalize(feu, image_size, 1, mask_encoding,
                        mask_format=mask_format, mask_edge=mask_edge)
    merged = sorted(kept_others["objects"] + kept_feu["objects"],
                    key=lambda item: item["score"], reverse=True)
    return {"width": kept_others["width"], "height": kept_others["height"],
            "mask_encoding": kept_others["mask_encoding"], "objects": merged}
