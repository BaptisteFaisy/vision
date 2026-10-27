"""Segmentation SAM 3.1 en local (GPU du poste), sans Modal ni HTTP.

  /tmp/sam3env/bin/python scripts/local_segment.py <photo> [prompt, ...]

Réutilise le backend tel quel (segmentation.load_model / run_segmentation) pour
prouver que la carte traite bien une vraie photo.
"""
import sys
import time
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

from PIL import Image  # noqa: E402

from segmentation import load_model, run_segmentation  # noqa: E402

MAX_PRINTED = 8


def summarise(result: dict) -> str:
    """Résumé lisible : dimensions, nombre d'objets, top scores."""
    lines = [f"image {result['width']}x{result['height']} — {len(result['objects'])} objet(s)"]
    for obj in result["objects"][:MAX_PRINTED]:
        x1, y1, x2, y2 = obj["box"]
        lines.append(
            f"  {obj['label']:<14} score {obj['score']:.3f}  box [{x1:.0f},{y1:.0f},{x2:.0f},{y2:.0f}]"
            f"  masque {len(obj['mask_b64'])} o (b64)"
        )
    if len(result["objects"]) > MAX_PRINTED:
        lines.append(f"  … {len(result['objects']) - MAX_PRINTED} de plus")
    return "\n".join(lines)


def print_peak() -> None:
    """Pic mémoire GPU de la session (alloué / réservé)."""
    import torch

    if not torch.cuda.is_available():
        return
    alloc = torch.cuda.max_memory_allocated() / 1e9
    reserved = torch.cuda.max_memory_reserved() / 1e9
    total = torch.cuda.mem_get_info()[1] / 1e9
    print(f"VRAM pic alloué {alloc:.2f} Go / réservé {reserved:.2f} Go / carte {total:.1f} Go")


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    path, prompts = argv[0], (argv[1:] or None)
    image = Image.open(path).convert("RGB")
    start = time.time()
    model, processor, device, dtype = load_model()
    print(f"chargé en {time.time() - start:.1f}s — device={device} dtype={dtype}")
    start = time.time()
    result = run_segmentation(model, processor, device, dtype, image, prompts)
    elapsed = time.time() - start
    print(summarise(result))
    print(f"latence {elapsed:.2f}s pour {len(result['objects'])} objet(s), prompts={prompts or 'défauts'}")
    print_peak()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
