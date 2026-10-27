"""Peut-on sortir du 1008 px / masque 288 px de SAM 3.1 sur une 3060 Ti ?

  /tmp/sam3env/bin/python scripts/probe_res.py <photo> [prompt]

Boucle les résolutions demandées au préprocesseur et renvoie, pour chacune :
résolution native du masque, latence, pic VRAM, et si le forward craque.
"""
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

from segmentation import load_model, model_concept  # noqa: E402

EDGES = (1008, 1344, 1680)
PROMPT = "person"
MAX_PRINTED = 3


def run_at(model, processor, image, prompt: str, edge: int) -> dict:
    """Une inférence à résolution `edge` ; nan si le modèle refuse cette taille."""
    import torch

    processor.image_processor.size = {"height": edge, "width": edge}
    pixels = processor(images=image, return_tensors="pt")["pixel_values"]
    text = {k: v.to("cuda") for k, v in processor(text=model_concept(prompt), return_tensors="pt").items()}
    torch.cuda.reset_peak_memory_stats()
    start = time.time()
    with torch.inference_mode():
        vision = model.vision_encoder(pixels.to("cuda", torch.bfloat16))
        out = model(vision_embeds=vision, **text)
        found = processor.post_process_instance_segmentation(out, threshold=0.35, mask_threshold=0.5)[0]
    return {
        "edge": edge,
        "inference_s": round(time.time() - start, 2),
        "alloc_peak_gb": round(torch.cuda.max_memory_allocated() / 1e9, 2),
        "reserved_peak_gb": round(torch.cuda.max_memory_reserved() / 1e9, 2),
        "masks": list(found["masks"].shape),
        "detected": int(len(found["scores"])),
        "scores": [round(float(s), 3) for s in found["scores"][:MAX_PRINTED]],
    }


def main(argv) -> int:
    path = argv[0] if argv else str(Path.home() / "Téléchargements/1000012232.jpg")
    prompt = argv[1] if len(argv) > 1 else PROMPT
    image = Image.open(path).convert("RGB")
    model, processor, _, _ = load_model()
    print(f"{path.split('/')[-1]} {image.size} prompt « {prompt} »")
    for edge in EDGES:
        try:
            print(run_at(model, processor, image, prompt, edge))
        except Exception as exc:
            print(f"edge={edge} ÉCHEC {type(exc).__name__}: {str(exc)[:140]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
