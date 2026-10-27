"""Plafond de précision de SAM 3.1 et test d'un raffinement par recadrage (zoom).

  /tmp/sam3env/bin/python scripts/probe_precision.py <photo> [prompt]

1) mesure la résolution native du masque et prouve qu'elle plafonne le détail ;
2) re-segmente la boîte de chaque objet recadrée (marge) pour quantifier le gain
   réel de précision sur le GPU local.
"""
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

from segmentation import load_model, model_concept, resize_max_edge  # noqa: E402

WORK_EDGE = 1024
PROMPT_DEFAULT = "person"
MARGIN = 0.25


def masks_at_full_res(model, processor, image, prompt: str):
    """Segmentation d'une PIL image ; renvoie (masks bool origine, boîtes, scores)."""
    import torch

    width, height = image.size
    work = resize_max_edge(image, WORK_EDGE)
    pixels = processor(images=work, return_tensors="pt")["pixel_values"]
    text_inputs = {k: v.to("cuda") for k, v in processor(text=model_concept(prompt), return_tensors="pt").items()}
    with torch.inference_mode():
        vision = model.vision_encoder(pixels.to("cuda", torch.bfloat16))
        outputs = model(vision_embeds=vision, **text_inputs)
        found = processor.post_process_instance_segmentation(
            outputs, threshold=0.35, mask_threshold=0.5, target_sizes=[(height, width)]
        )[0]
    return found["masks"].cpu().numpy().astype(bool), found["boxes"].float().cpu().numpy(), found["scores"].float().cpu().numpy()


def native_mask_resolution(processor) -> tuple[int, int]:
    size = processor.image_processor.mask_size
    return (int(size.height), int(size.width))


def ceiling_proof(mask: np.ndarray, native: tuple[int, int]) -> float:
    """IoU entre le masque final et le même masque écrasé à la résolution native.

    ~1,0 prouve que l'upscale n'invente aucun détail : le plafond est la
    résolution native du masque, pas la taille de l'image envoyée.
    """
    height, width = mask.shape
    small = Image.fromarray((mask * 255).astype(np.uint8)).resize(native[::-1], Image.NEAREST)
    back = np.array(small.resize((width, height), Image.NEAREST)) > 127
    return iou(mask, back)


def mask_pixel_of(image_width: int, region_width: int, mask_width: int) -> float:
    """Côté (px image) représenté par un pixel du masque avant upscale."""
    return round(region_width / mask_width, 2)


def iou(a: np.ndarray, b: np.ndarray) -> float:
    inter, union = np.logical_and(a, b).sum(), np.logical_or(a, b).sum()
    return round(float(inter / union), 4) if union else 0.0


def box_with_margin(box, size, margin: float):
    width, height = size
    x1, y1, x2, y2 = box
    dx, dy = (x2 - x1) * margin, (y2 - y1) * margin
    return (max(0, int(x1 - dx)), max(0, int(y1 - dy)), min(width, int(x2 + dx)), min(height, int(y2 + dy)))


def paste_mask(mask: np.ndarray, crop_box, canvas):
    """Recolle un masque recadré dans le repère de l'image d'origine."""
    full = np.zeros(canvas, dtype=bool)
    x1, y1, x2, y2 = crop_box
    full[y1:y2, x1:x2] = mask
    return full


def main(argv) -> int:
    if not argv:
        print(__doc__)
        return 2
    path = argv[0]
    prompt = argv[1] if len(argv) > 1 else PROMPT_DEFAULT
    image = Image.open(path).convert("RGB")
    model, processor, device, dtype = load_model()
    height, width = image.size[::-1]
    native = native_mask_resolution(processor)
    print(f"photo {width}x{height} — prompt « {prompt} » — masque natif {native}")
    start = time.time()
    masks, boxes, scores = masks_at_full_res(model, processor, image, prompt)
    elapsed = time.time() - start
    print(f"passe 1 : {len(masks)} objet(s) en {elapsed:.2f}s — pixel de masque = "
          f"{mask_pixel_of(width, width, native[1])} px d'image")
    if len(masks):
        print(f"  preuve du plafond : IoU masque vs masque écrasé-{native[1]}-puis-réagrandi = "
              f"{ceiling_proof(masks[0], native)} (1,0 = aucun détail au-delà de {native[1]} px)")
    base_px = mask_pixel_of(width, width, native[1])
    for index, (mask, box, score) in enumerate(zip(masks, boxes, scores)):
        crop_box = box_with_margin(box, image.size, MARGIN)
        start = time.time()
        refined, _, _ = masks_at_full_res(model, processor, image.crop(crop_box), prompt)
        elapsed = time.time() - start
        region_w = crop_box[2] - crop_box[0]
        best, full = None, None
        if len(refined):
            offsets = [iou(paste_mask(m, crop_box, mask.shape), mask) for m in refined]
            best = refined[int(np.argmax(offsets))]
            full = paste_mask(best, crop_box, mask.shape)
        gain = mask_pixel_of(width, region_w, native[1])
        same = iou(mask, full) if full is not None else 0.0
        detail = ""
        if best is not None:
            detail = f", IoU vs masque 288 natif = {ceiling_proof(best, native)}"
        print(f"  objet {index} score {score:.3f} boîte {list(map(int, box))} → recadrage {region_w}px : "
              f"pixel de masque {gain} px ({round(mask_pixel_of(width, width, native[1]) / max(gain, 0.01), 1)}x plus fin), "
              f"{elapsed:.2f}s, IoU avec la passe 1 = {same}{detail}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
