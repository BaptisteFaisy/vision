"""Service SAM 3.1 « live » — la logique du conteneur GPU, sans fournisseur.

Modèle, direct (``/live``, ``WS /live/ws``), ``/segment``, ``/release`` et l'app
FastAPI vivent ici ; les hôtes (``live_app_fast.py`` Modal, ``server.py`` Baseten)
ne fournissent que l'amorçage et le transport. Contrat : ``docs/SPEC.md``,
``docs/LIVE-SPEC.md`` — identique d'un hôte à l'autre, d'où la bascule possible.
"""

import asyncio
import os
import threading
import time
from contextlib import asynccontextmanager

import detr_graph
from fast_core import (BATCH_PROMPTS, DEPTH_EVERY, DTYPE_NAME, EDGE, RUNTIME_EDGE, TIMING,
                       clamp_int, gpu_name, levers, load, load_cpu, prepare, run_frame,
                       segment_frame, vision_grid)
from http_errors import validation_detail as _validation_detail
from live_app_utils import accepts_param, call
from passage import suggest_passage
from pipe_compat import accepts_param, call
from timing import (Stage, build_breakdown, container_age_seconds, elapsed_ms,
                      format_server_timing, now)
# Pompe WS extraite (garde < 500 l) : seuils ré-exportés (bancs, live_app).
from ws_pump import (WS_FRAME_SECONDS, WS_IDLE_SECONDS, WS_MAX_REPLY_BYTES, ws_answer,
                     ws_frame, ws_loop)

MODEL_LABEL = "sam3.1-fast"
# Laisse le temps de répondre au client avant d'arrêter le processus (POST /release).
RELEASE_GRACE_SECONDS = 0.5

# Défaut 512 (O2, ex-768) : aligné sur ce que tous les clients envoient déjà
# (LiveSession/LivePipeline WORK_EDGE = 512) — ne sert qu'aux clients muets.
# Mesuré : 512→768 = +7 ms/frame seulement (240→247 ms) : gain modeste, gratuit.
WORK_EDGE_DEFAULT = 512
# Plancher 512 (reel audit 31/10, ex-256) : sous 512 px le petit feu lointain et
# les zébrures ne sont plus segmentés — un client qui demande moins reçoit 512.
WORK_EDGE_MIN = 512
WORK_EDGE_MAX = 1024
# Backstop quand le client n'envoie pas `max_objects` (il envoie son plafond,
# 16 depuis le patch foule du 01/11, ex-8 : en foule, 10 personnes ; 8 objets =
# la moitié de la scène perdue) : 16 (reel audit 31/10, ex-12) — les 17 prompts
# par défaut tiennent presque tous (un objet chacun) et les places gardées
# (feu, passage, bordure) ne chassent plus un concept entier.
# Plafond absolu : segmentation.MAX_OBJECTS (32).
MAX_OBJECTS_DEFAULT = 16
MAX_OBJECTS_MIN = 1
LIVE_DEFAULT_PROMPTS = ("person", "car", "dog", "chair")


