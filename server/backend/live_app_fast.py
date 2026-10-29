"""Conteneur Modal « sam3-live » : /live avec les leviers de latence isolés.

Même contrat HTTP que ``live_app.py`` (``GET /health``, ``POST /segment``, ``POST /live``)
plus trois knobs optionnels dans le corps de ``/live`` — ``batch_prompts``, ``edge``,
pour pouvoir mesurer deux leviers A/B **dans un seul conteneur** (une seule carte à la
fois) : les variables d'environnement posent le défaut, la requête peut le surcharger.

S'y ajoute ``WS /live/ws`` : le même pipeline (``_live_sync``), le même corps de requête
(``schemas.LiveWsRequest``), la même réponse que ``POST /live`` plus un ``id`` de
corrélation — un message texte JSON par frame, une frame à la fois par connexion, pour
supprimer l'overhead HTTP par frame (mesures dans ``docs/LIVE-PERF.md``).

Depuis le portage de latence (``docs/LATENCE-PROD.md``) :

* ``mask_encoding`` (``png`` par défaut, ``bits`` sur demande, ``none`` pour les boîtes)
  est négocié sur ``POST /live`` **et** sur ``/live/ws`` ; la réponse porte toujours la
  représentation appliquée ;
* ``@modal.concurrent(max_inputs=4)`` : quatre slots par conteneur (une seule inférence
  à la fois, ``_gpu_lock``), ce qui supprime l'attente des frames en vol et laisse un
  zombie résiduel sans bloquer les reconnexions ;
* ``run_frame`` garde le verrou GPU sur la seule inférence : le décodage, le préprocess et
  l'encodage des masques se font hors verrou ;
* ``FAST_TIMING=1`` ajoute ``timings_ms`` à la réponse et l'en-tête ``Server-Timing``.

Leviers (voir ``fast_core.py`` pour le détail technique) :

    FAST_BATCH_PROMPTS=1   un seul forward DETR pour tous les concepts
    FAST_EDGE=448          surcharge la taille d'entrée du préprocesseur (1008 par défaut)
    FAST_COMPILE=1         torch.compile de l'encodeur vision + warm-up
    FAST_CUDA_GRAPH=1      CUDA graph de l'encodeur vision (exclusive de FAST_COMPILE)
    FAST_DTYPE=bf16|fp16|fp32
    FAST_TIMING=1          réponse enrichie d'un détail par étage (vision/detr/masks)

Déploiement (GPU, région, nom d'app en variables — jamais lancé d'ici) :

    FAST_APP_NAME=sam3-live FAST_GPU=A100 FAST_REGION=eu-west \
      modal deploy backend/live_fast.py

Seul ``modal`` est importé au niveau du module : torch, transformers, PIL, fastapi et les
helpers restent dans les fonctions côté conteneur, pour que le fichier soit importable
sans stack GPU.
"""

# NOTE: pas de `from __future__ import annotations` — FastAPI résout les annotations des
# modèles de requête au runtime ; avec des strings différées le corps serait pris pour un
# paramètre de query (400 systématique). Contrat identique à live_app.py.
import asyncio
import inspect
import json
import os
import threading
import time
from pathlib import Path

import modal

from fast_core import (BATCH_PROMPTS, DTYPE_NAME, EDGE, RUNTIME_EDGE, TIMING, clamp_int, load_cpu,
                       gpu_name, levers, load, prepare, run_frame, segment_frame, vision_grid)
from http_errors import MAX_DETAIL_CHARS, validation_detail as _validation_detail
from live_app_utils import (accepts_mask_encoding, accepts_param, call, container_age_seconds,
                            live_response)
from timing import Stage, elapsed_ms, format_server_timing, now

