"""Le batch de frames améliore-t-il le débit du ViT de SAM 3.1 sur une 3060 Ti ?

  /tmp/sam3env/bin/python scripts/probe_batch.py /tmp/frames720 [prompt]

L'encodeur vision reçoit toujours du 1008² : grouper N frames ne change pas la
VRAM des poids, seulement celle des activations. Mesure ms/frame par taille de lot.
"""
import sys
import time
from pathlib import Path

from PIL import Image

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

from segmentation import load_model, resize_max_edge  # noqa: E402

BATCHES = (1, 2, 4, 8)
ITERATIONS = 4
WORK_EDGE = 1024


def frame_tensors(processor, paths, count: int):
    """Empile `count` frames 1080p/720p déjà ramenées à la résolution de travail."""
    import torch

    tensors = []
    for path in paths[:count]:
        image = resize_max_edge(Image.open(path).convert("RGB"), WORK_EDGE)
        tensors.append(processor(images=image, return_tensors="pt")["pixel_values"][0])
    return torch.stack(tensors).to("cuda", torch.bfloat16)


def time_vision(model, batch, iterations: int) -> tuple[float, float]:
    """(ms/frame, pic VRAM Go) pour un lot de `batch` frames."""
    import torch

    n = batch.shape[0]
    for _ in range(2):
        with torch.inference_mode():
            model.vision_encoder(batch)
    torch.cuda.synchronize()
    torch.cuda.reset_peak_memory_stats()
    start = time.time()
    for _ in range(iterations):
        with torch.inference_mode():
            model.vision_encoder(batch)
    torch.cuda.synchronize()
    elapsed = time.time() - start
    return round(elapsed / iterations / batch.shape[0] * 1e3, 1), round(float(torch.cuda.max_memory_allocated()) / 1e9, 2)


def main(argv) -> int:
    folder = Path(argv[0] if argv else "/tmp/frames720")
    paths = sorted(folder.glob("*.jpg"))
    model, processor, _, _ = load_model()
    print(f"{len(paths)} frames {Image.open(paths[0]).size} — lots testés {BATCHES}")
    for batch_size in BATCHES:
        try:
            batch = frame_tensors(processor, paths, batch_size)
            per_frame, vram = time_vision(model, batch, ITERATIONS)
            print(f"  lot {batch_size:>2} : {per_frame:6.1f} ms/frame ({1000 / per_frame:.1f} fps) — VRAM pic {vram} Go")
        except Exception as exc:
            print(f"  lot {batch_size:>2} : ÉCHEC {type(exc).__name__}: {str(exc)[:100]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
