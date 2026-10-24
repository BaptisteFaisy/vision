"""Latence de SAM 3.1 sur de vraies captures (détections réelles, upscale au format d'origine).

  /tmp/sam3env/bin/python scripts/probe_real.py

Réutilise measure()/chargement de probe_local.py.
"""
import json
import sys

from PIL import Image

sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/scripts")

from probe_local import DTYPE, MODEL_ID, measure  # noqa: E402

IMAGES = [
    "/home/baptiste/Images/Captures/Capture_2026-10-06_17-12-36.png",
    "/home/baptiste/Images/Captures/iphone-20260922-211438.png",
]
PROMPTS = [["window"], ["text", "button", "icon"]]


def main() -> None:
    import time

    import torch
    from transformers import Sam3Model, Sam3Processor

    model = Sam3Model.from_pretrained(MODEL_ID, dtype=DTYPE).to("cuda").eval()
    processor = Sam3Processor.from_pretrained(MODEL_ID)
    report = {"device": torch.cuda.get_device_name(0), "runs": []}
    for path in IMAGES:
        image = Image.open(path).convert("RGB")
        for prompts in PROMPTS:
            report["runs"].append({"file": path.rsplit("/", 1)[-1],
                                   **measure(model, processor, image, prompts)})
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
