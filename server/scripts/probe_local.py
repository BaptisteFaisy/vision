"""Sonde VRAM/latence locale de SAM 3.1 (même protocole que backend/probe_gpu.py).

  /tmp/sam3env/bin/python scripts/probe_local.py

Mesure : pic mémoire GPU par taille d'image et par nombre de prompts, dtype bf16
(chemin utilisé par segmentation.py sur CUDA).
"""
import json
import time

import torch
from PIL import Image
from transformers import Sam3Model, Sam3Processor

MODEL_ID = "unsloth/sam3.1"
DTYPE = torch.bfloat16
SIDES = (512, 1024, 2048)
PROMPT_COUNTS = (1, 8)


def measure(model, processor, image: "Image.Image", prompts) -> dict:
    """Une inférence sur une image donnée ; renvoie pic mémoire et latence."""
    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()
    text = ", ".join(prompts) if isinstance(prompts, (list, tuple)) else prompts
    inputs = processor(images=image, text=text, return_tensors="pt").to("cuda", DTYPE)
    start = time.time()
    with torch.inference_mode():
        outputs = model(**inputs)
        result = processor.post_process_instance_segmentation(
            outputs,
            threshold=0.3,
            mask_threshold=0.5,
            target_sizes=inputs.get("original_sizes").tolist(),
        )[0]
    torch.cuda.synchronize()
    masks = result["masks"]
    return {
        "input_px": list(image.size),
        "prompts": text,
        "alloc_peak_gb": round(torch.cuda.max_memory_allocated() / 1e9, 2),
        "reserved_peak_gb": round(torch.cuda.max_memory_reserved() / 1e9, 2),
        "inference_s": round(time.time() - start, 3),
        "masks": list(masks.shape),
        "mask_px": int(masks.shape[-1] * masks.shape[-2]) if masks.numel() else 0,
    }


def run_once(model, processor, side: int, prompts: int) -> dict:
    """Image unie de ``side`` px, prompt répété ``prompts`` fois."""
    image = Image.new("RGB", (side, side), (120, 90, 60))
    text = ["sofa"] * prompts
    return measure(model, processor, image, text)


def main() -> None:
    name = torch.cuda.get_device_name(0)
    capability = torch.cuda.get_device_capability()
    start = time.time()
    model = Sam3Model.from_pretrained(MODEL_ID, dtype=DTYPE).to("cuda").eval()
    processor = Sam3Processor.from_pretrained(MODEL_ID)
    report = {
        "device": f"{name} sm_{capability[0]}{capability[1]}",
        "torch": torch.__version__,
        "dtype": str(DTYPE),
        "load_s": round(time.time() - start, 1),
        "weights_gb": round(torch.cuda.memory_allocated() / 1e9, 2),
        "runs": [],
    }
    for side in SIDES:
        for prompts in PROMPT_COUNTS:
            report["runs"].append(run_once(model, processor, side, prompts))
    free, total = torch.cuda.mem_get_info()
    report["device_total_gb"] = round(total / 1e9, 2)
    report["gpu_used_gb"] = round((total - free) / 1e9, 2)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()