def deploy_sha() -> str:
    """SHA déployé, cuit dans l'image (``DEPLOY_SHA``) ; ``unknown`` sans lui.

    Zéro appel git au runtime (pas de dépôt dans l'image) : le script de déploiement
    pose la variable, ``lever_env()`` la recuit — ``/health`` ne fait que la lire.
    """
    return os.environ.get("DEPLOY_SHA") or "unknown"


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
        """Charge le modèle au dtype demandé, applique résolution/compile/graph, chronomètre.

        ``load_seconds`` ne mesure que cette phase GPU (post-restauration) ; le VRAI temps
        d'ouverture (restauration du snapshot comprise) est ``load_breakdown``, figé ici.
        """
        if getattr(self, "ctx", None) is not None:  # déjà chargé : ne rien refaire
            return
        from segmentation import device_label

        started = time.monotonic()
        age = container_age_seconds()
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
            self.ctx["detr_patch"] = detr_graph.patch_detr_capture()
            self.ctx["detr_graphs"] = detr_graph.DetrGraphs()
        else:
            self.ctx["detr_graphs"] = None
        from depth import ensure_depth
        ensure_depth(self.ctx, device, dtype)
        self._gpu_lock = threading.Lock()
        self._warmup_frame()
        self.gpu_name = gpu_name()
        self.load_seconds = round(time.monotonic() - started, 1)
        snap = getattr(self, "snap_seconds", None)
        self.load_breakdown = {"age_conteneur_s": age,
                               "poids_cpu_1er_demarrage_s": snap,
                               "gpu_s": self.load_seconds}
        # Restauration = freeze/thaw Modal : l'âge /proc inclut le gel (mesuré : thaw
        # à 185,8 s pour un snap de 20,4 s) ; boot complet : écart ≈ 2-5 s. Seuil
        # 30 s : le gel minimal (scaledown 60 s) le dépasse toujours (29/10/2026).
        self.restored = (age is not None and snap is not None and age - snap > 30)

    def _warmup_frame(self) -> None:
        """Une frame factice jetée : capture DETR + cache tokenize chauds avant le réel.

        Post-restore donc graphes valides ; non-bloquant — le boot ne casse jamais
        là-dessus (bancs sans modèle : l'échec est avalé et loggé).
        """
        from PIL import Image

        try:
            run_frame(self.ctx, Image.new("RGB", (512, 512)),
                      list(LIVE_DEFAULT_PROMPTS), WORK_EDGE_DEFAULT, MAX_OBJECTS_DEFAULT,
                      BATCH_PROMPTS, gpu_lock=self._gpu_lock, with_depth=False)
        except Exception as exc:  # noqa: BLE001 — le pré-chauffe est un bonus
            print(f"warmup: frame factice ignorée ({exc})", flush=True)

    def _live_sync(self, image_b64, prompts, max_objects, work_edge, batch_prompts, edge,
                   mask_encoding=None, mask_format=None, mask_edge=0,
                   with_depth=False) -> dict:
        """Une frame de direct : décodage, segmentation avec les leviers effectifs, chrono.

        Le décodage et le pré-traitement CPU sont **hors** du verrou GPU (``run_frame`` ne
        garde sous verrou que l'encodeur vision et les passes DETR) : avec deux entrées par
        conteneur, la frame N+1 se prépare pendant l'inférence de N. Le résultat porte en
        plus ``mask_encoding`` (la représentation appliquée) et, si ``FAST_TIMING=1``,
        ``timings_ms`` (postes en ms). ``elapsed_ms`` garde son sens : total serveur.
        ``breakdown_ms`` (vision / DETR / overhead, somme = total) est émis toujours.
        """
        from imaging import decode_image

        timers: dict = {}
        started = now()
        with Stage(timers, "decode_ms"):
            image = decode_image(image_b64)
        return self._live_from_image(image, prompts, max_objects, work_edge, batch_prompts,
                                     edge, mask_encoding, mask_format, mask_edge, timers, started,
                                     with_depth)

    def _live_from_image(self, image, prompts, max_objects, work_edge, batch_prompts, edge,
                         mask_encoding, mask_format, mask_edge, timers, started,
                         with_depth=False) -> dict:
        """Suite de :meth:`_live_sync` sur une image déjà décodée (entrée JSON ou binaire)."""
        from feux_guard import coherent_work_edge
        from segmentation import MAX_OBJECTS, normalize_prompts

        concepts = normalize_prompts(prompts if prompts is not None else list(LIVE_DEFAULT_PROMPTS))
        cap = clamp_int(max_objects, MAX_OBJECTS_DEFAULT, MAX_OBJECTS_MIN, MAX_OBJECTS)
        work = clamp_int(work_edge, WORK_EDGE_DEFAULT, WORK_EDGE_MIN, WORK_EDGE_MAX)
        work = coherent_work_edge(work, image.size)
        batch = BATCH_PROMPTS if batch_prompts is None else bool(int(batch_prompts))
        result = run_frame(self.ctx, image, concepts, work, cap, batch, edge=edge,
                           mask_encoding=mask_encoding, gpu_lock=self._gpu_lock, timers=timers,
                           mask_format=mask_format, mask_edge=mask_edge or 0,
                           with_depth=with_depth)
        timers["total_ms"] = elapsed_ms(started)
        result["elapsed_ms"] = int(round(timers["total_ms"]))
        result["breakdown_ms"] = build_breakdown(timers, timers["total_ms"])
        result["fast"] = {"batch_prompts": batch, "edge": result.pop("_edge_applied"),
                          "edge_asked": edge, "edge_ok": result.pop("_edge_ok"),
                          "vision_accel": self.ctx.get("vision_accel"),
                        "attn": getattr(getattr(self.ctx.get("model"), "config", None),
                                        "_attn_implementation", "?"), "dtype": DTYPE_NAME,
                          "timings": TIMING}
        result["passage"] = suggest_passage(result.get("objects"), result["width"], result["height"])
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
        if accepts_param(self._live_sync, "with_depth"):
            extra["with_depth"] = bool(getattr(request, "with_depth", False))
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
                params.get("mask_format"), _int("mask_edge") or 0, timers, started,
                _int("with_depth") == 1)

        return await call(run)

    async def _run_live_binary_ws(self, request, jpeg: bytes) -> dict:
        """``WS /live/ws`` binaire : JPEG brut + knobs de l'en-tête, même réponse que le texte."""
        from imaging import decode_jpeg_bytes

        def run():
            timers: dict = {}
            started = now()
            with Stage(timers, "decode_ms"):
                image = decode_jpeg_bytes(jpeg)
            return self._live_from_image(
                image, request.prompts, request.max_objects, request.work_edge,
                request.batch_prompts, request.edge, request.mask_encoding,
                getattr(request, "mask_format", None),
                getattr(request, "mask_edge", None) or 0, timers, started,
                bool(getattr(request, "with_depth", False)))

        return await call(run)

    def _segment_sync(self, image_b64, prompts) -> dict:
        """Route historique /segment : concepts choisis par le serveur (docs/SPEC.md)."""
        from imaging import decode_image
        from segmentation import MAX_OBJECTS, normalize_prompts, run_segmentation

        image = decode_image(image_b64)
        concepts = normalize_prompts(prompts) if prompts is not None else None
        if concepts is None:
            with self._gpu_lock:
                return run_segmentation(self.ctx["model"], self.ctx["processor"],
                                        self.ctx["device"], self.ctx["dtype"], image, None)
        result = segment_frame(self.ctx, image, concepts, WORK_EDGE_MAX, MAX_OBJECTS,
                               BATCH_PROMPTS, gpu_lock=self._gpu_lock)
        result.pop("_edge_applied", None)
        result.pop("_edge_ok", None)
        return result

    async def _ws_frame(self, raw, depth_state=None) -> dict:
        """Un message → un message (résultat ou erreur) ; voir ``ws_pump.ws_frame``."""
        return await ws_frame(self, raw, depth_state, DEPTH_EVERY)

    async def _ws_answer(self, websocket, raw, frame_seconds, depth_state=None) -> bool:
        """Traite un message reçu ; voir ``ws_pump.ws_answer``."""
        return await ws_answer(self, websocket, raw, frame_seconds, depth_state,
                               DEPTH_EVERY)

    async def _ws_loop(self, websocket, idle_seconds=WS_IDLE_SECONDS,
                       frame_seconds=WS_FRAME_SECONDS) -> None:
        """Boucle de connexion ; voir ``ws_pump.ws_loop`` (DEPTH_EVERY lu en global)."""
        await ws_loop(self, websocket, idle_seconds, frame_seconds, DEPTH_EVERY)

    def health(self) -> dict:
        """État du conteneur : modèle, carte, région, boot, leviers appliqués.

        Région/cloud lus sur les deux hôtes (``MODAL_*`` / ``BASETEN_*``) vers un champ
        stable ; ``load_seconds`` = phase GPU seule, ``restored``/``load_breakdown`` le boot.
        """
        sha = deploy_sha()
        return {"ok": True, "model": MODEL_LABEL, "version": MODEL_LABEL,
                "sha": sha, "commit": sha, "ws_binary": True,
                "device": self.ctx.get("device_label", "cpu"),
                "depth": self.ctx.get("depth_model_id"),
                "gpu": getattr(self, "gpu_name", "unknown"),
                "region": (os.environ.get("MODAL_REGION") or os.environ.get("BASETEN_REGION")
                           or "unknown"),
                "cloud": (os.environ.get("MODAL_CLOUD_PROVIDER")
                          or os.environ.get("BASETEN_CLOUD") or "unknown"),
                "platform": os.environ.get("BACKEND_PLATFORM", "unknown"),
                "load_seconds": getattr(self, "load_seconds", None),
                "load_breakdown": getattr(self, "load_breakdown", None),
                # Via levers() (pas d'import direct de SNAPSHOT) : les deux fast_core
                # exposent levers(), seul celui de live_fast porte le flag snapshot.
                "memory_snapshot": levers().get("snapshot", False),
                "restored": getattr(self, "restored", None),
                "levers": levers(), "applied": {
                    "edge_applied": self.ctx.get("edge_applied"),
                    "vision_grid": self.ctx.get("vision_grid"),
                    "vision_accel": self.ctx.get("vision_accel"),
                    "attn_impl": self.ctx.get("attn_impl"),
                    "detr_patch": self.ctx.get("detr_patch"),
                    "detr_graph": (self.ctx["detr_graphs"].status()
                                   if self.ctx.get("detr_graphs") else None),
                    "post": levers().get("post", "hf")}}

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
        client voit un timeout au lieu d'une confirmation ; le processus se tue juste
        après (`os._exit`, pas de gel — voir `docs/LIVE-PERF.md`) : `min_containers=0`
        ne relance rien, le prochain réveil est un boot complet, jamais un restore.
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
            """Même pipeline que POST /live : un message texte JSON ou binaire (M1) par frame."""
            await websocket.accept()
            print("ws: accept /live/ws", flush=True)
            await service._ws_loop(websocket)

        return api
