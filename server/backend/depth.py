"""Profondeur monoculaire (Depth Anything V2 Small) fusionnée dans ``/live``.

``with_depth`` (``POST /live`` et ``WS /live/ws``) : la frame déjà décodée passe
dans DA2-Small (~25 M params, ~50 Mo VRAM) et chaque objet renvoyé porte sa
médiane de proximité (``depth``, 0..1, **grand = proche** — même convention que
la carte on-device, voir ``DepthMap.kt`` : la sortie brute de DA2 est une
disparité). Médiane sur le **masque** (pixels objet dans la boîte érodée),
repli boîte érodée (25 % par côté, comme ``DepthReadings`` embarqué) : la boîte
entière lirait le fond sur un objet fin (poteau). Client muet = aucune clé,
aucun coût ; modèle absent = repli gracieux (pas de clé, jamais d'erreur).

Imports lourds (torch/transformers) dans les fonctions : ce module reste
importable sans GPU, comme ``segmentation``.
"""

from __future__ import annotations

import numpy as np

MODEL_ID = "depth-anything/Depth-Anything-V2-Small-hf"
# Carte plate (aucune information) : ni proche ni loin.
NEUTRAL_DEPTH = 0.5
# Érosion par côté avant la médiane : les bords portent le fond (voir DepthReadings.kt).
EROSION_FRACTION = 0.25
# Pixels objet minimaux dans la fenêtre : en deçà, la médiane masque est du bruit.
MIN_MASK_PIXELS = 4


def load_depth(device: str, dtype):
    """Charge DA2-Small une fois (conteneur) : ``(modèle, processeur)``."""
    from transformers import AutoImageProcessor, AutoModelForDepthEstimation

    model = AutoModelForDepthEstimation.from_pretrained(MODEL_ID)
    model.to(device)
    if dtype is not None:
        model.to(dtype)
    return model.eval(), AutoImageProcessor.from_pretrained(MODEL_ID)


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

    Sans ``with_depth`` ou sans modèle chargé : les entrées repartent intactes
    (repli gracieux — le client bascule sur sa profondeur embarquée). Le poste
    ``depth_ms`` n'est émis que si le levier ``FAST_TIMING`` est vrai.
    Implémentation unique, partagée par les deux variantes du backend.
    """
    if not with_depth or ctx.get("depth_model") is None:
        return entries
    from timing import add_ms, elapsed_ms, now

    started = now()
    norm = depth_map_for(ctx["depth_model"], ctx.get("depth_processor"), image, ctx["device"])
    add_ms(timers, "depth_ms", elapsed_ms(started))
    return attach_medians(norm, entries, image.size)
