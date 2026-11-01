"""Profondeur monoculaire (Depth Anything V2 Small) fusionnée dans ``/live``.

``with_depth`` (``POST /live`` et ``WS /live/ws``) : la frame déjà décodée passe
dans DA2-Small (~25 M params, ~50 Mo VRAM) et chaque objet renvoyé porte sa
médiane de proximité (``depth``, 0..1, **grand = proche** — même convention que
la carte on-device, voir ``DepthMap.kt`` : la sortie brute de DA2 est une
disparité). Médiane sur le **masque** (pixels objet dans la boîte érodée),
repli boîte érodée (25 % par côté, comme ``DepthReadings`` embarqué) : la boîte
entière lirait le fond sur un objet fin (poteau). Client muet = aucune clé,
aucun coût ; modèle absent = repli gracieux (pas de clé, jamais d'erreur).

Throttle WS (``FAST_DEPTH_EVERY``, défaut 8) : le client demande la profondeur à
chaque frame mais se contente d'une carte fraîche de moins de 1500 ms (voir
``LiveDepthMeter.SERVER_FRESH_MS``) — le serveur ne calcule donc qu'1 carte sur
N et répète les médianes entre deux (``new_depth_throttle`` + ``cached_medians``
+ ``attach_cached_medians``). Répéter plutôt qu'omettre : le client ne rejoue
pas les dernières médianes reçues (une réponse sans clé retombe en sténopé
pur), et des médianes à chaque réponse gardent l'inférence embarquée coupée.

Imports lourds (torch/transformers) dans les fonctions : ce module reste
importable sans GPU, comme ``segmentation``.
"""

from __future__ import annotations

import os

import numpy as np

MODEL_ID = "depth-anything/Depth-Anything-V2-Small-hf"
# Poids embarqués dans l'image (FAST_DEPTH_MODEL_DIR, téléchargés au build comme
# sam3.1) : le boot est hermétique offline (HF_HUB_OFFLINE=1), sans répertoire
# local `from_pretrained` échoue et with_depth reste sans effet (depth=null).
MODEL_DIR = os.environ.get("FAST_DEPTH_MODEL_DIR", "") or ""
MODEL_SOURCE = MODEL_DIR or MODEL_ID
# Carte plate (aucune information) : ni proche ni loin.
NEUTRAL_DEPTH = 0.5
# Érosion par côté avant la médiane : les bords portent le fond (voir DepthReadings.kt).
EROSION_FRACTION = 0.25
# Pixels objet minimaux dans la fenêtre : en deçà, la médiane masque est du bruit.
MIN_MASK_PIXELS = 4


def load_depth(device: str, dtype):
    """Charge DA2-Small une fois (conteneur) : ``(modèle, processeur)``."""
    from transformers import AutoImageProcessor, AutoModelForDepthEstimation

    model = AutoModelForDepthEstimation.from_pretrained(
        MODEL_SOURCE, local_files_only=bool(MODEL_DIR))
    model.to(device)
    if dtype is not None:
        model.to(dtype)
    return model.eval(), AutoImageProcessor.from_pretrained(
        MODEL_SOURCE, local_files_only=bool(MODEL_DIR))


def ensure_depth(ctx: dict, device: str, dtype) -> None:
    """Charge DA2-Small dans ``ctx`` (clés ``depth_*``) ; échec = repli gracieux."""
    try:
        model, processor = load_depth(device, dtype)
    except Exception as exc:  # noqa: BLE001 — with_depth dégrade, ne casse jamais /live
        print(f"depth: modèle indisponible ({exc}), with_depth sans effet", flush=True)
        return
    ctx["depth_model"] = model
    ctx["depth_processor"] = processor
    ctx["depth_model_id"] = MODEL_ID


def depth_map_for(model, processor, image, device):
    """Carte de proximité normalisée 0..1 (grand = proche), à la taille de ``image``."""
    import torch
    import torch.nn.functional as functional

    pixels = processor(images=image, return_tensors="pt")["pixel_values"].to(device)
    with torch.inference_mode():
        predicted = model(pixels).predicted_depth
    width, height = image.size
    resized = functional.interpolate(predicted.unsqueeze(1), size=(height, width),
                                     mode="bilinear", align_corners=False)
    return normalize_disparity(resized.squeeze().float().cpu().numpy())


def normalize_disparity(values) -> np.ndarray:
    """Min-max vers 0..1 (ordre préservé : grand = proche) ; carte plate → neutre."""
    array = np.asarray(values, dtype=np.float32)
    span = float(array.max() - array.min())
    if not np.isfinite(span) or span <= 0:
        return np.full(array.shape, NEUTRAL_DEPTH, dtype=np.float32)
    return ((array - array.min()) / span).astype(np.float32)


def eroded_box(box):
    """Boîte ``box`` (xyxy) rognée d'``EROSION_FRACTION`` par côté ; None si dégénérée."""
    x1, y1, x2, y2 = (float(value) for value in box)
    dx, dy = (x2 - x1) * EROSION_FRACTION, (y2 - y1) * EROSION_FRACTION
    if dx <= 0 or dy <= 0:
        return None
    inner = [x1 + dx, y1 + dy, x2 - dx, y2 - dy]
    return inner if inner[2] > inner[0] and inner[3] > inner[1] else None


def _clip_window(window, image_size):
    """Fenêtre xyxy flottante -> bornes entières (x1, y1, x2, y2), cadre inclus."""
    width, height = image_size
    x1, y1, x2, y2 = (int(round(value)) for value in window)
    return max(x1, 0), max(y1, 0), min(x2, width), min(y2, height)


