"""SAM 3.1 loading and instance segmentation.

torch / transformers are imported inside the functions so this module stays
importable (for tests and tooling) without the heavy GPU stack installed.
"""

from __future__ import annotations

from imaging import encode_mask_png_b64, resize_max_edge, scale_factors

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
    """English concept handed to the model; unknown prompts are passed through."""
    key = prompt.strip().lower()
    for article in _ARTICLES:
        if key.startswith(article):
            key = key[len(article) :].strip()
            break
    return CONCEPT_ALIASES.get(key, prompt)


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
    """Validate client prompts, trimming blanks and removing duplicates."""
    cleaned: list[str] = []
    for raw in prompts or []:
        text = " ".join(str(raw).split())
        if not text:
            continue
        if len(text) > MAX_PROMPT_CHARS:
            raise PromptError(f"prompt too long (max {MAX_PROMPT_CHARS} chars): {text[:32]}...")
        if text not in cleaned:
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


def _run_prompt(model, processor, device, vision, prompt: str, work_size, image_size) -> list[dict]:
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
    scale_x, scale_y = scale_factors(work_size, image_size)
    boxes = detection["boxes"].float().cpu().numpy()
    scores = detection["scores"].float().cpu().numpy()
    masks = detection["masks"].cpu().numpy()
    return [
        {
            "label": prompt,
            "score": round(float(score), 4),
            "box": _clamp_box(box, scale_x, scale_y, image_size),
            "mask_b64": encode_mask_png_b64(mask, image_size),
        }
        for mask, box, score in zip(masks, boxes, scores)
    ]


def segment_image(model, processor, device, dtype, image, prompts: list[str]) -> dict:
    """Segment ``image`` for every prompt and build the SPEC-shaped payload."""
    torch = _torch()
    image_size = image.size
    work = resize_max_edge(image, SEG_MAX_EDGE)
    pixel_values = processor(images=work, return_tensors="pt")["pixel_values"]
    with torch.inference_mode():
        vision = model.vision_encoder(pixel_values.to(device=device, dtype=dtype))
    objects: list[dict] = []
    for prompt in prompts:
        objects.extend(_run_prompt(model, processor, device, vision, prompt, work.size, image_size))
    objects.sort(key=lambda item: item["score"], reverse=True)
    return {"width": image_size[0], "height": image_size[1], "objects": objects[:MAX_OBJECTS]}


def run_segmentation(model, processor, device, dtype, image, prompts=None) -> dict:
    """Server-side concept choice (defaults + generic fallback) when prompts is None."""
    if prompts is not None:
        return segment_image(model, processor, device, dtype, image, normalize_prompts(prompts))
    result = segment_image(model, processor, device, dtype, image, list(DEFAULT_PROMPTS))
    if result["objects"]:
        return result
    return segment_image(model, processor, device, dtype, image, list(FALLBACK_PROMPTS))