"""Tracker vidéo SAM 3.1 multiplex — logique du direct HTTP pour l'app « sam3-track ».

Reprend le chemin validé par ``backend/probe_video_tracker.py`` (fichier figé, lecture
seule) : bucket public → patch de ``start_session`` → ``build_sam3_multiplex_video_predictor``
→ ``add_prompt`` sur la frame 0 → ``propagate_in_video``. Ce module y ajoute ce qui manque
pour un flux HTTP : le paquet n'expose **aucune** ``add_new_frame`` (le dispatch
``handle_request`` ne connaît que start_session/add_prompt/add_mask/remove_object/
reset/close), donc chaque session **pré-alloue un tampon de ``capacity`` frames** — la
frame 0 est répétée sur GPU par le chargeur patché — et la frame reçue est écrite dans la
fente du prochain indice, puis propagée une frame par une frame
(``start_frame_index=k, max_frame_num_to_track=1``). Les ids du tracker sont conservés
d'une frame à l'autre (état du masquelet persistant dans ``inference_state``).

torch/sam3 ne sont importés qu'à l'intérieur des fonctions : le module reste importable
hors conteneur GPU (``py_compile``, tests unitaires).
"""

from __future__ import annotations

import base64
import binascii
import os
import shutil
import time
from pathlib import Path

from concepts import model_concept

def _env_int(name: str, default: int) -> int:
    """Entier d'environnement (le conteneur ne voit que ce que `image.env()` a écrit)."""
    try:
        return int(os.environ.get(name) or default)
    except (TypeError, ValueError):
        return default


BUCKET = "Baptiste12312/sam3.1-bucket"
CKPT_DIR = os.environ.get("TRACK_CKPT_DIR", "/root/ckpts")
CKPT_FILES = ["sam3.1_multiplex.pt", "config.json"]  # le BPE vient de sam3/assets
MUX_COUNT = 16          # objets par bucket multiplex (figé par le checkpoint)
MAX_TRACKED_OBJECTS = 16  # objets par concept — aussi le pas d'offset des ids


CAPACITY_DEFAULT = 900  # frames par session (30 s à 30 fps), surchargeable par TRACK_CAPACITY
# Frames tirées d'un même générateur de propagation. Le détecteur travaille par *chunk* de
# frames (`batched_grounding_batch_size`) : un chunk de 8 = ~6 Go de tenseurs d'un coup
# (mesuré), 1 = ~14 Go stable. 1 = un générateur neuf par frame (fenêtre toujours fraîche,
# ids stables) ; > 1 = un générateur vivant d'une requête à l'autre.
BATCH_DEFAULT = _env_int("TRACK_BATCH", 0)  # 0 = un générateur pour la session
GROUNDING_BATCH = _env_int("TRACK_GROUNDING_BATCH", 1)  # frames par chunk du détecteur
# torch.compile : supprime une partie du surcoût Python/dispatch par frame (le modèle est
# appelé une frame à la fois en direct, là où le mode batch amortit ces coûts).
COMPILE = os.environ.get("TRACK_COMPILE") == "1"
PROMPT_LIMIT = 18       # concepts par session : chacun coûte une inférence de frame par /frame (18 = 17 + murs iSee, 31/10)
SESSIONS_ROOT = "/tmp/sessions"
OUTPUT_PROB_THRESH = 0.5


class PromptTooLong(ValueError):
    """Prompt refusé (trop long ou vide) — HTTP 400."""


class SessionUnknown(KeyError):
    """session_id inconnu du conteneur — HTTP 404."""


class CapacityFull(ValueError):
    """Tampon de frames de la session épuisé — HTTP 400, le client doit rouvrir une session."""


def fetch_checkpoint() -> float:
    """Télécharge le checkpoint depuis le bucket public ; renvoie la durée (0 si déjà là)."""
    from huggingface_hub import download_bucket_files

    if Path(os.path.join(CKPT_DIR, "sam3.1_multiplex.pt")).exists():
        return 0.0
    os.makedirs(CKPT_DIR, exist_ok=True)
    start = time.time()
    download_bucket_files(BUCKET, files=[(f, os.path.join(CKPT_DIR, f)) for f in CKPT_FILES])
    return round(time.time() - start, 1)


