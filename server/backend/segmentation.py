"""SAM 3.1 loading and instance segmentation.

torch / transformers are imported inside the functions so this module stays
importable (for tests and tooling) without the heavy GPU stack installed.
"""

from __future__ import annotations

from imaging import (
    DEFAULT_MASK_ENCODING,
    check_mask_encoding,
    encode_mask_b64,
    resize_max_edge,
    scale_factors,
)

MODEL_ID = "unsloth/sam3.1"
# Generic, broad concept list used when /segment gets prompts == null.
DEFAULT_PROMPTS = (
    "person",
    "car",
    "animal",
    "plant",
    "furniture",
    "building",
    "food",
    "clothing",
)
# Fallback concepts when the generic list finds nothing at all.
FALLBACK_PROMPTS = ("object", "thing")
MAX_PROMPTS = 16
MAX_OBJECTS = 24
MAX_PROMPT_CHARS = 64
SEG_MAX_EDGE = 1024
SCORE_THRESHOLD = 0.35
MASK_THRESHOLD = 0.5


# SAM 3.1's text encoder is an English CLIP: French concepts are mapped to their
# English counterpart for the model, while the label stays as the user typed it.
CONCEPT_ALIASES = {
    "personne": "person",
    "personnes": "person",
    "gens": "person",
    "homme": "man",
    "femme": "woman",
    "enfant": "child",
    "voiture": "car",
    "voitures": "car",
    "véhicule": "vehicle",
    "camion": "truck",
    "vélo": "bicycle",
    "moto": "motorcycle",
    "animal": "animal",
    "animaux": "animal",
    "chien": "dog",
    "chat": "cat",
    "oiseau": "bird",
    "cheval": "horse",
    "arbre": "tree",
    "plante": "plant",
    "fleur": "flower",
    "maison": "house",
    "immeuble": "building",
    "bâtiment": "building",
    "rue": "street",
    "route": "road",
    "table": "table",
    "chaise": "chair",
    "canapé": "sofa",
    "lit": "bed",
    "ordinateur": "computer",
    "téléphone": "phone",
    "nourriture": "food",
    "repas": "meal",
    "fruit": "fruit",
    "légume": "vegetable",
    "vêtement": "clothing",
    "chaussure": "shoe",
    "sac": "bag",
    "objet": "object",
    "chose": "thing",
    "texte": "text",
    "panneau": "sign",
    "ciel": "sky",
    "mer": "sea",
    "montagne": "mountain",
    "eau": "water",
}
_ARTICLES = ("un ", "une ", "le ", "la ", "les ", "des ", "du ", "l'", "the ", "a ", "an ")


class PromptError(ValueError):
    """Raised when the requested concept prompts cannot be used (HTTP 400)."""


def model_concept(prompt: str) -> str:
    """Concept anglais remis au modèle.

    Les alias FR/EN sont traduits ; un prompt inconnu est remis **normalisé** (minuscules,
    articles retirés, espaces pliés) au lieu d'être recopié tel quel : c'est aussi la clé
    de dédoublonnage de ``normalize_prompts``, donc « chaise », « Chair » et « une chaise »
    ne peuvent plus désigner deux concepts différents pour le serveur.
    """
    key = prompt.strip().lower()
    for article in _ARTICLES:
        if key.startswith(article):
            key = key[len(article) :].strip()
            break
    return CONCEPT_ALIASES.get(key, key)


def _torch():
    import torch

    return torch


def device_label() -> str:
    """Return ``cuda`` when a GPU is visible, ``cpu`` otherwise."""
    return "cuda" if _torch().cuda.is_available() else "cpu"


def load_model(model_id: str = MODEL_ID):
    """Load the SAM 3.1 checkpoint once and return (model, processor, device, dtype)."""
    torch = _torch()
    from transformers import Sam3Model, Sam3Processor

    device = device_label()
    dtype = torch.bfloat16 if device == "cuda" else torch.float32
    model = Sam3Model.from_pretrained(model_id, dtype=dtype).to(device).eval()
    processor = Sam3Processor.from_pretrained(model_id)
    return model, processor, device, dtype


