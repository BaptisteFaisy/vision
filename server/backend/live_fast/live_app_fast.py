"""Conteneur Modal « sam3-live » : la logique est dans ``service.py``, ici l'amorçage.

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
    FAST_DETR_GRAPH=1      CUDA graph de la passe DETR (vérifié, repli eager)
    FAST_ATTN=sdpa         attention fusionnée (eager matérialise, ~55 ms)
    FAST_DTYPE=bf16|fp16|fp32
    FAST_TIMING=1          réponse enrichie d'un détail par étage (vision/detr/masks)

Déploiement (GPU, région, nom d'app en variables — jamais lancé d'ici) :

    FAST_APP_NAME=sam3-live FAST_GPU=A100 FAST_REGION=eu-west \
      modal deploy backend/live_fast.py

Le MÊME service tourne sur Baseten (``backend/baseten/``) : seule la couche d'amorçage
change, les routes et les leviers sont identiques.
"""

# NOTE: pas de `from __future__ import annotations` — FastAPI résout les annotations des
# modèles de requête au runtime ; avec des strings différées le corps serait pris pour un
# paramètre de query (400 systématique). Contrat identique à live_app.py.
import os
from pathlib import Path

import modal

from fast_core import clamp_int
from service import Sam3LiveService

APP_NAME = os.environ.get("LIVE_APP_NAME", "sam3-eu-a100")
GPU_TYPE = os.environ.get("FAST_GPU", "A100")
REGION = os.environ.get("FAST_REGION") or None
ROUTING = os.environ.get("FAST_ROUTING_REGION") or None
# Plafond 60 s : une sortie ratée coûte ≤ 0,04 $, pas 0,20 $ (règle user 27/10 :
# éteinte = 0 €). La voie normale reste POST /release (~1 s).
SCALEDOWN = clamp_int(os.environ.get("FAST_SCALEDOWN"), 20, 5, 60)
# Warm pool : 0 = extinction après le bail (défaut) ; 1 = conteneur chaud permanent
# (cold start supprimé, ~500 $/mois A100). Jamais plus de 1 : UN SEUL GPU, JAMAIS DEUX.
MIN_CONTAINERS = clamp_int(os.environ.get("FAST_MIN_CONTAINERS"), 0, 0, 1)
SNAPSHOT = os.environ.get("FAST_SNAPSHOT", "1").strip().lower() in ("1", "true", "yes", "on")
MODEL_DIR = os.environ.get("FAST_MODEL_DIR", "/opt/models/sam3.1")
BACKEND_DIR = Path(__file__).resolve().parent

# Les poids sont téléchargés **au build de l'image**, plus au démarrage du conteneur :
# ~20 s gagnées sur les 32 s de chargement à froid mesurées fin octobre (le Hub HF renvoyait
# même un avertissement de débit non authentifié). Étape cachée par le build Modal.
WEIGHTS_BUILD = (
    'python -c "from huggingface_hub import snapshot_download; '
    + f"snapshot_download('unsloth/sam3.1', local_dir='{MODEL_DIR}')"
    + '"'
)

# Leviers lus au moment du DEPLOIEMENT puis recuits dans l'environnement de l'image :
# le conteneur importe ce module au démarrage, ses os.environ sont donc ceux de l'image
# (les variables du shell de déploiement ne sont pas propagées telles quelles).
LEVER_NAMES = ("FAST_BATCH_PROMPTS", "FAST_EDGE", "FAST_EDGE_RUNTIME", "FAST_COMPILE",
                "FAST_CUDA_GRAPH", "FAST_DETR_GRAPH", "FAST_DTYPE", "FAST_TIMING",
                "FAST_COMPILE_WARMUPS", "FAST_GZIP_MIN_BYTES", "FAST_SNAPSHOT", "FAST_ATTN")


def lever_env() -> dict:
    """Env de l'image : PYTHONPATH + les leviers positionnés au déploiement."""
    values = {name: os.environ.get(name, "") for name in LEVER_NAMES}
    values["PYTHONPATH"] = "/root:/root/backend_src"
    values["BACKEND_PLATFORM"] = "modal"
    values["FAST_MODEL_DIR"] = MODEL_DIR
    return values


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
    # sous les 10 s (sans lui : ~12 s de poids + scheduling, relevé antérieur).
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
class Sam3Live(Sam3LiveService):
    """Conteneur de probe : modèle chargé une fois, leviers activables, verrou GPU unique."""

    @modal.enter(snap=True)
    def load_weights(self) -> None:
        """Phase snapshottée : poids sur **CPU** (aucun état CUDA, sinon Modal refuse)."""
        self.load_cpu_weights()

    @modal.enter()
    def load_container_on_enter(self) -> None:
        """Après restauration : passage GPU + leviers (idempotent, le lifespan suit)."""
        self.load_container()

    @modal.asgi_app()
    def web(self):
        """FastAPI : /health, /segment (SPEC), /live (LIVE-SPEC + knobs), /live/ws."""
        return self.api()