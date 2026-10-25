"""Modal backend « sam3-track » : tracker vidéo SAM 3.1 (multiplex) sur B200.

Routes (contrat docs/TRACK-SPEC.md) : ``GET /health``, ``POST /session`` (frame 0 +
prompts), ``POST /frame`` (une frame du flux, ids stables), ``DELETE /session/{id}`` et
``POST /session/close``. La logique tracker (checkpoint, tampon de frames, propagation
d'une frame) vit dans ``tracking.py`` ; les erreurs et les masques réutilisent
``http_errors.py`` et ``imaging.py``.

Une seule session vivante par conteneur, et un routage stable : ``max_containers=1``
(paramètre présent dans Modal 1.6.2, vérifié) empêche Modal d'ouvrir un second conteneur
et donc de perdre l'état en mémoire. Le direct est sérialisé par un verrou : deux frames
simultanées se suivent, elles ne se mélangent pas.

Nom et GPU surchargeables au déploiement (comme ``live_app.py``) :
``TRACK_GPU=H200 TRACK_APP_NAME=sam3-track-h200 modal deploy backend/track_app.py``.

Seul ``modal`` est importé au niveau du module — torch, sam3, fastapi et les helpers sont
importés dans les fonctions côté conteneur, pour que ce fichier reste importable sans
stack GPU (``py_compile``, tests).
"""

# NOTE: pas de `from __future__ import annotations` ici — FastAPI résout les annotations
# des modèles de requête au runtime (importés localement dans web()).
import asyncio
import os
import threading
import traceback
import time
import uuid
from pathlib import Path

import modal

from http_errors import MAX_DETAIL_CHARS, validation_detail as _validation_detail

APP_NAME = os.environ.get("TRACK_APP_NAME", "sam3-track")
GPU_TYPE = os.environ.get("TRACK_GPU") or os.environ.get("LIVE_GPU") or "B200"
# Mêmes leviers que live_app.py : `region` place le conteneur en Europe, `routing_region`
# fait sortir le trafic web par Dublin (sinon tout passe par la Virginie, même GPU en EU).
REGION = os.environ.get("TRACK_REGION") or None
ROUTING = os.environ.get("TRACK_ROUTING_REGION") or None
CAPACITY = int(os.environ.get("TRACK_CAPACITY", "900"))
CAPACITY_MIN, CAPACITY_MAX = 30, 900  # bornes du tampon demandé par le client
MODEL_LABEL = "sam3.1-tracker"
BACKEND_DIR = Path(__file__).resolve().parent

image = (
    modal.Image.debian_slim(python_version="3.12")
    # Même chaîne que backend/probe_video_tracker.py : les couches pip restent en cache.
    .env({"PYTHONPATH": "/root"})
    .pip_install_from_requirements(str(BACKEND_DIR / "requirements.txt"))
    .apt_install("git")  # après la couche pip : le cache de la couche torch reste valide
    .pip_install(
        "git+https://github.com/facebookresearch/sam3.git",
        "einops",
        "pycocotools",
        "hydra-core",
        "opencv-python-headless",
        "setuptools<80",
    )
    # Les modules du backend arrivent dans /root (déjà sur PYTHONPATH, comme le probe).
    .add_local_dir(str(BACKEND_DIR), remote_path="/root")
)

app = modal.App(APP_NAME)


async def call(pipeline, *args):
    """Lance le blocage GPU dans un thread, mappe les erreurs vers les codes HTTP."""
    from fastapi import HTTPException

    from imaging import ImageDecodeError
    from tracking import CapacityFull, PromptTooLong, SessionUnknown

    try:
        return await asyncio.to_thread(pipeline, *args)
    except SessionUnknown as exc:
        raise HTTPException(status_code=404, detail=f"unknown session {exc}"[:MAX_DETAIL_CHARS]) from exc
    except (ImageDecodeError, PromptTooLong, CapacityFull, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)[:MAX_DETAIL_CHARS]) from exc
    except Exception as exc:  # noqa: BLE001 — borne la réponse, ne fuite pas la stack
        traceback.print_exc()  # la stack complète reste dans les logs du conteneur
        raise HTTPException(status_code=500, detail=f"internal error: {exc}"[:MAX_DETAIL_CHARS]) from exc


