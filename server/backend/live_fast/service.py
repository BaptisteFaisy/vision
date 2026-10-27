"""Service SAM 3.1 « live » — la logique du conteneur GPU, sans fournisseur.

Tout ce qui fait tourner le backend vit ici : chargement du modèle, frame de direct,
``/segment``, ``/live``, ``WS /live/ws``, ``/release`` et la construction de l'app
FastAPI. Les deux hôtes ne fournissent que l'amorçage et le transport :

* ``live_app_fast.py`` — Modal (``@modal.enter`` + ``@modal.asgi_app``)
* ``../baseten/src/server.py`` — Baseten (uvicorn sur l'image Truss)

Le contrat HTTP est décrit dans ``docs/SPEC.md`` et ``docs/LIVE-SPEC.md`` ; il ne change
pas d'un hôte à l'autre, c'est ce qui permet de basculer l'un pour l'autre.
"""

import asyncio
import inspect
import json
import os
import threading
import time
from contextlib import asynccontextmanager

import detr_graph
from fast_core import (BATCH_PROMPTS, DTYPE_NAME, EDGE, RUNTIME_EDGE, TIMING, clamp_int,
                       gpu_name, levers, load, prepare, run_frame, segment_frame, vision_grid)
from http_errors import MAX_DETAIL_CHARS, validation_detail as _validation_detail
from timing import Stage, elapsed_ms, format_server_timing, now

MODEL_LABEL = "sam3.1-fast"
# Laisse le temps de répondre au client avant d'arrêter le processus (POST /release).
RELEASE_GRACE_SECONDS = 0.5

WORK_EDGE_DEFAULT = 768
WORK_EDGE_MIN = 256
WORK_EDGE_MAX = 1024
MAX_OBJECTS_DEFAULT = 12
MAX_OBJECTS_MIN = 1
LIVE_DEFAULT_PROMPTS = ("person", "car", "dog", "chair")


async def call(pipeline, *args, **kwargs):
    """Lance le blocage GPU dans un thread ; mappe les erreurs vers les codes HTTP.

    Point de vérité unique des statuts : ``POST /live`` laisse remonter l'``HTTPException``
    (400 décodage/prompt, 500 interne), ``/live/ws`` l'attrape et en fait un message
    d'erreur — même code, même détail borné, aucune logique dupliquée. ``kwargs`` porte
    ``mask_encoding`` (les arguments optionnels du pipeline ne sont pas positionnels).
    """
    from fastapi import HTTPException
    from imaging import ImageDecodeError, MaskEncodingError
    from segmentation import PromptError

    try:
        return await asyncio.to_thread(pipeline, *args, **kwargs)
    except (ImageDecodeError, MaskEncodingError, PromptError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)[:MAX_DETAIL_CHARS]) from exc
    except Exception as exc:
        raise HTTPException(status_code=500,
                            detail=f"internal error: {exc}"[:MAX_DETAIL_CHARS]) from exc


def accepts_mask_encoding(pipeline) -> bool:
    """Le pipeline accepte-t-il le paramètre ``mask_encoding`` ?

    Les doublures de test de ``/live`` (``scripts/test_live_ws.py``) ont la signature
    historique à six paramètres : on ne leur impose pas le septième, on le leur demande.
    Même motif que la référence ``accepts_text_cache`` — une vérification par signature,
    pas un ``try/except TypeError`` qui avalerait les vraies erreurs du pipeline.
    """
    try:
        return "mask_encoding" in inspect.signature(pipeline).parameters
    except (TypeError, ValueError):  # callable sans signature introspectable
        return False