def patch_start_session() -> None:
    """Contourne un bug du paquet : ``init_state()`` multiplex n'accepte pas
    ``offload_state_to_cpu``, que ``Sam3BasePredictor.start_session`` passe quand même.
    On filtre les kwargs non supportés ; le reste du chemin (prompt, propagation) est intact.
    """
    import inspect
    import uuid

    from sam3.model.sam3_base_predictor import Sam3BasePredictor

    if getattr(Sam3BasePredictor.start_session, "_sam3track", False):
        return

    def start_session(self, resource_path, session_id=None, offload_video_to_cpu=False,
                      offload_state_to_cpu=False):
        params = inspect.signature(self.model.init_state).parameters
        kwargs = {"resource_path": resource_path}
        for name, value in (
            ("offload_video_to_cpu", offload_video_to_cpu),
            ("offload_state_to_cpu", offload_state_to_cpu),
            ("async_loading_frames", getattr(self, "async_loading_frames", None)),
        ):
            if name in params and value is not None:
                kwargs[name] = value
        state = self.model.init_state(**kwargs)
        sid = session_id or str(uuid.uuid4())
        self._all_inference_states[sid] = {
            "state": state, "session_id": sid, "start_time": time.time(), "last_use_time": time.time(),
        }
        return {"session_id": sid}

    start_session._sam3track = True
    Sam3BasePredictor.start_session = start_session


def patch_frame_loader() -> None:
    """Donne au chargeur un format de requête pour le direct : ``[image0, capacity]``.

    Les frames réelles passent par le chargeur du paquet (même normalisation exacte), puis
    la dernière est répétée sur GPU jusqu'à ``capacity`` — le tampon est donc prêt avant le
    premier ``/frame``. Sans patch, ``init_state`` échouerait sur ce type de ressource.
    """
    from sam3.model import sam3_multiplex_tracking as module
    from sam3.model.io_utils import load_resource_as_video_frames as base_loader

    if getattr(module.load_resource_as_video_frames, "_sam3track", False):
        return

    def padded(resource_path, *args, **kwargs):
        if (isinstance(resource_path, list) and len(resource_path) == 2
                and isinstance(resource_path[1], int) and not isinstance(resource_path[1], bool)):
            frames, capacity = resource_path
            images, height, width = base_loader([frames], *args, **kwargs)
            if capacity > images.shape[0]:
                # Tampon pré-alloué : seule la frame 0 est écrite (les fentes suivantes le
                # sont par /frame avant toute propagation). Zéro remplissage = /session court.
                buffer = images.new_empty((int(capacity), *images.shape[1:]))
                buffer[0].copy_(images[0])
                images = buffer
            return images, height, width
        return base_loader(resource_path, *args, **kwargs)

    padded._sam3track = True
    module.load_resource_as_video_frames = padded


def build_predictor(multiplex_count: int = MUX_COUNT, max_objects: int = MAX_TRACKED_OBJECTS):
    """Charge le checkpoint et construit le predictor multiplex (sans ``use_fa3`` ni compile)."""
    import torch

    from sam3.model_builder import build_sam3_multiplex_video_predictor

    patch_start_session()
    patch_frame_loader()
    started = time.time()
    predictor = build_sam3_multiplex_video_predictor(
        checkpoint_path=os.path.join(CKPT_DIR, "sam3.1_multiplex.pt"),
        multiplex_count=_env_int("TRACK_MUX", multiplex_count),
        max_num_objects=_env_int("TRACK_MAXOBJ", max_objects),
        use_fa3=False,
        compile=COMPILE,
        warm_up=False,
    )
    # Deux réglages du checkpoint sont écrits pour un run « vidéo entière » et rendent le
    # direct impossible tel quel : hotstart_delay=15 (la propagation retient 15 frames avant
    # de rien rendre) et postprocess_batch_size=16 (yield tous les 16 frames). Ici chaque
    # appel ne propage qu'une frame : on remet les deux à 0/1 pour que la frame calculée soit
    # rendue immédiatement — les objets sortent dès leur détection, et on n'est jamais forcé
    # de calculer une frame de padding pour obtenir la réponse.
    predictor.model.hotstart_delay = 0
    predictor.model.postprocess_batch_size = 1
    # Le détecteur calcule la grounding par *chunks* mémorisés sous une clé
    # `(chunk_start, chunk_end)` et ne nettoie que la clé précédente, calculée sur la même
    # grille. Le builder impose 16 frames par chunk : la clé sortante vaut alors
    # (frame-16, frame) et ne correspond jamais à celle qu'on a créée -> le cache grossissait
    # de ~200 Mo par frame (VRAM 5,6 -> 18 Go en 7 frames, OOM vers la frame 10). À 1, la clé
    # est (frame, frame+1) et le nettoyage tombe juste : la mémoire reste plate.
    predictor.model.batched_grounding_batch_size = GROUNDING_BATCH
    torch.cuda.synchronize()
    return predictor, round(time.time() - started, 1)


