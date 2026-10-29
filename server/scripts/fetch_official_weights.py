#!/usr/bin/env python3
"""Pré-remplit le volume des poids SAM 3.1 officiels (une fois, CPU, ~7 Go).

Télécharge ``sam3.1_multiplex.pt`` + ``config.json`` depuis le dépôt officiel
``facebook/sam3.1`` (gated — secret Modal ``huggingface``) vers le volume
``sam3-official`` monté par l'app ``sam3-vision``. CPU uniquement, jamais de GPU.

    ~/.venv-modal/bin/modal run scripts/fetch_official_weights.py
"""

import modal

OFFICIAL_REPO = "facebook/sam3.1"
CKPT_FILES = ("sam3.1_multiplex.pt", "config.json")
VOLUME_NAME = "sam3-official"
MOUNT = "/root/ckpts"

app = modal.App("sam3-official-weights")
volume = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)
image = modal.Image.debian_slim(python_version="3.12").pip_install("huggingface_hub")


@app.function(image=image, secrets=[modal.Secret.from_name("huggingface")],
             volumes={MOUNT: volume}, cpu=2.0, timeout=3600)
def fetch() -> dict:
    """Télécharge les fichiers manquants, rend tailles + empreintes (jamais de secret)."""
    import hashlib
    import json
    import os
    import time

    from huggingface_hub import hf_hub_download

    token = os.environ["HF_TOKEN"]
    started, files = time.time(), {}
    for name in CKPT_FILES:
        path = hf_hub_download(OFFICIAL_REPO, name, local_dir=MOUNT, token=token)
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
        files[name] = {"bytes": os.path.getsize(path), "sha256": digest.hexdigest()}
    volume.commit()
    return {"repo": OFFICIAL_REPO, "seconds": round(time.time() - started, 1),
            "files": files}


@app.local_entrypoint()
def main() -> None:
    import json

    print(json.dumps(fetch.remote(), indent=2))