@app.cls(
    image=image,
    gpu=GPU_TYPE,
    region=REGION,
    routing_region=ROUTING,
    timeout=600,
    startup_timeout=1800,
    scaledown_window=60,
    max_containers=1,
    cpu=8,
    memory=32768,
)
class Sam3Track:
    """Conteneur chaud : un predictor multiplex chargé une fois, une session vivante."""

    @modal.enter()
    def load(self) -> None:
        """Télécharge le checkpoint, construit le predictor, relève le nom du GPU."""
        from tracking import build_predictor, fetch_checkpoint, gpu_name

        started = time.monotonic()
        self.download_s = fetch_checkpoint()
        self.predictor, self.build_s = build_predictor()
        self.gpu_name = gpu_name()
        self.session = None
        self._lock = threading.Lock()
        self.ready_s = round(time.monotonic() - started, 1)
        print(f"sam3-track prêt en {self.ready_s}s (poids {self.download_s}s, "
              f"predictor {self.build_s}s) sur {self.gpu_name}")

    # ── Pipelines (bloquants, appelés dans un thread) ─────────────────

    def _close_locked(self) -> str:
        """Ferme la session courante (verrou déjà tenu) ; renvoie son id ('' si aucune)."""
        session, self.session = self.session, None
        if session is None:
            return ""
        session.close()
        return session.session_id

    def _start(self, image_b64: str, prompts, capacity=None, debug: bool = False) -> dict:
        from imaging import decode_image
        from tracking import LiveSession, clean_prompts

        concepts = clean_prompts(prompts)
        started = time.monotonic()
        image = decode_image(image_b64)
        decode_ms = round((time.monotonic() - started) * 1000)
        if image.size[0] < 16 or image.size[1] < 16:
            raise ValueError(f"image too small: {image.size[0]}x{image.size[1]}")
        with self._lock:
            self._close_locked()  # une seule session vivante : la précédente est libérée
            size = CAPACITY if capacity is None else max(CAPACITY_MIN, min(int(capacity), CAPACITY_MAX))
            session = LiveSession(self.predictor, uuid.uuid4().hex[:12], concepts, image, size,
                                  debug=bool(debug))
            objects = session.start()
            self.session = session
        return {"session_id": session.session_id, "width": session.width,
                "height": session.height, "objects": objects,
                "elapsed_ms": int((time.monotonic() - started) * 1000)}

    def _frame(self, session_id: str, image_b64: str) -> dict:
        from imaging import decode_image
        from tracking import SessionUnknown, vram_mb

        started = time.monotonic()
        image = decode_image(image_b64)
        decode_ms = round((time.monotonic() - started) * 1000)
        with self._lock:
            session = self.session
            if session is None or session.session_id != session_id:
                raise SessionUnknown(session_id)
            result = session.push(image, image_b64)
        result["elapsed_ms"] = int((time.monotonic() - started) * 1000)
        result["ms"] = {"decode": decode_ms, **result.get("ms", {})}
        result["vram_mb"] = vram_mb()  # relève live : une frame qui fuit se voit dans la courbe
        if session.debug:
            result["mem"] = session.memory_report()
        return result

    def _close(self, session_id: str) -> dict:
        from tracking import SessionUnknown

        with self._lock:
            current = self.session
            if current is None or current.session_id != session_id:
                raise SessionUnknown(session_id)
            closed = self._close_locked()
        return {"closed": closed}

    # ── HTTP ─────────────────────────────────────────────────────────

    @modal.asgi_app()
    def web(self):
        """FastAPI : /health, /session, /frame, /session/{id}."""
        from fastapi import FastAPI, HTTPException
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse
        from schemas import TrackCloseRequest, TrackFrameRequest, TrackSessionRequest
        from tracking import MAX_TRACKED_OBJECTS

        api = FastAPI(title=APP_NAME, version="1.0.0")

        @api.exception_handler(RequestValidationError)
        async def invalid_body(_request, exc):
            return JSONResponse(status_code=400, content={"detail": _validation_detail(exc.errors())})

        @api.get("/health")
        async def health():
            return {"ok": True, "model": MODEL_LABEL, "gpu": getattr(self, "gpu_name", "unknown"),
                    "region": os.environ.get("MODAL_REGION", "unknown"),
                    "max_objects": MAX_TRACKED_OBJECTS}

        @api.post("/session")
        async def open_session(request: TrackSessionRequest):
            return await call(self._start, request.image_b64, request.prompts,
                              request.capacity, request.debug)

        @api.post("/frame")
        async def push_frame(request: TrackFrameRequest):
            return await call(self._frame, request.session_id, request.image_b64)

        @api.delete("/session/{session_id}")
        async def close_session(session_id: str):
            return await call(self._close, session_id)

        @api.post("/session/close")
        async def close_session_post(request: TrackCloseRequest):
            return await call(self._close, request.session_id)

        return api