def normalize_frame(model, image):
    """Une frame PIL -> tenseur [1,3,S,S] fp16 GPU, avec la normalisation du paquet.

    Même recette que `load_resource_as_video_frames` (carré à `model.image_size`, /255,
    moyenne/écart du modèle) mais faite **sur GPU** : la version du paquet (PIL bicubique +
    numpy fp16 sur CPU) coûtait 30-45 ms par frame, soit un cinquième du budget 300 ms.
    """
    import numpy as np
    import torch
    import torch.nn.functional as F

    size = int(model.image_size)
    array = torch.from_numpy(np.asarray(image.convert("RGB")))
    frame = array.to("cuda").permute(2, 0, 1).unsqueeze(0).to(torch.float16) / 255.0
    frame = F.interpolate(frame, size=(size, size), mode="bicubic", align_corners=False)
    mean = torch.tensor(model.image_mean, dtype=torch.float16, device=frame.device)[:, None, None]
    std = torch.tensor(model.image_std, dtype=torch.float16, device=frame.device)[:, None, None]
    return (frame - mean) / std


def vram_mb() -> int | None:
    """Mémoire GPU réellement allouée par torch (Mo), pour suivre les fuites en direct."""
    try:
        import torch

        if torch.cuda.is_available():
            return int(torch.cuda.memory_allocated() / 1e6)
    except Exception:  # noqa: BLE001 — un relevé de VRAM ne doit jamais casser une frame
        pass
    return None


def _tensor_bytes(obj, depth: int = 0) -> int:
    """Taille approximative (octets) des tenseurs d'un objet, profondeur bornée (cycles)."""
    import torch

    if depth > 4:
        return 0
    if torch.is_tensor(obj):
        return obj.numel() * obj.element_size()
    if isinstance(obj, dict):
        return sum(_tensor_bytes(value, depth + 1) for value in obj.values())
    if isinstance(obj, (list, tuple, set)):
        return sum(_tensor_bytes(value, depth + 1) for value in obj)
    return 0


def _mb(value: int) -> int:
    """Octets -> mégaoctets."""
    return int(value / 1e6)


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


def box_from_norm(box, width: int, height: int) -> list:
    """[x,y,w,h] normalisés -> [x1,y1,x2,y2] en pixels, bornés dans l'image."""
    x, y, w, h = (float(value) for value in box)
    return [round(min(max(x * width, 0.0), width), 1), round(min(max(y * height, 0.0), height), 1),
            round(min(max((x + w) * width, 0.0), width), 1), round(min(max((y + h) * height, 0.0), height), 1)]


def objects_from_output(outputs, label: str, id_offset: int, size) -> list:
    """Corps du tracker (numpy, frame propagée) -> objets du contrat HTTP, ids stables."""
    from imaging import encode_mask_png_b64

    ids = outputs.get("out_obj_ids")
    if ids is None or len(ids) == 0:
        return []
    width, height = size
    boxes = outputs.get("out_boxes_xywh")
    probs = outputs.get("out_probs")
    masks = outputs.get("out_binary_masks")
    objects = []
    for index, obj_id in enumerate(ids):
        box = box_from_norm(boxes[index], width, height) if boxes is not None else [0.0, 0.0, width, height]
        score = float(probs[index]) if probs is not None and index < len(probs) else 0.0
        mask = masks[index] if masks is not None else None
        objects.append({
            "id": int(id_offset) + int(obj_id),
            "label": label,
            "score": round(score, 3),
            "box": box,
            "mask_b64": encode_mask_png_b64(mask, size) if mask is not None else "",
        })
    return objects


def clean_prompts(prompts) -> list:
    """Concepts du direct : dédoublonnés sur le concept modèle, bornés à PROMPT_LIMIT (le coût est linéaire)."""
    cleaned: list[str] = []
    for raw in prompts or []:
        text = " ".join(str(raw).split())[:64]
        if text and all(model_concept(text) != model_concept(done) for done in cleaned):
            cleaned.append(text)
    if not cleaned:
        raise PromptTooLong("prompts must contain at least one non-empty concept")
    return cleaned[:PROMPT_LIMIT]


def _jpeg_bytes_from_b64(payload: str) -> bytes:
    """Bytes JPEG de la requête, pour l'archive de session (déjà validée par decode_image)."""
    raw = payload.strip()
    if raw.startswith("data:"):
        raw = raw.partition(",")[2]
    try:
        return base64.b64decode(raw, validate=False)
    except (binascii.Error, ValueError):
        return b""


