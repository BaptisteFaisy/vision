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
                       gpu_name, levers, load, load_cpu, prepare, run_frame,
                       segment_frame, vision_grid)
from http_errors import MAX_DETAIL_CHARS, validation_detail as _validation_detail
from timing import Stage, elapsed_ms, format_server_timing, now

MODEL_LABEL = "sam3.1-fast"
# Laisse le temps de répondre au client avant d'arrêter le processus (POST /release).
RELEASE_GRACE_SECONDS = 0.5
WS_IDLE_SECONDS = 45.0  # sans message : pair considéré mort, fermeture 1011 (zombie ≤ 45 s)
WS_FRAME_SECONDS = 180.0  # inférence max par frame : au-delà, erreur 503, connexion gardée
WS_MAX_REPLY_BYTES = 4_000_000  # réponse plus grosse : fermeture 1009 avant le proxy

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


def accepts_param(pipeline, name: str) -> bool:
    """Le pipeline accepte-t-il le paramètre ``name`` ?

    Les doublures de test de ``/live`` (``scripts/test_live_ws.py``) ont la signature
    historique à six paramètres : on ne leur impose pas les suivants, on les leur demande.
    Même motif que la référence ``accepts_text_cache`` — une vérification par signature,
    pas un ``try/except TypeError`` qui avalerait les vraies erreurs du pipeline.
    """
    try:
        return name in inspect.signature(pipeline).parameters
    except (TypeError, ValueError):  # callable sans signature introspectable
        return False


def accepts_mask_encoding(pipeline) -> bool:
    """Le pipeline accepte-t-il le paramètre ``mask_encoding`` ? (Compat historique.)"""
    return accepts_param(pipeline, "mask_encoding")


