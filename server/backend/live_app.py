"""Modal backend « sam3-live » : SAM 3.1 sur A100, une frame à la fois (docs/LIVE-SPEC.md).

Routes : ``GET /health`` (avec le nom du GPU réellement alloué), ``POST /segment``
(contrat docs/SPEC.md inchangé) et ``POST /live`` (contrat docs/LIVE-SPEC.md). Aucun
secret : le téléphone pose ses questions directement au LLM vision d'InferHub, ce serveur
ne fait que segmenter la frame.

Seul ``modal`` est importé au niveau du module — torch, transformers, PIL, fastapi et les
helpers du backend sont importés dans les fonctions côté conteneur, pour que ce fichier
reste importable sans stack GPU (``py_compile``, ``scripts/test_units.py``).
"""

# ⚠️ version LENTE (1008 px, 1 forward DETR par concept) : 205-241 ms serveur.
# Pour la production, déployer `backend/live_app_fast.py` (83-125 ms). Voir docs/LIVE-PERF.md.
# Seule commande de déploiement du direct : scripts/deploy-live-fast.sh

# NOTE: pas de `from __future__ import annotations` ici — FastAPI résout les annotations
# des modèles de requête (importés localement dans web()) au runtime ; avec les strings
# différées le corps serait pris pour un paramètre de query (400 systématique).
import asyncio
import os
import threading
import time
from pathlib import Path

import modal

from http_errors import MAX_DETAIL_CHARS, validation_detail as _validation_detail

# Nom et GPU surchargeables au déploiement pour comparer deux cartes sans
# duplicata de code :
#   LIVE_APP_NAME=sam3-live-h200 LIVE_GPU=H200 modal deploy backend/live_app.py
APP_NAME = os.environ.get("LIVE_APP_NAME", "sam3-live")
GPU_TYPE = os.environ.get("LIVE_GPU", "A100")
REGION = os.environ.get("LIVE_REGION") or None
# Sans routing_region, Modal fait passer TOUT le trafic web par Virginia (us-east),
# même si le conteneur est en Europe : pour la latence téléphone, c'est la moitié
# du trajet. Fixable uniquement au premier déploiement (documenté par Modal).
ROUTING = os.environ.get("LIVE_ROUTING_REGION") or None
MODEL_LABEL = "sam3.1"
BACKEND_DIR = Path(__file__).resolve().parent

# /live : knobs de latence bornés côté serveur — un client hors bornes reçoit quand même
# une frame, et ne peut pas faire exploser la VRAM en demandant une résolution délirante.
WORK_EDGE_DEFAULT = 768
WORK_EDGE_MIN = 256
WORK_EDGE_MAX = 1024
MAX_OBJECTS_DEFAULT = 12
MAX_OBJECTS_MIN = 1
# Concepts quand le client n'en envoie aucun : liste courte, le direct est cadencé.
LIVE_DEFAULT_PROMPTS = ("person", "car", "dog", "chair")

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install_from_requirements(str(BACKEND_DIR / "requirements.txt"))
    # .env() est une étape de build : elle doit passer avant add_local_* (règle Modal).
    .env({"PYTHONPATH": "/root:/root/backend_src"})
    .add_local_dir(str(BACKEND_DIR), remote_path="/root/backend_src")
)

app = modal.App(APP_NAME)


def _clamp(value, default: int, low: int, high: int) -> int:
    """Ramener un knob optionnel dans [low, high] ; absent ou non numérique → default."""
    if value is None:
        return default
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


def gpu_name() -> str:
    """Nom du GPU alloué, relevé une fois au chargement (nvidia-smi, secours torch)."""
    import subprocess

    try:
        result = subprocess.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                                capture_output=True, text=True, timeout=15)
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip().splitlines()[0].strip()
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        import torch

        if torch.cuda.is_available():
            return torch.cuda.get_device_name(0)
    except Exception:  # noqa: BLE001 — /health ne doit jamais tomber pour un nom de GPU
        pass
    return "unknown"


