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
* ``@modal.concurrent(max_inputs=2)`` : deux entrées par conteneur (une seule inférence à
  la fois, ``_gpu_lock``), ce qui supprime l'attente de ~90 ms de la 2ᵉ frame en vol ;
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
from timing import Stage, elapsed_ms, format_server_timing, now

APP_NAME = os.environ.get("LIVE_APP_NAME", "sam3-eu-a100")
GPU_TYPE = os.environ.get("FAST_GPU", "A100")
REGION = os.environ.get("FAST_REGION") or None
ROUTING = os.environ.get("FAST_ROUTING_REGION") or None
SCALEDOWN = clamp_int(os.environ.get("FAST_SCALEDOWN"), 20, 5, 300)
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
# ~20 s gagnées sur les 32 s de chargement à froid mesurées le 25/10 (le Hub HF renvoyait
# même un avertissement de débit non authentifié). Étape cachée par le build Modal.
WEIGHTS_BUILD = (
    'python -c "from huggingface_hub import snapshot_download; '
    + f"snapshot_download('unsloth/sam3.1', local_dir='{MODEL_DIR}')"
    + '"'
)


def container_age_seconds():
    """Âge du conteneur en secondes (uptime machine - début du processus).

    Sert à mesurer le VRAI temps d'ouverture : après une restauration de snapshot, le
    `enter(snap=True)` n'est pas rejoué, donc le chrono du code ne voit que la phase GPU.
    """
    try:
        with open("/proc/uptime", "r", encoding="utf-8") as handle:
            uptime = float(handle.read().split()[0])
        with open("/proc/self/stat", "r", encoding="utf-8") as handle:
            fields = handle.read().rsplit(") ", 1)[1].split()
        start_ticks = int(fields[19])
        hertz = os.sysconf("SC_CLK_TCK")
        return round(uptime - start_ticks / hertz, 1)
    except Exception:  # noqa: BLE001 — mesure best-effort, jamais bloquante
        return None


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


def live_response(result):
    """Réponse de ``POST /live`` : le résultat, plus ``Server-Timing`` si les chronos existent.

    En-tête additif (RFC 8673) : le levier ``FAST_TIMING`` reste le seul interrupteur.
    Éteint, ``timings_ms`` est absent et aucun en-tête n'est émis — un client ancien ne
    voit strictement rien changer ; un banc lit les postes sans parser le corps.
    """
    header = format_server_timing(result.get("timings_ms") if isinstance(result, dict) else None)
    if not header:
        return result
    from fastapi.responses import JSONResponse

    return JSONResponse(content=result, headers={"Server-Timing": header})


@app.cls(
    image=image,
    gpu=GPU_TYPE,
    region=REGION,
    routing_region=ROUTING,
    timeout=300,
    startup_timeout=1200,  # torch.compile + warm-up au chargement : plus long que le simple load
    max_containers=1,      # UN SEUL GPU, JAMAIS DEUX
    min_containers=0,      # la carte s'éteint dès la fin du direct (bail 20 s)
    scaledown_window=SCALEDOWN,
    # Snapshot mémoire : le chargement CPU des poids est figé à la première ouverture,
    # les suivantes repartent de là. C'est LE levier qui fait passer l'ouverture à froid
    # sous les 10 s (sans lui : 12,3 s de poids + scheduling, mesuré le 25/10).
    enable_memory_snapshot=SNAPSHOT,
)
# DEUX ENTRÉES PAR CONTENEUR, UNE SEULE INFÉRENCE SUR LA CARTE. Sans ce décorateur, Modal
# n'admet qu'une entrée à la fois : la 2e frame en vol (le client iHear tourne à 2 créneaux)
# attend ~90 ms au proxy (mesuré : elapsed 91/90 ms mais ttfb 232 → 322 ms). Les routes sont
# `async` et le travail bloquant passe par `asyncio.to_thread` : les entrées concurrentes
# tournent en threads séparés, donc le décodage + le pré-traitement CPU de la frame N+1 se
# cachent sous le GPU de la frame N. `self._gpu_lock` garde l'inférence strictement
# sérialisée (une frame sur la carte, aucune VRAM en plus) et `max_containers=1` interdit
# toujours une seconde carte. Le décorateur se pose sur la CLASSE, sous `@app.cls`
# (doc Modal « Input concurrency »).
@modal.concurrent(max_inputs=2)
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
                          "vision_accel": self.ctx.get("vision_accel"), "dtype": DTYPE_NAME,
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

    @modal.asgi_app()
    def web(self):
        """FastAPI : /health, /segment (SPEC), /live (LIVE-SPEC + knobs), /live/ws."""
        from fastapi import FastAPI, Request, WebSocket
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse
        from schemas import LiveFastRequest, SegmentRequest

        api = FastAPI(title=APP_NAME, version="1.0.0")

        @api.exception_handler(RequestValidationError)
        async def invalid_body(_request, exc):
            return JSONResponse(status_code=400, content={"detail": _validation_detail(exc.errors())})

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

        @api.post("/live")
        async def live(request: LiveFastRequest):
            return live_response(await self._run_live(request))

        @api.websocket("/live/ws")
        async def live_ws(websocket: WebSocket):
            """Même pipeline que POST /live, un message texte JSON par frame."""
            await websocket.accept()
            await self._ws_loop(websocket)

        return api