def normalize_prompts(prompts) -> list[str]:
    """Validate client prompts, trimming blanks and removing duplicates.

    Le dédoublonnage se fait sur le concept **modèle** (après ``CONCEPT_ALIASES``) et plus
    sur le texte brut : sinon « chaise » et « chair » passent tous les deux et coûtent deux
    passes DETR pour le même concept anglais (mesuré : +14 ms serveur, +5 masques encodés
    pour rien, +4,5 Ko de réponse). Le label renvoyé reste celui du **premier** prompt saisi.
    """
    cleaned: list[str] = []
    seen: set[str] = set()
    for raw in prompts or []:
        text = " ".join(str(raw).split())
        if not text:
            continue
        if len(text) > MAX_PROMPT_CHARS:
            raise PromptError(f"prompt too long (max {MAX_PROMPT_CHARS} chars): {text[:32]}...")
        key = model_concept(text)
        if key in seen:
            continue
        seen.add(key)
        cleaned.append(text)
    if not cleaned:
        raise PromptError("prompts must contain at least one non-empty concept")
    if len(cleaned) > MAX_PROMPTS:
        raise PromptError(f"too many prompts (max {MAX_PROMPTS})")
    return cleaned


def _clamp_box(box, scale_x: float, scale_y: float, size: tuple[int, int]) -> list[float]:
    """Scale a work-resolution xyxy box to image pixels and clamp it inside."""
    width, height = size
    x1, y1, x2, y2 = (float(value) for value in box)
    return [
        round(min(max(x1 * scale_x, 0.0), float(width)), 2),
        round(min(max(y1 * scale_y, 0.0), float(height)), 2),
        round(min(max(x2 * scale_x, 0.0), float(width)), 2),
        round(min(max(y2 * scale_y, 0.0), float(height)), 2),
    ]


def _top_indices(scores, keep: int) -> list[int]:
    """Indices des ``keep`` meilleurs scores, ordre stable (à égalité, ordre de détection).

    Borer un concept à ``keep`` objets (``keep`` ≥ ``max_objects``) ne retire que des objets
    qui **ne peuvent pas** entrer dans le top global : ``keep`` objets du même concept les
    précèdent dans le tri stable, donc ils passeraient aussi avant eux. La sélection finale
    est identique à « tout garder, trier, tronquer » — sans encoder les masques jetés.
    """
    if keep <= 0:
        return []
    return sorted(range(len(scores)), key=lambda index: -float(scores[index]))[:keep]


def detection_entries(detection, label: str, work_size, image_size,
                      keep: int = MAX_OBJECTS) -> list[dict]:
    """Une détection SAM3 -> objets du contrat, **masque non encodé** (tableau brut).

    L'encodage (1,015 ms par masque en PNG) est le poste le plus cher du CPU serveur et il
    était payé pour **chaque** détection, y compris celles jetées par ``max_objects`` : il
    est donc reporté après le tri global, dans :func:`finalize`. Le ``label`` reste le prompt
    tel que saisi (pas le concept anglais) — contrat historique.
    """
    scale_x, scale_y = scale_factors(work_size, image_size)
    boxes = detection["boxes"].float().cpu().numpy()
    scores = detection["scores"].float().cpu().numpy()
    masks = detection["masks"].cpu().numpy()
    return [
        {
            "label": label,
            "score": round(float(scores[index]), 4),
            "box": _clamp_box(boxes[index], scale_x, scale_y, image_size),
            "mask": masks[index],
        }
        for index in _top_indices(scores, keep)
    ]