@app.cls(
    image=image,
    gpu=GPU_TYPE,
    region=REGION,
    routing_region=ROUTING,
    timeout=300,
    startup_timeout=900,
    # UN SEUL GPU, JAMAIS DEUX : max_containers=1 empêche Modal de lancer un second
    # conteneur (donc une seconde carte) même si plusieurs frames arrivent en parallèle.
    # min_containers=0 + scaledown court : la carte s'éteint dès la fin du direct.
    max_containers=1,
    min_containers=0,
    scaledown_window=int(os.environ.get("LIVE_SCALEDOWN", "20")),
)
class Sam3Live:
    """Conteneur chaud A100 : modèle chargé une fois, inférence sérialisée sur un verrou."""

    @modal.enter()
    def load(self) -> None:
        """Charge SAM 3.1, le processeur et le nom du GPU une fois par conteneur."""
        from segmentation import load_model

        self.model, self.processor, self.device, self.dtype = load_model()
        self.gpu_name = gpu_name()
        self._gpu_lock = threading.Lock()

    def _segment_sync(self, image_b64: str, prompts) -> dict:
        """Route historique /segment : découpage choisi par le serveur (docs/SPEC.md)."""
        from imaging import decode_image
        from segmentation import run_segmentation

        image = decode_image(image_b64)
        with self._gpu_lock:
            return run_segmentation(self.model, self.processor, self.device, self.dtype, image, prompts)

    def _live_sync(self, image_b64: str, prompts, max_objects, work_edge) -> dict:
        """Une frame de direct : décodage, segmentation à la résolution demandée, chrono."""
        from imaging import decode_image
        from segmentation import MAX_OBJECTS, normalize_prompts, segment_image

        started = time.monotonic()
        image = decode_image(image_b64)
        concepts = normalize_prompts(prompts if prompts is not None else list(LIVE_DEFAULT_PROMPTS))
        cap = _clamp(max_objects, MAX_OBJECTS_DEFAULT, MAX_OBJECTS_MIN, MAX_OBJECTS)
        edge = _clamp(work_edge, WORK_EDGE_DEFAULT, WORK_EDGE_MIN, WORK_EDGE_MAX)
        with self._gpu_lock:
            result = segment_image(self.model, self.processor, self.device, self.dtype, image, concepts,
                                   work_edge=edge, max_objects=cap)
        result["elapsed_ms"] = int(round((time.monotonic() - started) * 1000))
        return result

    @modal.asgi_app()
    def web(self):
        """FastAPI : /health (+ nom GPU), /segment (SPEC), /live (LIVE-SPEC)."""
        from fastapi import FastAPI, HTTPException
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse
        from imaging import ImageDecodeError
        from schemas import LiveRequest, SegmentRequest
        from segmentation import PromptError, device_label

        api = FastAPI(title=APP_NAME, version="1.0.0")

        async def call(pipeline, *args):
            """Lance le blocage GPU dans un thread, mappe les erreurs vers les codes HTTP."""
            try:
                return await asyncio.to_thread(pipeline, *args)
            except (ImageDecodeError, PromptError) as exc:
                raise HTTPException(status_code=400, detail=str(exc)[:MAX_DETAIL_CHARS]) from exc
            except Exception as exc:
                raise HTTPException(
                    status_code=500, detail=f"internal error: {exc}"[:MAX_DETAIL_CHARS]) from exc

        @api.exception_handler(RequestValidationError)
        async def invalid_body(_request, exc):
            return JSONResponse(status_code=400, content={"detail": _validation_detail(exc.errors())})

        @api.get("/health")
        async def health():
            return {"ok": True, "model": MODEL_LABEL, "device": device_label(),
                    "gpu": getattr(self, "gpu_name", "unknown"),
                    "region": os.environ.get("MODAL_REGION", "unknown"),
                    "cloud": os.environ.get("MODAL_CLOUD_PROVIDER", "unknown")}

        @api.post("/segment")
        async def segment(request: SegmentRequest):
            return await call(self._segment_sync, request.image_b64, request.prompts)

        @api.post("/live")
        async def live(request: LiveRequest):
            return await call(self._live_sync, request.image_b64, request.prompts,
                              request.max_objects, request.work_edge)

        return api
