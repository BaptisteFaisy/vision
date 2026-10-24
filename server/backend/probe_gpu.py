"""Sonde VRAM SAM 3.1 : pic mémoire GPU par taille d'image, sur la GPU demandée.

Réutilise l'image de modal_app (couche pip déjà en cache) dans une app Modal
séparée, pour ne pas entrer en collision avec le déploiement sam3-vision.

  cd backend && uv run --with modal env PROBE_GPU=L4 PROBE_DTYPE=bfloat16 \
      modal run probe_gpu.py::probe
"""
import os

import modal

from modal_app import image

GPU = os.environ.get("PROBE_GPU", "L4")
DTYPE = os.environ.get("PROBE_DTYPE", "bfloat16")
SIDES = [int(x) for x in os.environ.get("PROBE_SIDES", "512,1024,2048").split(",")]
PROMPTS = int(os.environ.get("PROBE_PROMPTS", "1"))

app = modal.App(f"sam3-probe-{GPU}-{DTYPE}")


@app.function(gpu=GPU, timeout=1800, cpu=4, memory=16384)
def probe():
    import time

    import torch
    from PIL import Image
    from transformers import Sam3Model, Sam3Processor

    dtype = getattr(torch, DTYPE)
    t0 = time.time()
    model = Sam3Model.from_pretrained("unsloth/sam3.1", dtype=dtype).to("cuda").eval()
    proc = Sam3Model and Sam3Processor.from_pretrained("unsloth/sam3.1")
    name, cap = torch.cuda.get_device_name(0), torch.cuda.get_device_capability()
    out = {
        "gpu": GPU,
        "device": f"{name} sm_{cap[0]}{cap[1]}",
        "dtype": DTYPE,
        "prompts": PROMPTS,
        "load_s": round(time.time() - t0, 1),
        "weights_gb": round(torch.cuda.memory_allocated() / 1e9, 2),
        "runs": [],
    }
    text = ", ".join(["sofa"] * PROMPTS) if PROMPTS > 1 else "sofa"
    for side in SIDES:
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
        img = Image.new("RGB", (side, side), (120, 90, 60))
        inputs = proc(images=img, text=text, return_tensors="pt").to("cuda", dtype)
        t1 = time.time()
        with torch.inference_mode():
            o = model(**inputs)
            r = proc.post_process_instance_segmentation(
                o, threshold=0.3, mask_threshold=0.5,
                target_sizes=inputs.get("original_sizes").tolist(),
            )[0]
        out["runs"].append({
            "side": side,
            "alloc_peak_gb": round(torch.cuda.max_memory_allocated() / 1e9, 2),
            "reserved_peak_gb": round(torch.cuda.max_memory_reserved() / 1e9, 2),
            "inference_s": round(time.time() - t1, 2),
            "masks": list(r["masks"].shape),
        })
    free, total = torch.cuda.mem_get_info()
    out["device_total_gb"] = round(total / 1e9, 2)
    out["context_overhead_gb"] = round((total - free) / 1e9, 2)
    print("RESULT " + repr(out))
    return out