class LiveSession:
    """Une session de direct : une sous-session tracker par concept + un compteur de frames.

    La frame 0 est consommée par ``start()`` (prompt texte) ; chaque ``push()`` écrit la
    frame reçue dans le tampon GPU à l'indice courant et propage exactement une frame.
    Les frames sont aussi archivées en JPEG dans ``/tmp/sessions/<id>/`` (f00000.jpg…).
    """

    def __init__(self, predictor, session_id: str, prompts: list, image, capacity: int,
                 debug: bool = False):
        self.predictor = predictor
        self.model = predictor.model
        self.session_id = session_id
        self.prompts = prompts
        self.first_frame = image
        self.capacity = int(capacity)
        self.batch = max(1, int(BATCH_DEFAULT)) if int(BATCH_DEFAULT) > 0 else max(1, self.capacity - 1)
        self.debug = bool(debug)
        self.width, self.height = image.size
        self.next_index = 1
        self.subsessions: list[dict] = []
        self.directory = Path(SESSIONS_ROOT) / session_id
        self.directory.mkdir(parents=True, exist_ok=True)
        self._archive(0, image)

    def _archive(self, index: int, payload) -> None:
        raw = _jpeg_bytes_from_b64(payload) if isinstance(payload, str) else b""
        if not raw:
            return
        try:
            (self.directory / f"f{index:05d}.jpg").write_bytes(raw)
        except OSError:
            pass

    def _state(self, tracker_id: str):
        return self.predictor._get_session(tracker_id)["state"]

    def start(self) -> list:
        """Ouvre une sous-session par concept, pose le concept modèle (FR→EN, label brut gardé) sur la frame 0, rend les objets."""
        objects: list[dict] = []
        for step, prompt in enumerate(self.prompts):
            tracker_id = self.predictor.handle_request(
                {"type": "start_session", "resource_path": [self.first_frame, self.capacity]}
            )["session_id"]
            outputs = self.predictor.handle_request({
                "type": "add_prompt", "session_id": tracker_id, "frame_index": 0,
                "text": model_concept(prompt), "output_prob_thresh": OUTPUT_PROB_THRESH,
            })["outputs"]
            self.subsessions.append({"prompt": prompt, "tracker_id": tracker_id,
                                     "offset": step * MAX_TRACKED_OBJECTS,
                                     "stream": None, "stream_start": 0, "stream_next": 1})
            objects += objects_from_output(outputs, prompt, step * MAX_TRACKED_OBJECTS,
                                           (self.width, self.height))
        return objects

    def push(self, image, payload: str = "") -> dict:
        """Une frame du flux : écriture du tampon + propagation d'une seule frame."""
        import torch

        if self.next_index >= self.capacity:
            raise CapacityFull(f"session capacity reached ({self.capacity} frames) — reopen the session")
        index = self.next_index
        if image.size != (self.width, self.height):
            image = image.resize((self.width, self.height))
        self._archive(index, payload)
        objects: list[dict] = []
        marks = {"normalize": 0, "propagate": 0, "masks": 0}
        # Le tampon a été créé sous inference_mode (init_state) : l'écriture in place doit
        # l'être aussi, sinon torch refuse (« Inplace update to inference tensor »).
        with torch.inference_mode():
            started = time.perf_counter()
            frame = normalize_frame(self.model, image)
            marks["normalize"] = round((time.perf_counter() - started) * 1000)
            for sub in self.subsessions:
                state = self._state(sub["tracker_id"])
                state["input_batch"].img_batch.tensors[index].copy_(frame[0])
                started = time.perf_counter()
                outputs = self._propagate_one(sub, index)
                marks["propagate"] += round((time.perf_counter() - started) * 1000)
                started = time.perf_counter()
                objects += objects_from_output(outputs, sub["prompt"], sub["offset"], (self.width, self.height))
                marks["masks"] += round((time.perf_counter() - started) * 1000)
        self.next_index = index + 1
        return {"session_id": self.session_id, "frame": index, "width": self.width,
                "height": self.height, "objects": objects, "ms": marks}

    def _open_stream(self, sub: dict, index: int) -> None:
        """Ouvre un générateur de propagation à `index`, de `self.batch` frames.

        `handle_stream_request` ne pose pas d'autocast : le predictor l'entre une fois dans
        son `__init__`, donc uniquement dans le thread qui l'a construit. Un `asyncio.to_thread`
        arrive dans un autre thread (contexte local) — on le rétablit ici, sinon le détecteur
        mélange bf16 (poids) et fp32 (activations) et meurt.
        """
        import torch

        state = self._state(sub["tracker_id"])
        # Le predictor multiplex choisit son type de propagation d'après `action_history`
        # (pensé pour une UI) : à la 3e propagation il passe en « fetch » et relit
        # cached_frame_outputs — vidé par l'éviction ci-dessous, d'où un KeyError. En direct
        # chaque requête AJOUTE une frame : l'historique est vidé avant l'appel pour rester
        # en « propagation_full », seul mode qui calcule réellement la frame demandée.
        state["action_history"] = []
        request = {
            "type": "propagate_in_video", "session_id": sub["tracker_id"],
            "propagation_direction": "forward", "start_frame_index": index,
            "max_frame_num_to_track": self.batch, "output_prob_thresh": OUTPUT_PROB_THRESH,
            # Sans ça le paquet garde `cached_frame_outputs[frame]` (masques pleine résolution
            # de toutes les frames déjà calculées) : ~1 Go retenu par frame, OOM vers la frame
            # 11 sur 40 Go et latence qui enfle (300 -> 850 ms) avant de tomber.
            "evict_cached_frame_outputs": True,
        }
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16):
            sub["stream"] = self.predictor.handle_stream_request(request)
        sub["stream_start"] = index
        sub["stream_next"] = index

    @staticmethod
    def _close_stream(sub: dict) -> None:
        """Libère le générateur courant (rien si aucun)."""
        stream, sub["stream"] = None, None
        if stream is not None:
            try:
                stream.close()
            except Exception:  # noqa: BLE001 — la fermeture ne doit jamais casser le 200
                pass

    def _propagate_one(self, sub: dict, index: int):
        """Une frame du flux : tire l'élément `index` du générateur courant.

        Le générateur reste **vivant** d'une requête à l'autre (c'est le batching prévu par
        le paquet, `generator_state` est conservé dans `inference_state`) : abandonner un
        générateur à chaque frame retenait ses activations et coûtait une ré-initialisation.
        La frame n'est lue dans le tampon qu'au moment où elle est calculée, donc écrire la
        fente `index` avant de tirer l'élément suffit — pas de frame de padding calculée.
        """
        import torch

        if sub["stream"] is None or sub["stream_next"] != index:
            self._close_stream(sub)
            self._open_stream(sub, index)
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16):
            item = next(sub["stream"])
        if item.get("frame_index") != index:
            raise RuntimeError(f"propagate_in_video answered frame {item.get('frame_index')}, "
                               f"expected {index}")
        sub["stream_next"] = index + 1
        if index - sub["stream_start"] + 1 >= self.batch:
            self._close_stream(sub)  # batch consommé : le prochain /frame en rouvrira un
        return item["outputs"]

    def memory_report(self) -> dict:
        """Où part la VRAM : tailles des caches vivants de la session (diagnostic)."""
        import torch

        report = {"vram_allocated_mb": _mb(torch.cuda.memory_allocated()) if torch.cuda.is_available() else None,
                  "vram_reserved_mb": _mb(torch.cuda.memory_reserved()) if torch.cuda.is_available() else None,
                  "frames": self.next_index}
        for sub in self.subsessions:
            state = self._state(sub["tracker_id"])
            generator_state = state.get("generator_state", {})
            report[sub["prompt"]] = {
                "cached_frame_outputs": {"n": len(state.get("cached_frame_outputs", {})),
                                         "mb": _mb(_tensor_bytes(state.get("cached_frame_outputs", {})))},
                "feature_cache": _mb(_tensor_bytes(state.get("feature_cache", {}))),
                "generator_state": {
                    "yield_list": len(generator_state.get("postprocess_yield_list", [])),
                    "hotstart": len(generator_state.get("hotstart_buffer", [])),
                    "mb": _mb(_tensor_bytes(generator_state))},
                "tracker_metadata_mb": _mb(_tensor_bytes(state.get("tracker_metadata", {}))),
                "input_batch_mb": _mb(_tensor_bytes(state.get("input_batch", {}))),
            }
        return report

    def close(self) -> None:
        """Ferme les sous-sessions, leurs générateurs, et supprime l'archive de frames."""
        for sub in self.subsessions:
            self._close_stream(sub)
            try:
                self.predictor.handle_request({"type": "close_session", "session_id": sub["tracker_id"]})
            except Exception:  # noqa: BLE001 — la fermeture ne doit jamais bloquer le 200
                pass
        self.subsessions = []
        shutil.rmtree(self.directory, ignore_errors=True)
