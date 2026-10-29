"""Segmentation SAM 3.1 **officielle** : code natif Meta + checkpoint facebook/sam3.1.

Le dépôt officiel ne contient aucun poids ``transformers`` (seulement
``sam3.1_multiplex.pt`` natif) : ce module passe donc par le paquet
``facebookresearch/sam3`` au lieu de ``transformers``. Il ne sert que l'app
``sam3-vision`` (``modal_app.py`` : ``/segment``, ``/analyze``) — ``/live``
reste sur le chemin ``transformers`` (``segmentation.py`` + ``fast_core.py``).

Une image = une vidéo d'une frame : ``start_session`` + ``add_prompt`` par
concept sur la frame 0 (même recette que ``tracking.LiveSession.start``),
sessions refermées aussitôt. Les objets repassent par ``segmentation.finalize``
(tri global, coupe à 24, encodage PNG) : le contrat SPEC est inchangé.

Poids : dépôt officiel (gated, licence Meta acceptée) via ``HF_TOKEN`` (secret
Modal ``huggingface``), persistés dans le volume ``sam3-official`` monté sur
``CKPT_DIR``. ``fetch_checkpoint`` ne télécharge que ce qui manque.

torch / sam3 / huggingface_hub ne sont importés que dans les fonctions : ce
module reste importable (tests, outillage) sans stack GPU.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

OFFICIAL_REPO = "facebook/sam3.1"
CKPT_FILES = ("sam3.1_multiplex.pt", "config.json")
CKPT_DIR = os.environ.get("SEG_CKPT_DIR", "/root/ckpts")
HF_SECRET_NAME = "huggingface"
VOLUME_NAME = "sam3-official"
SESSION_CAPACITY = 1  # une image = une frame, pas de tampon à pré-allouer

# Le predictor multiplex est construit par tracking.py : il lit son propre
# répertoire (TRACK_CKPT_DIR) à l'import — on l'aligne avant tout import.
os.environ.setdefault("TRACK_CKPT_DIR", CKPT_DIR)


def missing_files() -> list[str]:
    """Fichiers du checkpoint officiel absents de ``CKPT_DIR``."""
    return [name for name in CKPT_FILES if not Path(CKPT_DIR, name).exists()]


def fetch_checkpoint() -> float:
    """Assure la présence des poids officiels ; renvoie la durée (0 si déjà là).

    Téléchargement depuis le dépôt officiel (gated) — ``HF_TOKEN`` requis sauf
    si tout est déjà présent (volume pré-rempli).
    """
    missing = missing_files()
    if not missing:
        return 0.0
    token = os.environ.get("HF_TOKEN", "")
    if not token:
        raise OSError(f"poids officiels manquants ({', '.join(missing)}) et HF_TOKEN "
                      f"absent — secret Modal {HF_SECRET_NAME!r} requis")
    from huggingface_hub import hf_hub_download

    os.makedirs(CKPT_DIR, exist_ok=True)
    started = time.time()
    for name in missing:
        hf_hub_download(OFFICIAL_REPO, name, local_dir=CKPT_DIR, token=token)
    return round(time.time() - started, 1)


def build_predictor():
    """Construit le predictor multiplex officiel (recette ``tracking`` éprouvée)."""
    from tracking import build_predictor as build_mux

    return build_mux()


def _as_list(values) -> list:
    """Tensor / tableau / liste -> liste Python (ids, scores du multiplex)."""
    if hasattr(values, "flatten"):
        values = values.flatten().tolist()
    elif hasattr(values, "tolist"):
        values = values.tolist()
    return list(values)


def native_entries(outputs: dict, label: str, image_size) -> list[dict]:
    """Sorties multiplex d'un concept -> entrées à ``finalize`` (**masque brut**).

    Le ``label`` reste le prompt tel que saisi (contrat historique) ; sans
    masque, l'objet est inutilisable (SPEC exige ``mask_b64``) et sauté.
    """
    from tracking import box_from_norm

    raw_ids = outputs.get("out_obj_ids")
    ids = _as_list(raw_ids) if raw_ids is not None else []
    if not ids:
        return []
    width, height = image_size
    boxes = outputs.get("out_boxes_xywh")
    raw_probs = outputs.get("out_probs")
    probs = _as_list(raw_probs) if raw_probs is not None else []
    masks = outputs.get("out_binary_masks")
    entries = []
    for index in range(len(ids)):
        mask = masks[index] if masks is not None and index < len(masks) else None
        if mask is None:
            continue
        box = (box_from_norm(boxes[index], width, height) if boxes is not None
               else [0.0, 0.0, float(width), float(height)])
        score = float(probs[index]) if index < len(probs) else 0.0
        entries.append({"label": label, "score": round(score, 4), "box": box,
                        "mask": mask})
    return entries


def _run_prompt_native(predictor, image, prompt: str, image_size) -> list[dict]:
    """Un concept : session d'une frame, prompt texte, objets, session fermée."""
    from segmentation import model_concept
    from tracking import OUTPUT_PROB_THRESH

    session = predictor.handle_request(
        {"type": "start_session", "resource_path": [image, SESSION_CAPACITY]}
    )["session_id"]
    try:
        outputs = predictor.handle_request({
            "type": "add_prompt", "session_id": session, "frame_index": 0,
            "text": model_concept(prompt), "output_prob_thresh": OUTPUT_PROB_THRESH,
        })["outputs"]
        return native_entries(outputs, prompt, image_size)
    finally:
        predictor.handle_request({"type": "close_session", "session_id": session})


def segment_image_native(predictor, image, prompts: list[str], max_objects: int,
                         mask_encoding: str) -> dict:
    """Segmente ``image`` (PIL) pour chaque prompt, charge SPEC via ``finalize``."""
    from segmentation import finalize

    entries: list[dict] = []
    for prompt in prompts:
        entries.extend(_run_prompt_native(predictor, image, prompt, image.size))
    return finalize(entries, image.size, max_objects, mask_encoding)


def run_segmentation_native(predictor, image, prompts=None, mask_encoding: str = None,
                            max_objects: int = None) -> dict:
    """Choix des concepts côté serveur (défauts + repli générique), chemin officiel.

    Mêmes sémantiques que ``segmentation.run_segmentation`` : prompts explicites
    validés, sinon concepts larges puis repli si rien n'est trouvé.
    """
    from segmentation import (DEFAULT_MASK_ENCODING, DEFAULT_PROMPTS, FALLBACK_PROMPTS,
                              MAX_OBJECTS, normalize_prompts)

    encoding = mask_encoding or DEFAULT_MASK_ENCODING
    cap = MAX_OBJECTS if max_objects is None else max_objects
    if prompts is not None:
        cleaned = normalize_prompts(prompts)
        return segment_image_native(predictor, image, cleaned, cap, encoding)
    result = segment_image_native(predictor, image, list(DEFAULT_PROMPTS), cap, encoding)
    if result["objects"]:
        return result
    return segment_image_native(predictor, image, list(FALLBACK_PROMPTS), cap, encoding)