class Sam3LiveService:
    """Modèle chargé une fois, leviers activables, verrou GPU unique.

    ``load_container()`` est idempotent : Modal l'appelle depuis le second
    ``@modal.enter`` (après restauration du snapshot), Baseten depuis le lifespan
    ASGI de l'app (les deux chemins se rejoignent).
    """

    def load_cpu_weights(self) -> None:
        """Phase snapshottée (Modal ``enter(snap=True)``) : poids sur CPU, aucun état CUDA."""
        if getattr(self, "cpu", None) is not None:  # déjà chargés : ne rien refaire
            return
        started = time.monotonic()
        model, processor, dtype = load_cpu(DTYPE_NAME, EDGE)
        self.cpu = {"model": model, "processor": processor, "dtype": dtype}
        self.snap_seconds = round(time.monotonic() - started, 1)

    def load_container(self) -> None:
        """Charge le modèle au dtype demandé, applique résolution/compile/graph, chronomètre."""
        if getattr(self, "ctx", None) is not None:  # déjà chargé : ne rien refaire
            return
        from segmentation import device_label

        started = time.monotonic()
        if getattr(self, "cpu", None) is None:
            # Baseten / 1er démarrage : pas d'état snapshotté, chargement complet.
            model, processor, device, dtype = load(DTYPE_NAME, EDGE)
        else:
            # Restauration snapshot : les poids CPU reviennent tels quels, reste le GPU.
            model, processor, dtype = (self.cpu["model"], self.cpu["processor"],
                                       self.cpu["dtype"])
            device = device_label()
            model = model.to(device).eval()
            del self.cpu
        self.ctx = {"model": model, "processor": processor, "device": device, "dtype": dtype,
                    "runtime_edge": RUNTIME_EDGE,
                    "attn_impl": getattr(getattr(model, "config", None),
                                         "_attn_implementation", "?")}
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
                   mask_encoding=None, mask_format=None, mask_edge=0) -> dict:
        """Une frame de direct : décodage, segmentation avec les leviers effectifs, chrono.

        Le décodage et le pré-traitement CPU sont **hors** du verrou GPU (``run_frame`` ne
        garde sous verrou que l'encodeur vision et les passes DETR) : avec deux entrées par
        conteneur, la frame N+1 se prépare pendant l'inférence de N. Le résultat porte en
        plus ``mask_encoding`` (la représentation appliquée) et, si ``FAST_TIMING=1``,
        ``timings_ms`` (postes en ms). ``elapsed_ms`` garde son sens : total serveur.
        """
        from imaging import decode_image

        timers: dict = {}
        started = now()
        with Stage(timers, "decode_ms"):
            image = decode_image(image_b64)
        return self._live_from_image(image, prompts, max_objects, work_edge, batch_prompts,
                                     edge, mask_encoding, mask_format, mask_edge, timers, started)

    def _live_from_image(self, image, prompts, max_objects, work_edge, batch_prompts, edge,
                         mask_encoding, mask_format, mask_edge, timers, started) -> dict:
        """Suite de :meth:`_live_sync` sur une image déjà décodée (entrée JSON ou binaire)."""
        from segmentation import MAX_OBJECTS, normalize_prompts

        concepts = normalize_prompts(prompts if prompts is not None else list(LIVE_DEFAULT_PROMPTS))
        cap = clamp_int(max_objects, MAX_OBJECTS_DEFAULT, MAX_OBJECTS_MIN, MAX_OBJECTS)
        work = clamp_int(work_edge, WORK_EDGE_DEFAULT, WORK_EDGE_MIN, WORK_EDGE_MAX)
        batch = BATCH_PROMPTS if batch_prompts is None else bool(int(batch_prompts))
        result = run_frame(self.ctx, image, concepts, work, cap, batch, edge=edge,
                           mask_encoding=mask_encoding, gpu_lock=self._gpu_lock, timers=timers,
                           mask_format=mask_format, mask_edge=mask_edge or 0)
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
        extra = {}
        if accepts_param(self._live_sync, "mask_encoding"):
            extra["mask_encoding"] = request.mask_encoding
        if accepts_param(self._live_sync, "mask_format"):
            extra["mask_format"] = getattr(request, "mask_format", None)
            extra["mask_edge"] = getattr(request, "mask_edge", None) or 0
        return await call(self._live_sync, *args, **extra)

    async def _run_live_binary(self, request) -> dict:
        """``POST /live`` binaire : JPEG brut + knobs en query, même réponse que le JSON."""
        from imaging import decode_jpeg_bytes

        raw = await request.body()
        params = request.query_params

        def _int(name):
            try:
                return int(params[name])
            except (KeyError, TypeError, ValueError):
                return None

        prompts = params.getlist("prompts") or None

        def run():
            timers: dict = {}
            started = now()
            with Stage(timers, "decode_ms"):
                image = decode_jpeg_bytes(raw)
            return self._live_from_image(
                image, prompts, _int("max_objects"), _int("work_edge"),
                _int("batch_prompts"), _int("edge"), params.get("mask_encoding"),
                params.get("mask_format"), _int("mask_edge") or 0, timers, started)

        return await call(run)

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

    async def _ws_answer(self, websocket, raw, frame_seconds) -> bool:
        """Traite un message reçu : inférence bornée, réponse bornée, envoi.

        Renvoie False quand la boucle doit s'arrêter (réponse trop grosse,
        client déconnecté, socket cassé), True pour attendre le message suivant.
        """
        from fastapi import WebSocketDisconnect

        from live_ws import UNKNOWN_ID, error_message, frame_id

        try:
            if raw is None:
                reply = error_message(UNKNOWN_ID, 400, "expected a JSON text message")
            else:
                try:
                    reply = await asyncio.wait_for(self._ws_frame(raw),
                                                   timeout=frame_seconds)
                except asyncio.TimeoutError:
                    try:
                        stuck = frame_id(json.loads(raw).get("id"))
                    except Exception:  # noqa: BLE001 — frame illisible, id inconnu
                        stuck = UNKNOWN_ID
                    print(f"ws: frame id={stuck} > {frame_seconds:g} s, erreur 503",
                          flush=True)
                    reply = error_message(stuck, 503, "inference timeout, retry")
            text = json.dumps(reply, separators=(",", ":"))
            print(f"ws id={reply.get('id')} in={len(raw) if raw else 0}B "
                  f"out={len(text)}B objs={len(reply.get('objects', []))}", flush=True)
            if len(text) > WS_MAX_REPLY_BYTES:
                print(f"ws id={reply.get('id')}: {len(text)}B > "
                      f"{WS_MAX_REPLY_BYTES}B, fermeture 1009", flush=True)
                await websocket.close(code=1009, reason="response too large")
                return False
            await websocket.send_text(text)
            return True
        except WebSocketDisconnect:
            return False
        except Exception:  # noqa: BLE001 — client parti entre réception et réponse
            return False

    async def _ws_loop(self, websocket, idle_seconds=WS_IDLE_SECONDS,
                       frame_seconds=WS_FRAME_SECONDS) -> None:
        """Une frame à la fois par connexion ; le verrou GPU global reste la seule file.

        Anti-zombie : réception bornée (45 s d'inactivité → fermeture 1011),
        inférence bornée (180 s → erreur 503 à la frame, connexion gardée),
        réponse bornée (4 Mo → fermeture 1009) et fermeture explicite sur tous
        les chemins. Sans ces bornes, chaque timeout client laisse une connexion
        pendante 300 s, et deux zombies saturent le conteneur (toute reconnexion
        queue alors indéfiniment : c'est le freeze observé). Les seuils sont des
        paramètres pour que les tests les exercent en millisecondes.
        """
        from fastapi import WebSocketDisconnect

        from live_ws import UNKNOWN_ID, error_message, frame_id

        try:
            while True:
                try:
                    raw = await asyncio.wait_for(websocket.receive_text(),
                                                 timeout=idle_seconds)
                except asyncio.TimeoutError:
                    print(f"ws: inactif depuis {idle_seconds:g} s, fermeture 1011", flush=True)
                    await websocket.close(code=1011, reason="idle")
                    return
                except WebSocketDisconnect:
                    return
                except Exception:  # noqa: BLE001 — message binaire ou socket déjà fermé
                    raw = None
                if not await self._ws_answer(websocket, raw, frame_seconds):
                    return
        finally:
            try:
                await websocket.close()
            except Exception:  # noqa: BLE001 — déjà fermé, rien à faire
                pass

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
                    "attn_impl": self.ctx.get("attn_impl"),
                    "detr_graph": (self.ctx["detr_graphs"].status()
                                   if self.ctx.get("detr_graphs") else None)}}

    @asynccontextmanager
    async def _lifespan(self, _api):
        """Charge le modèle avant la première requête (Baseten : uvicorn sans ``enter``)."""
        await asyncio.to_thread(self.load_container)
        yield

    async def _handle_live(self, request):
        """Frame JSON (contrat historique) ou JPEG brut (``octet-stream`` + knobs en query).

        Le binaire évite les +33 % du base64 à la montée : le corps est le JPEG tel
        quel, les knobs passent en query (``prompts`` répété, ``work_edge``,
        ``max_objects``, ``mask_encoding``, ``mask_format``, ``mask_edge``,
        ``batch_prompts``, ``edge``). Réponse strictement identique dans les deux cas.
        """
        from fastapi.responses import JSONResponse
        from schemas import LiveFastRequest

        ctype = request.headers.get("content-type", "")
        if ctype.split(";")[0].strip().lower() == "application/octet-stream":
            result = await self._run_live_binary(request)
        else:
            try:
                parsed = LiveFastRequest(**await request.json())
            except Exception as exc:  # noqa: BLE001 — même 400 string que le handler
                from pydantic import ValidationError as PydanticValidationError

                if isinstance(exc, PydanticValidationError):
                    return JSONResponse(status_code=400,
                                        content={"detail": _validation_detail(exc.errors())})
                return JSONResponse(status_code=400,
                                    content={"detail": "invalid request body: JSON illisible"})
            result = await self._run_live(parsed)
        # En-tête additif (RFC 8673) : le levier FAST_TIMING reste le seul interrupteur.
        # Éteint, `timings_ms` est absent et aucun en-tête n'est émis — un client ancien
        # ne voit strictement rien changer ; un banc lit les postes sans parser le corps.
        header = format_server_timing(result.get("timings_ms") if isinstance(result, dict)
                                      else None)
        if not header:
            return result
        return JSONResponse(content=result, headers={"Server-Timing": header})
    async def _handle_release(self, request):
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
    def api(self):
        """FastAPI : /health, /segment (SPEC), /live (LIVE-SPEC + knobs), /live/ws, /release."""
        from fastapi import FastAPI, Request, WebSocket
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse
        from schemas import SegmentRequest

        service = self
        api = FastAPI(title=MODEL_LABEL, version="1.0.0", lifespan=self._lifespan)
        gzip_min = clamp_int(os.environ.get("FAST_GZIP_MIN_BYTES"), 1024, 0, 65536)
        if gzip_min > 0:
            from fastapi.middleware.gzip import GZipMiddleware

            api.add_middleware(GZipMiddleware, minimum_size=gzip_min)

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
        async def live(request: Request):
            """Frame JSON ou JPEG brut — voir _handle_live."""
            return await service._handle_live(request)


        @api.post("/release")
        async def release(request: Request):
            """Éteint le conteneur — voir _handle_release."""
            return await service._handle_release(request)


        @api.websocket("/live/ws")
        async def live_ws(websocket: WebSocket):
            """Même pipeline que POST /live, un message texte JSON par frame."""
            await websocket.accept()
            await service._ws_loop(websocket)

        return api