class Sam3LiveService:
    """Modèle chargé une fois, leviers activables, verrou GPU unique.

    ``load_container()`` est idempotent : Modal l'appelle depuis ``@modal.enter``,
    Baseten depuis le lifespan ASGI de l'app (les deux chemins se rejoignent).
    """

    def load_container(self) -> None:
        """Charge le modèle au dtype demandé, applique résolution/compile/graph, chronomètre."""
        if getattr(self, "ctx", None) is not None:  # déjà chargé : ne rien refaire
            return
        from segmentation import device_label

        started = time.monotonic()
        model, processor, device, dtype = load(DTYPE_NAME, EDGE)
        self.ctx = {"model": model, "processor": processor, "device": device, "dtype": dtype,
                    "runtime_edge": RUNTIME_EDGE}
        self.ctx.update(prepare(model, processor, device, dtype, EDGE))
        self.ctx["device_label"] = device_label()
        self.ctx["vision_grid"] = vision_grid(model)
        # Graphe CUDA de la passe DETR (levier FAST_DETR_GRAPH) : le cache vit ici, la
        # passe elle-même est présentée par fast_core._detections.
        if detr_graph.enabled():
            self.ctx["detr_graphs"] = detr_graph.DetrGraphs()
        else:
            self.ctx["detr_graphs"] = None
        self.gpu_name = gpu_name()
        self.load_seconds = round(time.monotonic() - started, 1)
        self._gpu_lock = threading.Lock()

    def _live_sync(self, image_b64, prompts, max_objects, work_edge, batch_prompts, edge,
                   mask_encoding=None) -> dict:
        """Une frame de direct : décodage, segmentation avec les leviers effectifs, chrono.

        Le décodage et le pré-traitement CPU sont **hors** du verrou GPU (``run_frame`` ne
        garde sous verrou que l'encodeur vision et les passes DETR) : avec deux entrées par
        conteneur, la frame N+1 se prépare pendant l'inférence de N. Le résultat porte en
        plus ``mask_encoding`` (la représentation appliquée) et, si ``FAST_TIMING=1``,
        ``timings_ms`` (postes en ms). ``elapsed_ms`` garde son sens : total serveur.
        """
        from imaging import decode_image
        from segmentation import MAX_OBJECTS, normalize_prompts

        timers: dict = {}
        started = now()
        with Stage(timers, "decode_ms"):
            image = decode_image(image_b64)
        concepts = normalize_prompts(prompts if prompts is not None else list(LIVE_DEFAULT_PROMPTS))
        cap = clamp_int(max_objects, MAX_OBJECTS_DEFAULT, MAX_OBJECTS_MIN, MAX_OBJECTS)
        work = clamp_int(work_edge, WORK_EDGE_DEFAULT, WORK_EDGE_MIN, WORK_EDGE_MAX)
        batch = BATCH_PROMPTS if batch_prompts is None else bool(int(batch_prompts))
        result = run_frame(self.ctx, image, concepts, work, cap, batch, edge=edge,
                           mask_encoding=mask_encoding, gpu_lock=self._gpu_lock, timers=timers)
        timers["total_ms"] = elapsed_ms(started)
        result["elapsed_ms"] = int(round(timers["total_ms"]))
        result["fast"] = {"batch_prompts": batch, "edge": result.pop("_edge_applied"),
                          "edge_asked": edge, "edge_ok": result.pop("_edge_ok"),
                          "vision_accel": self.ctx.get("vision_accel"),
                        "attn": getattr(getattr(self.ctx.get("model"), "config", None),
                                        "_attn_implementation", "?"), "dtype": DTYPE_NAME,
                          "timings": TIMING}
        return result

    async def _run_live(self, request) -> dict:
        """Un seul chemin pour ``POST /live`` et ``WS /live/ws`` : mêmes args, mêmes erreurs."""
        args = (request.image_b64, request.prompts, request.max_objects, request.work_edge,
                request.batch_prompts, request.edge)
        if accepts_mask_encoding(self._live_sync):
            return await call(self._live_sync, *args, mask_encoding=request.mask_encoding)
        return await call(self._live_sync, *args)

    def _segment_sync(self, image_b64, prompts) -> dict:
        """Route historique /segment : concepts choisis par le serveur (docs/SPEC.md)."""
        from imaging import decode_image
        from segmentation import MAX_OBJECTS, normalize_prompts, run_segmentation

        image = decode_image(image_b64)
        concepts = normalize_prompts(prompts) if prompts is not None else None
        with self._gpu_lock:
            if concepts is None:
                return run_segmentation(self.ctx["model"], self.ctx["processor"],
                                        self.ctx["device"], self.ctx["dtype"], image, None)
            result = segment_frame(self.ctx, image, concepts, WORK_EDGE_MAX, MAX_OBJECTS,
                                   BATCH_PROMPTS)
        result.pop("_edge_applied", None)
        result.pop("_edge_ok", None)
        return result

    async def _ws_frame(self, raw: str) -> dict:
        """Un message texte → un message texte (résultat ou erreur) ; ne lève jamais.

        Une frame invalide (JSON cassé, ``id`` absent, base64 illisible, GPU en vrac) ne
        ferme pas la connexion : elle repart en message d'erreur, avec l'``id`` connu.
        """
        from fastapi import HTTPException

        from live_ws import error_message, parse_frame, success_message

        parsed = parse_frame(raw)
        if parsed["error"] is not None:
            return error_message(parsed["id"], parsed["error"]["status"],
                                 parsed["error"]["detail"])
        request = parsed["request"]
        try:
            result = await self._run_live(request)
        except HTTPException as exc:
            return error_message(parsed["id"], exc.status_code, exc.detail)
        except Exception as exc:  # noqa: BLE001 — le contrat interdit la fermeture
            return error_message(parsed["id"], 500, f"internal error: {exc}")
        return success_message(result, parsed["id"])

    async def _ws_loop(self, websocket) -> None:
        """Une frame à la fois par connexion ; le verrou GPU global reste la seule file."""
        from fastapi import WebSocketDisconnect

        from live_ws import UNKNOWN_ID, error_message

        while True:
            try:
                raw = await websocket.receive_text()
            except WebSocketDisconnect:
                return
            except Exception:  # noqa: BLE001 — message binaire ou socket déjà fermé
                raw = None
            try:
                reply = (error_message(UNKNOWN_ID, 400, "expected a JSON text message")
                         if raw is None else await self._ws_frame(raw))
                await websocket.send_text(json.dumps(reply, separators=(",", ":")))
            except Exception:  # noqa: BLE001 — client parti entre la réception et la réponse
                return

    def health(self) -> dict:
        """État du conteneur : modèle, carte, région, leviers appliqués.

        Les variables de région diffèrent selon l'hôte (``MODAL_*`` / ``BASETEN_*``) : on
        les lit toutes, le client ne voit qu'un champ ``region``/``cloud`` stable.
        """
        return {"ok": True, "model": MODEL_LABEL, "device": self.ctx.get("device_label", "cpu"),
                "gpu": getattr(self, "gpu_name", "unknown"),
                "region": (os.environ.get("MODAL_REGION") or os.environ.get("BASETEN_REGION")
                           or "unknown"),
                "cloud": (os.environ.get("MODAL_CLOUD_PROVIDER")
                          or os.environ.get("BASETEN_CLOUD") or "unknown"),
                "platform": os.environ.get("BACKEND_PLATFORM", "unknown"),
                "load_seconds": getattr(self, "load_seconds", None),
                "levers": levers(), "applied": {
                    "edge_applied": self.ctx.get("edge_applied"),
                    "vision_grid": self.ctx.get("vision_grid"),
                    "vision_accel": self.ctx.get("vision_accel"),
                    "detr_graph": (self.ctx["detr_graphs"].status()
                                   if self.ctx.get("detr_graphs") else None)}}

    @asynccontextmanager
    async def _lifespan(self, _api):
        """Charge le modèle avant la première requête (Baseten : uvicorn sans ``enter``)."""
        await asyncio.to_thread(self.load_container)
        yield

    def api(self):
        """FastAPI : /health, /segment (SPEC), /live (LIVE-SPEC + knobs), /live/ws, /release."""
        from fastapi import FastAPI, Request, WebSocket
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse
        from schemas import LiveFastRequest, SegmentRequest

        service = self
        api = FastAPI(title=MODEL_LABEL, version="1.0.0", lifespan=self._lifespan)

        @api.exception_handler(RequestValidationError)
        async def invalid_body(_request, exc):
            return JSONResponse(status_code=400, content={"detail": _validation_detail(exc.errors())})

        @api.get("/health")
        async def health():
            return service.health()

        @api.post("/segment")
        async def segment(request: SegmentRequest):
            return await call(service._segment_sync, request.image_b64, request.prompts)

        @api.post("/live")
        async def live(request: LiveFastRequest):
            result = await service._run_live(request)
            # En-tête additif (RFC 8673) : le levier FAST_TIMING reste le seul interrupteur.
            # Éteint, `timings_ms` est absent et aucun en-tête n'est émis — un client ancien
            # ne voit strictement rien changer ; un banc lit les postes sans parser le corps.
            header = format_server_timing(result.get("timings_ms") if isinstance(result, dict)
                                          else None)
            if not header:
                return result
            return JSONResponse(content=result, headers={"Server-Timing": header})

        @api.post("/release")
        async def release(request: Request):
            """Éteint le conteneur GPU — l'app l'appelle quand elle passe en arrière-plan.

            Le téléphone n'a aucun identifiant du fournisseur : il ne peut que demander.
            L'en-tête `x-sam-release: confirm` évite qu'un scanner ou un préchargement de
            lien éteigne la carte par accident. On répond **avant** de mourir, sinon le
            client voit un timeout au lieu d'une confirmation ; le processus s'arrête juste
            après (min_replica=0 ⇒ pas de relance, et le prochain réveil repart d'un
            snapshot restauré ou d'un conteneur neuf).
            """
            from fastapi import HTTPException

            if request.headers.get("x-sam-release", "") != "confirm":
                raise HTTPException(status_code=400, detail="en-tête x-sam-release: confirm requis")
            threading.Timer(RELEASE_GRACE_SECONDS, os._exit, args=(0,)).start()
            return {"released": True, "grace_seconds": RELEASE_GRACE_SECONDS}

        @api.websocket("/live/ws")
        async def live_ws(websocket: WebSocket):
            """Même pipeline que POST /live, un message texte JSON par frame."""
            await websocket.accept()
            await service._ws_loop(websocket)

        return api