"""Débit SAM 3.1 sur des frames 1080p (modèle image appelé frame par frame).

  /tmp/sam3env/bin/python scripts/probe_video.py /tmp/frames [prompt ...]

Mesure par étape (préprocesseur, encodeur vision, passes texte, post-traitement)
pour situer le goulot d'un traitement vidéo « en continu ».
"""
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

from imaging import encode_mask_png_b64  # noqa: E402
from segmentation import load_model, model_concept, resize_max_edge  # noqa: E402

WORK_EDGE = 1024
LIMIT = 40


def frames(folder: str) -> list[Path]:
    paths = sorted(Path(folder).glob("*.jpg")) + sorted(Path(folder).glob("*.png"))
    return paths[:LIMIT]


def one_frame(model, processor, path, prompts: list[str]) -> dict:
    """Une frame, lue depuis ``path`` : encodeur vision une fois, puis un prompt après l'autre."""
    import torch

    t_read = time.time()
    image = Image.open(path).convert("RGB")
    work = resize_max_edge(image, WORK_EDGE)
    t_read = time.time() - t_read
    width, height = image.size
    t0 = time.time()
    pixels = processor(images=work, return_tensors="pt")["pixel_values"].to("cuda", torch.bfloat16)
    torch.cuda.synchronize()
    t1 = time.time()
    with torch.inference_mode():
        vision = model.vision_encoder(pixels)
    torch.cuda.synchronize()
    t2 = time.time()
    text_times, encode_times, detections = [], [], 0
    for prompt in prompts:
        text = {k: v.to("cuda") for k, v in processor(text=model_concept(prompt), return_tensors="pt").items()}
        start = time.time()
        with torch.inference_mode():
            out = model(vision_embeds=vision, **text)
            found = processor.post_process_instance_segmentation(
                out, threshold=0.35, mask_threshold=0.5, target_sizes=[(height, width)]
            )[0]
            t_enc = time.time()
            for mask in found["masks"].cpu().numpy().astype(np.uint8):
                encode_mask_png_b64(mask * 255, (width, height))
            encode_times.append(time.time() - t_enc)
        torch.cuda.synchronize()
        text_times.append(time.time() - start)
        detections += len(found["scores"])
    return {
        "read_ms": round(t_read * 1e3, 1),
        "encode_ms": round(sum(encode_times) * 1e3, 1),
        "preprocess_ms": round((t1 - t0) * 1e3, 1),
        "vision_ms": round((t2 - t1) * 1e3, 1),
        "text_ms": [round(t * 1e3, 1) for t in text_times],
        "detections": detections,
    }


def main(argv) -> int:
    import torch

    folder = argv[0] if argv else "/tmp/frames"
    prompts = argv[1:] or ["person"]
    paths = frames(folder)
    if not paths:
        print(f"aucune frame dans {folder}")
        return 2
    model, processor, device, dtype = load_model()
    print(f"{len(paths)} frames {Image.open(paths[0]).size} — prompts {prompts} — {device}/{dtype}")
    one_frame(model, processor, paths[0], prompts)  # chauffe cuDNN, non comptée
    torch.cuda.reset_peak_memory_stats()
    start = time.time()
    stages = [one_frame(model, processor, p, prompts) for p in paths]
    total = time.time() - start
    fps = len(paths) / total
    vision = np.mean([s["vision_ms"] for s in stages])
    text = np.mean([sum(s["text_ms"]) for s in stages])
    pre = np.mean([s["preprocess_ms"] for s in stages])
    print(f"total {total:.1f}s pour {len(paths)} frames → {fps:.2f} fps (soit {1 / fps * 1e3:.0f} ms/frame)")
    read = np.mean([s["read_ms"] for s in stages])
    enc = np.mean([s["encode_ms"] for s in stages])
    print(f"  détail/frame : lecture+resize PIL {read:.0f} ms | préprocesseur {pre:.0f} ms | "
          f"encodeur vision {vision:.0f} ms | DETR+post {text:.0f} ms dont encodage PNG masques {enc:.0f} ms")
    print(f"détections/frame {np.mean([s['detections'] for s in stages]):.1f} — "
          f"VRAM pic {torch.cuda.max_memory_allocated() / 1e9:.2f} Go")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