def finalize(entries: list[dict], image_size, max_objects: int = MAX_OBJECTS,
             mask_encoding: str = DEFAULT_MASK_ENCODING, mask_format=None,
             mask_edge: int = 0) -> dict:
    """Tri global, troncature, puis encodage des **seuls** objets renvoyés.

    Renvoie la charge utile du contrat (``width``/``height``/``objects``) augmentée de
    ``mask_encoding`` : la représentation **appliquée** (jamais absente), pour qu'un client
    sache décoder ``mask_b64`` sans deviner. Point d'entrée partagé par ``/segment``
    (``segment_image``) et ``/live`` (``fast_core``) — un seul chemin d'encodage.

    ``mask_format="rle"`` (+ ``mask_edge``, défaut 256) : masques en runs LEB128 sur
    grille réduite, chaque objet portant ``mask_scale`` (ratio grille/frame) pour que
    le client redérive les dimensions. Absent = chemin historique inchangé.
    """
    from imaging import encode_mask_rle_b64, rle_grid

    if (mask_format or "").strip().lower() == "rle":
        grid, scale = rle_grid(tuple(image_size), mask_edge or 0)
        ordered = sorted(entries, key=lambda item: item["score"], reverse=True)
        return {
            "width": image_size[0],
            "height": image_size[1],
            "mask_encoding": "rle",
            "objects": [
                {
                    "label": item["label"],
                    "score": item["score"],
                    "box": item["box"],
                    "mask_b64": encode_mask_rle_b64(item["mask"], grid),
                    "mask_scale": scale,
                }
                for item in ordered[:max_objects]
            ],
        }
    encoding = check_mask_encoding(mask_encoding)
    ordered = sorted(entries, key=lambda item: item["score"], reverse=True)
    return {
        "width": image_size[0],
        "height": image_size[1],
        "mask_encoding": encoding,
        "objects": [
            {
                "label": item["label"],
                "score": item["score"],
                "box": item["box"],
                "mask_b64": encode_mask_b64(item["mask"], image_size, encoding),
            }
            for item in ordered[:max_objects]
        ],
    }


def _run_prompt(model, processor, device, vision, prompt: str, work_size, image_size,
                keep: int = MAX_OBJECTS) -> list[dict]:
    """Run one text prompt against pre-computed vision embeddings."""
    torch = _torch()
    text_inputs = {
        name: value.to(device)
        for name, value in processor(text=model_concept(prompt), return_tensors="pt").items()
    }
    with torch.inference_mode():
        outputs = model(vision_embeds=vision, **text_inputs)
        detection = processor.post_process_instance_segmentation(
            outputs,
            threshold=SCORE_THRESHOLD,
            mask_threshold=MASK_THRESHOLD,
            target_sizes=[(work_size[1], work_size[0])],
        )[0]
    return detection_entries(detection, prompt, work_size, image_size, keep)


def segment_image(model, processor, device, dtype, image, prompts: list[str],
                  work_edge: int = SEG_MAX_EDGE, max_objects: int = MAX_OBJECTS,
                  mask_encoding: str = DEFAULT_MASK_ENCODING) -> dict:
    """Segment ``image`` for every prompt and build the SPEC-shaped payload.

    ``work_edge`` caps the resolution handed to the encoder (the live route trades
    it for latency), ``max_objects`` caps the score-sorted slice, ``mask_encoding``
    choisit la représentation des masques (``png`` par défaut — le contrat historique
    des APK installés — ``bits`` sur demande, ``none`` pour ne renvoyer que les boîtes).
    """
    torch = _torch()
    image_size = image.size
    work = resize_max_edge(image, work_edge)
    pixel_values = processor(images=work, return_tensors="pt")["pixel_values"]
    with torch.inference_mode():
        vision = model.vision_encoder(pixel_values.to(device=device, dtype=dtype))
    objects: list[dict] = []
    for prompt in prompts:
        objects.extend(_run_prompt(model, processor, device, vision, prompt, work.size,
                                   image_size, keep=max_objects))
    return finalize(objects, image_size, max_objects, mask_encoding)


def run_segmentation(model, processor, device, dtype, image, prompts=None,
                     mask_encoding: str = DEFAULT_MASK_ENCODING) -> dict:
    """Server-side concept choice (defaults + generic fallback) when prompts is None.

    Le défaut reste ``png`` ici : ``/segment`` et ``/analyze`` servent le contrat historique
    (docs/SPEC.md) ; c'est ``/live`` qui négocie ``bits`` avec le client moderne.
    """
    if prompts is not None:
        return segment_image(model, processor, device, dtype, image, normalize_prompts(prompts),
                             mask_encoding=mask_encoding)
    result = segment_image(model, processor, device, dtype, image, list(DEFAULT_PROMPTS),
                           mask_encoding=mask_encoding)
    if result["objects"]:
        return result
    return segment_image(model, processor, device, dtype, image, list(FALLBACK_PROMPTS),
                         mask_encoding=mask_encoding)