"""Débit SAM 3.1 sur frames vidéo, sur la GPU Modal demandée (défaut A100 40 Go).

  cd backend && PROBE_GPU=A100-40GB uv run --with modal modal run probe_video_modal.py

Mêmes mesures que scripts/probe_video.py (local) : étapes par frame + montée en
lot de l'encodeur vision, pour situer 720p et le batching sur la même base.
"""
import json
import os
import pathlib
import time

import modal

REQ = str(pathlib.Path(__file__).resolve().parent / "requirements.txt")
image = (
    modal.Image.debian_slim(python_version="3.12")
    .env({"PYTHONPATH": "/root"})
    .pip_install_from_requirements(REQ)
)

GPU = os.environ.get("PROBE_GPU", "A100-40GB")
FRAMES_DIR = "/tmp/frames720"
BATCHES = (1, 4, 8, 16)
WORK_EDGE = 1024
LIMIT = 40
MAX_PROMPT_FRAMES = 5

app = modal.App(f"sam3-video-{GPU}")


def _stage_timing(model, processor, images, prompt: str) -> list[dict]:
    """Encodeur vision puis un prompt, étapes chronométrées avec synchronisation."""
    import numpy as np
    import torch
    from PIL import Image

    torch_mod = torch
    stages = []
    for image in images:
        t0 = time.time()
        pixels = processor(images=image, return_tensors="pt")["pixel_values"].to("cuda", torch_mod.bfloat16)
        torch_mod.cuda.synchronize()
        t1 = time.time()
        with torch_mod.inference_mode():
            vision = model.vision_encoder(pixels)
        torch_mod.cuda.synchronize()
        t2 = time.time()
        text = {k: v.to("cuda") for k, v in processor(text=prompt, return_tensors="pt").items()}
        with torch_mod.inference_mode():
            out = model(vision_embeds=vision, **text)
            found = processor.post_process_instance_segmentation(
                out, threshold=0.35, mask_threshold=0.5, target_sizes=[(image.height, image.width)]
            )[0]
            masks = found["masks"].cpu().numpy().astype(np.uint8)
        torch_mod.cuda.synchronize()
        t3 = time.time()
        stages.append({
            "preprocess_ms": round((t1 - t0) * 1e3, 1),
            "vision_ms": round((t2 - t1) * 1e3, 1),
            "detr_post_ms": round((t3 - t2) * 1e3, 1),
            "detections": int(len(found["scores"])),
            "mask_px": int(masks[0].shape[0] * masks[0].shape[1]) if masks.size else 0,
        })
    return stages


def _batch_scaling(model, processor, images, iterations: int = 4) -> list[dict]:
    """ms/frame et VRAM du seul encodeur vision, par taille de lot."""
    import torch

    tensors = [
        processor(images=image, return_tensors="pt")["pixel_values"][0] for image in images[:max(BATCHES)]
    ]
    results = []
    for size in BATCHES:
        try:
            batch = torch.stack(tensors[:size]).to("cuda", torch.bfloat16)
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
            results.append({
                "batch": size,
                "ms_per_frame": round(elapsed / iterations / size * 1e3, 1),
                "fps": round(size * iterations / elapsed, 1),
                "peak_gb": round(float(torch.cuda.max_memory_allocated()) / 1e9, 2),
            })
        except Exception as exc:  # VRAM insuffisante ou forme refusée
            results.append({"batch": size, "error": f"{type(exc).__name__}: {str(exc)[:120]}"})
    return results


@app.function(image=image, gpu=GPU, timeout=1800, cpu=8, memory=32768)
def video_bench(frames: list[bytes], prompt: str = "person") -> dict:
    import torch
    from PIL import Image
    from transformers import Sam3Model, Sam3Processor

    name, cap = torch.cuda.get_device_name(0), torch.cuda.get_device_capability()
    start = time.time()
    model = Sam3Model.from_pretrained("unsloth/sam3.1", dtype=torch.bfloat16).to("cuda").eval()
    processor = Sam3Processor.from_pretrained("unsloth/sam3.1")
    report = {
        "gpu": GPU,
        "device": f"{name} sm_{cap[0]}{cap[1]}",
        "vram_total_gb": round(torch.cuda.mem_get_info()[1] / 1e9, 1),
        "load_s": round(time.time() - start, 1),
        "frames": len(frames),
        "prompt": prompt,
    }
    images = [Image.open(__import__("io").BytesIO(raw)).convert("RGB") for raw in frames]
    report["frame_px"] = list(images[0].size)
    model.vision_encoder(processor(images=images[0], return_tensors="pt")["pixel_values"].to("cuda", torch.bfloat16))
    start = time.time()
    stages = _stage_timing(model, processor, images, prompt)
    total = time.time() - start
    report["end_to_end"] = {
        "seconds": round(total, 2),
        "fps": round(len(images) / total, 2),
        "vision_ms": round(sum(s["vision_ms"] for s in stages) / len(stages), 1),
        "detr_post_ms": round(sum(s["detr_post_ms"] for s in stages) / len(stages), 1),
        "preprocess_ms": round(sum(s["preprocess_ms"] for s in stages) / len(stages), 1),
        "detections_per_frame": round(sum(s["detections"] for s in stages) / len(stages), 2),
    }
    report["batch_scaling"] = _batch_scaling(model, processor, images)
    report["vram_peak_gb"] = round(float(torch.cuda.max_memory_allocated()) / 1e9, 2)
    print("RESULT " + json.dumps(report))
    return report


@app.local_entrypoint()
def main(prompt: str = "person"):
    raw = [pathlib.Path(p).read_bytes() for p in sorted(pathlib.Path(FRAMES_DIR).glob("*.jpg"))[:LIMIT]]
    if not raw:
        raise ValueError(f"aucune frame JPEG dans {FRAMES_DIR} : régénère la séquence avant le run")
    print(f"envoi de {len(raw)} frames depuis {FRAMES_DIR} → {GPU}")
    report = video_bench.remote(raw, prompt)
    print(json.dumps(report, indent=2))