APP_NAME = os.environ.get("LIVE_APP_NAME", "sam3-eu-a100")
GPU_TYPE = os.environ.get("FAST_GPU", "A100")
REGION = os.environ.get("FAST_REGION") or None
ROUTING = os.environ.get("FAST_ROUTING_REGION") or None
SCALEDOWN = clamp_int(os.environ.get("FAST_SCALEDOWN"), 20, 5, 300)
# Warm pool : 0 = la carte s'éteint après le bail (défaut, économe) ; 1 = un
# conteneur chaud en permanence (~500 $/mois A100, cold start supprimé sur les
# plages d'usage). Jamais plus de 1 : UN SEUL GPU, JAMAIS DEUX.
MIN_CONTAINERS = clamp_int(os.environ.get("FAST_MIN_CONTAINERS"), 0, 0, 1)
WS_IDLE_SECONDS = 45.0  # sans message : pair considéré mort, fermeture 1011 (zombie ≤ 45 s)
WS_FRAME_SECONDS = 180.0  # inférence max par frame : au-delà, erreur 503, connexion gardée
WS_MAX_REPLY_BYTES = 4_000_000  # réponse plus grosse : fermeture 1009 avant le proxy
# Descente gzip : les réponses portent des masques base64 (~5 Ko), très
# compressibles ; OkHttp l'accepte en transparence. 0 = désactivé.
GZIP_MIN_BYTES = clamp_int(os.environ.get("FAST_GZIP_MIN_BYTES"), 1024, 0, 65536)
# Délai avant autocommit de la mort : le temps que la réponse `/release` parte au téléphone.
RELEASE_DELAY_S = clamp_int(os.environ.get("FAST_RELEASE_DELAY"), 1, 1, 30)
SNAPSHOT = os.environ.get("FAST_SNAPSHOT", "1").strip().lower() in ("1", "true", "yes", "on")
MODEL_LABEL = "sam3.1-fast"
BACKEND_DIR = Path(__file__).resolve().parent

# Leviers lus au moment du DEPLOIEMENT puis recuits dans l'environnement de l'image :
# le conteneur importe ce module au démarrage, ses os.environ sont donc ceux de l'image
# (les variables du shell de déploiement ne sont pas propagées telles quelles).
LEVER_NAMES = ("FAST_BATCH_PROMPTS", "FAST_EDGE", "FAST_EDGE_RUNTIME", "FAST_COMPILE",
                "FAST_CUDA_GRAPH", "FAST_DTYPE", "FAST_TIMING", "FAST_COMPILE_WARMUPS")


MODEL_DIR = os.environ.get("FAST_MODEL_DIR", "/opt/models/sam3.1")

# Les poids sont téléchargés **au build de l'image**, plus au démarrage du conteneur :
# ~20 s gagnées sur les 32 s de chargement à froid mesurées (relevé antérieur, hors de
# cet arbre). Étape cachée par le build Modal.
WEIGHTS_BUILD = (
    'python -c "from huggingface_hub import snapshot_download; '
    + f"snapshot_download('unsloth/sam3.1', local_dir='{MODEL_DIR}')"
    + '"'
)


def lever_env() -> dict:
    """Env de l'image : PYTHONPATH + les leviers positionnés au déploiement."""
    values = {name: os.environ.get(name, "") for name in LEVER_NAMES}
    values["PYTHONPATH"] = "/root:/root/backend_src"
    values["FAST_MODEL_DIR"] = MODEL_DIR
    return values


WORK_EDGE_DEFAULT = 768
WORK_EDGE_MIN = 256
WORK_EDGE_MAX = 1024
MAX_OBJECTS_DEFAULT = 12
MAX_OBJECTS_MIN = 1
LIVE_DEFAULT_PROMPTS = ("person", "car", "dog", "chair")

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install_from_requirements(str(BACKEND_DIR / "requirements.txt"))
    # Poids embarqués : une fois au build (en cache), jamais au démarrage du conteneur.
    .run_commands(WEIGHTS_BUILD)
    # .env() est une étape de build : elle doit passer avant add_local_* (règle Modal).
    .env(lever_env())
    .add_local_dir(str(BACKEND_DIR), remote_path="/root/backend_src")
)

app = modal.App(APP_NAME)