def median_in_window(norm, window, image_size):
    """Médiane de la carte sur ``window`` (xyxy flottants, bornés) ; None si vide."""
    x1, y1, x2, y2 = _clip_window(window, image_size)
    if x2 <= x1 or y2 <= y1:
        return None
    return float(np.median(norm[y1:y2, x1:x2]))


def median_in_box(norm, box, image_size):
    """Médiane de la carte sur la boîte ``box`` (xyxy, pixels image) ; None si vide.

    Chemin historique (boîte entière, tests) — :func:`attach_medians` préfère le
    masque puis la boîte érodée.
    """
    return median_in_window(norm, box, image_size)


def median_in_mask(norm, mask, window, image_size):
    """Médiane sur les pixels objet (``mask > 0``) dans ``window`` ; None si < 4.

    ``mask`` est le masque brut de l'entrée (résolution de travail) : il est ramené
    à ``image_size`` au plus proche (``imaging.mask_to_uint8``, même seuil que
    l'encodage), puis croisé avec la fenêtre déjà bornée.
    """
    from imaging import mask_to_uint8

    binary = mask_to_uint8(mask, image_size) > 0
    x1, y1, x2, y2 = _clip_window(window, image_size)
    if x2 <= x1 or y2 <= y1:
        return None
    picked = norm[y1:y2, x1:x2][binary[y1:y2, x1:x2]]
    if picked.size < MIN_MASK_PIXELS:
        return None
    return float(np.median(picked))


def median_for_entry(norm, entry, image_size):
    """Médiane d'une entrée : masque dans la boîte érodée, sinon boîte érodée.

    Ni l'un ni l'autre (boîte dégénérée, masque vide) : None, et la clé ``depth``
    reste absente — pas de médiane déguisée sur la boîte entière.
    """
    window = eroded_box(entry["box"])
    if window is None:
        return None
    mask = entry.get("mask")
    if mask is not None:
        try:
            masked = median_in_mask(norm, mask, window, image_size)
        except (TypeError, ValueError, AttributeError):
            masked = None
        if masked is not None:
            return masked
    return median_in_window(norm, window, image_size)


def attach_medians(norm, entries, image_size):
    """Joint ``entry["depth"]`` (médiane masque/érodée) à chaque entrée mesurable."""
    for entry in entries:
        try:
            median = median_for_entry(norm, entry, image_size)
        except (KeyError, TypeError, ValueError):
            median = None
        if median is not None:
            entry["depth"] = median
    return entries


def attach_frame_depth(ctx, image, entries: list, with_depth: bool, timers: dict):
    """Étage profondeur de ``run_frame`` : médiane par entrée, sous le verrou GPU.

    Sans ``with_depth``, sans modèle chargé, ou sans entrée (DETR n'a rien
    trouvé : aucune médiane à mesurer) : les entrées repartent intactes, sans
    forward (repli gracieux — le client bascule sur sa profondeur embarquée).
    Le poste ``depth_ms`` n'est émis que si le levier ``FAST_TIMING`` est vrai
    et qu'un forward a réellement eu lieu.
    Implémentation unique, partagée par les deux variantes du backend.
    """
    if not with_depth or ctx.get("depth_model") is None or not entries:
        return entries
    from timing import add_ms, elapsed_ms, now

    started = now()
    norm = depth_map_for(ctx["depth_model"], ctx.get("depth_processor"), image, ctx["device"])
    add_ms(timers, "depth_ms", elapsed_ms(started))
    return attach_medians(norm, entries, image.size)


def new_depth_throttle() -> dict:
    """État du throttle depth, UN par connexion WS (jamais global, jamais partagé)."""
    return {"frames": 0, "medians": []}


def depth_compute_due(state: dict, every: int) -> bool:
    """Faut-il calculer la carte pour cette frame demandée ? 1re frame : toujours.

    ``every`` ≤ 0 vaut 1 (throttle coupé). N'appeler que pour une frame avec
    ``with_depth`` : les frames muettes ne font pas avancer le compteur.
    """
    step = every if isinstance(every, int) and every >= 1 else 1
    due = state["frames"] % step == 0
    state["frames"] += 1
    return due


def cached_medians(entries: list) -> list:
    """Médianes d'une réponse calculée, par index (``None`` = objet non mesuré)."""
    return [entry.get("depth") if isinstance(entry, dict) else None for entry in entries]


def attach_cached_medians(entries: list, medians: list) -> list:
    """Répète les médianes de la dernière carte calculée (frames throttlées).

    Par index d'objet (les réponses sont triées par score décroissant) et sans
    jamais écraser une vraie mesure : seuls les objets sans clé ``depth``
    reçoivent la médiane mémorisée au même index.
    """
    for index, entry in enumerate(entries):
        if index >= len(medians) or medians[index] is None:
            continue
        if isinstance(entry, dict) and "depth" not in entry:
            entry["depth"] = medians[index]
    return entries


def depth_throttle_in(request, depth_state, every):
    """Avant l'inférence : requête à servir + plan ``(mémoriser, répéter)``.

    Carte due (1re frame, puis 1 sur ``every``) : la requête passe telle
    quelle et on mémorisera les médianes ; sinon ``with_depth`` est coupé
    (zéro coût GPU) et on répétera les médianes mémorisées. Sans
    ``with_depth`` ou sans état de connexion : la requête passe, rien à faire.
    """
    if not request.with_depth or depth_state is None:
        return request, False, None
    if depth_compute_due(depth_state, every):
        return request, True, None
    return (request.model_copy(update={"with_depth": False}), False,
            depth_state["medians"])


def depth_throttle_out(objects, cache, repeat, depth_state):
    """Après l'inférence : mémorise les médianes calculées ou répète le cache."""
    if cache:
        depth_state["medians"] = cached_medians(objects)
    elif repeat is not None:
        attach_cached_medians(objects, repeat)