@app.cls(
    image=image,
    gpu=GPU_TYPE,
    region=REGION,
    routing_region=ROUTING,
    timeout=3600,  # sessions longues saines ; les zombies meurent par idle-timeout 45 s
    startup_timeout=1200,  # torch.compile + warm-up au chargement : plus long que le simple load
    max_containers=1,      # UN SEUL GPU, JAMAIS DEUX
    min_containers=MIN_CONTAINERS,  # 0 = extinction après le bail ; 1 = warm pool (FAST_MIN_CONTAINERS)
    scaledown_window=SCALEDOWN,
    # Snapshot mémoire : le chargement CPU des poids est figé à la première ouverture,
    # les suivantes repartent de là. C'est LE levier qui fait passer l'ouverture à froid
    # sous les 10 s (sans lui : 12,3 s de poids + scheduling, relevé antérieur,
    # hors de cet arbre).
    enable_memory_snapshot=SNAPSHOT,
)
# DEUX ENTRÉES PAR CONTENEUR, UNE SEULE INFÉRENCE SUR LA CARTE. Sans ce décorateur, Modal
# n'admet qu'une entrée à la fois : la 2e frame en vol (le client iSee tourne à 2 créneaux)
# attend ~90 ms au proxy (mesuré : elapsed 91/90 ms mais ttfb 232 → 322 ms). Les routes sont
# `async` et le travail bloquant passe par `asyncio.to_thread` : les entrées concurrentes
# tournent en threads séparés, donc le décodage + le pré-traitement CPU de la frame N+1 se
# cachent sous le GPU de la frame N. `self._gpu_lock` garde l'inférence strictement
# sérialisée (une frame sur la carte, aucune VRAM en plus) et `max_containers=1` interdit
# toujours une seconde carte. Le décorateur se pose sur la CLASSE, sous `@app.cls`
# (doc Modal « Input concurrency »).
@modal.concurrent(max_inputs=4)  # 4 slots : un zombie résiduel ne bloque plus tout
class Sam3Live:
    """Conteneur de probe : modèle chargé une fois, leviers activables, verrou GPU unique."""

    @modal.enter(snap=True)
    def load_weights(self) -> None:
        """Phase snapshottée : poids + graphe sur **CPU** (aucun état CUDA, sinon Modal refuse)."""
        started = time.monotonic()
        model, processor, dtype = load_cpu(DTYPE_NAME, EDGE)
        self.cpu = {"model": model, "processor": processor, "dtype": dtype}
        self.snap_seconds = round(time.monotonic() - started, 1)

    @modal.enter()
    def load_container(self) -> None:
        """Phase après restauration : passage sur GPU, leviers, nom de carte, verrou.

        Après une restauration de snapshot, `load_weights` n'est **pas** rejoué : la
        mémoire du conteneur revient telle quelle, y compris `snap_seconds`. On marque
        donc explicitement le cas, et `load_seconds` ne mesure que le travail réellement
        refait après la restauration (passage GPU + leviers).
        """
        from segmentation import device_label

        started = time.monotonic()
        age = container_age_seconds()
        model, processor, dtype = self.cpu["model"], self.cpu["processor"], self.cpu["dtype"]
        device = device_label()
        model = model.to(device).eval()
        self.ctx = {"model": model, "processor": processor, "device": device, "dtype": dtype,
                    "runtime_edge": RUNTIME_EDGE}
        self.ctx.update(prepare(model, processor, device, dtype, EDGE))
        self.ctx["device_label"] = device_label()
        self.ctx["vision_grid"] = vision_grid(model)
        self.gpu_name = gpu_name()
        gpu_seconds = round(time.monotonic() - started, 1)
        # `container_age` = âge du conteneur quand la phase GPU commence : c'est le VRAI
        # temps d'ouverture (restauration du snapshot comprise). `snap_seconds` ne sert
        # qu'au tout premier démarrage (pas de snapshot encore disponible).
        self.load_seconds = age if age is not None else round(
            gpu_seconds + getattr(self, "snap_seconds", 0.0), 1)
        self.load_breakdown = {"age_conteneur_s": age,
                               "poids_cpu_1er_demarrage_s": getattr(self, "snap_seconds", None),
                               "gpu_s": gpu_seconds}
        del self.cpu
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
                          "vision_accel": self.ctx.get("vision_accel"), "dtype": DTYPE_NAME,
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

    async def _ws_step(self, websocket, raw, frame_seconds) -> bool:
        """Un message reçu : réponse calculée puis envoyée. Faux = fermer la boucle."""
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
                if not await self._ws_step(websocket, raw, frame_seconds):
                    return
        finally:
            try:
                await websocket.close()
            except Exception:  # noqa: BLE001 — déjà fermé, rien à faire
                pass

    @modal.asgi_app()
    def web(self):
        """FastAPI : /health, /segment (SPEC), /live (LIVE-SPEC + knobs), /live/ws."""
        from fastapi import FastAPI
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse

        api = FastAPI(title=APP_NAME, version="1.0.0")
        if GZIP_MIN_BYTES > 0:
            from fastapi.middleware.gzip import GZipMiddleware

            api.add_middleware(GZipMiddleware, minimum_size=GZIP_MIN_BYTES)

        @api.exception_handler(RequestValidationError)
        async def invalid_body(_request, exc):
            return JSONResponse(status_code=400, content={"detail": _validation_detail(exc.errors())})

        self._routes_probe(api)
        self._routes_live(api)
        self._routes_control(api)
        return api

    def _routes_probe(self, api):
        """GET /health (état + carte) et POST /segment (route historique, SPEC)."""
        from schemas import SegmentRequest

        @api.get("/health")
        async def health():
            return {"ok": True, "model": MODEL_LABEL, "device": self.ctx.get("device_label", "cpu"),
                    "gpu": getattr(self, "gpu_name", "unknown"),
                    "region": os.environ.get("MODAL_REGION", "unknown"),
                    "cloud": os.environ.get("MODAL_CLOUD_PROVIDER", "unknown"),
                    "load_seconds": getattr(self, "load_seconds", None),
                    "load_breakdown": getattr(self, "load_breakdown", None),
                    "weights_in_image": bool(os.environ.get("FAST_MODEL_DIR")),
                    "memory_snapshot": SNAPSHOT,
                    "levers": levers(), "applied": {
                        "edge_applied": self.ctx.get("edge_applied"),
                        "vision_grid": self.ctx.get("vision_grid"),
                        "vision_accel": self.ctx.get("vision_accel")}}

        @api.post("/segment")
        async def segment(request: SegmentRequest):
            return await call(self._segment_sync, request.image_b64, request.prompts)

    def _routes_live(self, api):
        """POST /live : frame JSON (contrat historique) ou JPEG brut + knobs en query."""
        from fastapi import Request
        from fastapi.responses import JSONResponse
        from schemas import LiveFastRequest

        @api.post("/live")
        async def live(request: Request):
            """Le binaire évite les +33 % du base64 ; réponse identique, 400 si illisible."""
            ctype = request.headers.get("content-type", "")
            if ctype.split(";")[0].strip().lower() == "application/octet-stream":
                return live_response(await self._run_live_binary(request))
            try:
                parsed = LiveFastRequest(**await request.json())
            except Exception as exc:  # noqa: BLE001 — même 400 string que le handler FastAPI
                from pydantic import ValidationError as PydanticValidationError

                if isinstance(exc, PydanticValidationError):
                    return JSONResponse(status_code=400,
                                        content={"detail": _validation_detail(exc.errors())})
                return JSONResponse(status_code=400,
                                    content={"detail": "invalid request body: JSON illisible"})
            return live_response(await self._run_live(parsed))

    def _routes_control(self, api):
        """POST /release (extinction GPU) et WS /live/ws (même pipeline que /live)."""
        from fastapi import Request, WebSocket

        @api.post("/release")
        async def release(request: Request):
            """Le téléphone ferme l'app : le conteneur se tue lui-même.

            Sans cet appel, la carte A100 reste allumée jusqu'à la fenêtre de
            réduintage (20 s ici) et, surtout, tant qu'un client continue de sonder
            `/health` — ce qui rend le bail invisible. `min_containers=0` : Modal ne
            relance rien, le prochain réveil repart d'un snapshot restauré (2-7 s).
            Garde : en-tête de confirmation requis, sinon 400 (pas d'extinction
            accidentelle par un simple curl).
            """
            from fastapi import HTTPException
            from fastapi.responses import JSONResponse

            if request.headers.get("x-sam-release") != "confirm":
                raise HTTPException(status_code=400, detail="extinction refusée : en-tête x-sam-release: confirm requis")
            asyncio.get_running_loop().call_later(RELEASE_DELAY_S, os._exit, 0)
            return JSONResponse({"released": True, "in_seconds": RELEASE_DELAY_S})

        @api.websocket("/live/ws")
        async def live_ws(websocket: WebSocket):
            """Même pipeline que POST /live, un message texte JSON par frame."""
            await websocket.accept()
            await self._ws_loop(websocket)
