"""Noyau « rapide » de /live : un levier de latence = une variable d'environnement.

Copie de travail T5 (objectif < 200 ms) — le contrat HTTP de ``live_app.py`` est conservé
(``POST /live``, ``GET /health``) et chaque levier est isolé pour être mesuré séparément :

* ``FAST_BATCH_PROMPTS=1`` — un seul forward DETR pour N concepts (batch de textes,
  embeddings vision répliqués sur la dim batch) au lieu d'une passe par concept ;
* ``FAST_EDGE=448``        — surcharge de la taille d'entrée du préprocesseur ;
* ``FAST_COMPILE=1``       — ``torch.compile`` de l'encodeur vision + warm-up explicite ;
* ``FAST_CUDA_GRAPH=1``    — capture CUDA graph de l'encodeur vision (exclusive de compile) ;
* ``FAST_DTYPE=bf16|fp16|fp32`` ;
* ``FAST_TIMING=1``        — détail des temps par étage dans la réponse (outillage de banc) ;
* ``FAST_DEPTH_EVERY=5``   — carte depth 1 frame WS sur N (médianes répétées entre deux) ;
* ``FAST_PREPROCESS=fast`` — ``pixel_values`` fusionnés torch (resize + normalisation en
  une passe, sur GPU si dispo) au lieu de l'appel HF ; ``hf`` (défaut) garde le chemin
  historique ;
* ``FAST_SNAPSHOT=1``      — snapshot mémoire Modal (poids figés, réveil 2-7 s).

Les poids et leurs chargements vivent dans ``weights.py`` (ré-exportés ici).

Deux champs gouvernent la résolution d'entrée (vérifié dans transformers 5.20.2 installée,
``models/sam3/{image_processing,processing}_sam3.py``) : ``image_processor.size`` fait le
resize réel, ``processor.target_size`` normalise les coordonnées — il n'hérite du premier
qu'à la construction, donc tout levier d'exécution redéfinit les deux. Le ViT découpe en
patchs de 14 px : la taille demandée est arrondie au multiple de 14 le plus proche.

torch / transformers / PIL ne sont importés que dans les fonctions : ce module reste
importable et testable sans stack GPU.

Budget GPU (une seule carte, un seul verrou) : ``run_frame`` ne tient le verrou
que sur l'encodeur vision (1 passe/frame) et les passes DETR — le pré-traitement
CPU et l'encodage des masques sont hors verrou et se recouvrent avec la frame
suivante. ``FAST_BATCH_PROMPTS=1`` groupe les N concepts en UN forward DETR
(``repeat_vision`` réplique les embeddings vision sur le batch) au lieu de N
passes ; ``queue_ms`` (levier ``FAST_TIMING``) publie l'attente au verrou.
"""

from __future__ import annotations

import os
from contextlib import contextmanager
from types import SimpleNamespace

# Knobs d'environnement et nom du GPU : extraits dans ``fast_env`` (garde taille
# < 500 lignes/fichier, zéro duplication). Ré-exportés ici : ``service.py``,
# ``live_app_fast.py`` et les bancs gardent ``from fast_core import clamp_int, …``.
from fast_env import clamp_int, env_bool, env_int, gpu_name
from timing import Stage, add_ms, elapsed_ms, now
# Poids + chargements ré-exportés pour service.py et les bancs (limite 500 lignes).
from token_cache import tokenize_cached
from weights import (ATTN_IMPL, DTYPE_NAME, MODEL_DIR, MODEL_ID, MODEL_SOURCE, load,
                     load_cpu, local_only, round_edge, torch_dtype)

DTYPES = ("bf16", "fp16", "fp32")


# --- leviers du conteneur (lus à l'import, surchargeables par requête) -------------
BATCH_PROMPTS = env_bool(os.environ.get("FAST_BATCH_PROMPTS"))
EDGE = env_int(os.environ.get("FAST_EDGE"))
RUNTIME_EDGE = env_bool(os.environ.get("FAST_EDGE_RUNTIME"))
COMPILE = env_bool(os.environ.get("FAST_COMPILE"))
CUDA_GRAPH = env_bool(os.environ.get("FAST_CUDA_GRAPH"))
# Graphe CUDA de la passe DETR (voir `detr_graph.py`) : le vision est déjà graphé, DETR
# est le dernier gros poste (~55 ms mesurés fin octobre pour ~1,5 ms de calcul).
DETR_GRAPH = env_bool(os.environ.get("FAST_DETR_GRAPH"))
WARMUPS = env_int(os.environ.get("FAST_COMPILE_WARMUPS"), 3)
TIMING = env_bool(os.environ.get("FAST_TIMING"))
# Throttle depth WS (voir depth.py) : 1 carte calculée sur N frames demandées
# (défaut 5 ≈ 6 Hz à 30 images/s), médianes répétées entre deux. 1 = chaque
# frame (throttle coupé) ; borne 30 ≈ 1 s à 30 Hz, sous les 1500 ms de
# fraîcheur qu'exige le client (LiveDepthMeter.SERVER_FRESH_MS). env_int +
# bornes explicites : clamp_int n'est défini qu'après ce bloc.
DEPTH_EVERY = max(1, min(30, env_int(os.environ.get("FAST_DEPTH_EVERY"), 5)))
# Pré-traitement : "hf" (appel processeur historique) ou "fast" (pixels_fast fusionné,
# même sémantique à ±1 LSB 8 bits près, repli HF si la config sort du supporté).
PREPROCESS = (os.environ.get("FAST_PREPROCESS") or "hf").strip().lower()
# Snapshot mémoire Modal (poids figés après le 1er démarrage) : défaut False — honnête
# pour Baseten (pas de snapshot) ; le déploiement Modal pose FAST_SNAPSHOT=1.
SNAPSHOT = env_bool(os.environ.get("FAST_SNAPSHOT"))


def levers() -> dict:
    """État des leviers tels que lus à l'import — étiquette /health et lignes du banc."""
    return {"batch_prompts": BATCH_PROMPTS, "edge": EDGE, "runtime_edge": RUNTIME_EDGE,
            "compile": COMPILE, "cuda_graph": CUDA_GRAPH, "detr_graph": DETR_GRAPH,
            "dtype": DTYPE_NAME, "snapshot": SNAPSHOT, "attn": ATTN_IMPL or "eager",
            "warmups": WARMUPS, "timing": TIMING, "depth_every": DEPTH_EVERY,
            "preprocess": PREPROCESS}


def _torch():
    import torch

    return torch


def vision_grid(model) -> int:
    """Taille d'entrée pour laquelle le RoPE du ViT a été construit (1008 par défaut)."""
    return int(model.config.vision_config.backbone_config.image_size)


def set_vision_grid(model, edge: int) -> str:
    """Recalcule le RoPE du ViT pour une autre taille d'entrée (grille de patchs).

    Les embeddings de position du ViT sont *tuilés* (et non interpolés) : passer de 1008
    à 448 revient à rogner la table 72x72 — le modèle reste valide mais perd de la
    précision de localisation. À n'utiliser que comme banc de latence.
    """
    import torch

    size = round_edge(int(edge))
    model.config.vision_config.backbone_config.image_size = size
    for module in model.modules():
        if hasattr(module, "precompute_positions") and hasattr(module, "position_ids"):
            module.position_ids = torch.tensor(
                module.precompute_positions(module.config, module.window_size), dtype=torch.long)
    return "ok"


def apply_edge(processor, edge) -> int | None:
    """Surcharge ``image_processor.size`` ET ``processor.target_size`` ; None = inchangé."""
    if not edge:
        return None
    size = round_edge(int(edge))
    processor.image_processor.size = {"height": size, "width": size}
    processor.target_size = size
    return size


def read_edge(processor) -> dict:
    """Résolution d'entrée réellement en vigueur (contrôle visuel du levier FAST_EDGE)."""
    return {"image_processor_size": dict(getattr(processor.image_processor, "size", {}) or {}),
            "target_size": getattr(processor, "target_size", None),
            "mask_size": dict(getattr(processor.image_processor, "mask_size", {}) or {})}


def _warmup(callable_, device: str, dtype, edge: int, repeats: int = WARMUPS) -> None:
    """Passes sur un tensor figé (1, 3, edge, edge) : la forme que le graphe va capturer."""
    torch = _torch()
    sample = torch.zeros(1, 3, edge, edge, device=device, dtype=dtype)
    with torch.inference_mode():
        for _ in range(max(1, repeats)):
            callable_(sample)


def compile_vision(model, device: str, dtype, edge: int) -> str:
    """``torch.compile`` de l'encodeur vision seul + warm-up ; repli silencieux si ça échoue."""
    if device != "cuda":
        return "skipped:no-cuda"
    original = model.vision_encoder
    try:
        model.vision_encoder = _torch().compile(original)
        _warmup(model.vision_encoder, device, dtype, edge)
        model._vision_original = original
        return "ok"
    except Exception as exc:  # noqa: BLE001 — la compilation est un bonus, jamais un blocage
        model.vision_encoder = original
        return f"failed:{type(exc).__name__}:{str(exc)[:120]}"


def graph_vision(model, device: str, dtype, edge: int) -> str:
    """Capture CUDA graph de l'encodeur vision (forme statique) ; repli silencieux.

    Les tenseurs sortis du graphe sont réécrits à chaque ``replay`` : sûr ici parce que le
    conteneur sérialise tout derrière un verrou GPU et consomme la sortie dans la foulée.
    Activé uniquement par ``FAST_CUDA_GRAPH=1`` — non testé sans GPU, donc laissé éteint.
    """
    torch = _torch()
    if device != "cuda" or not hasattr(torch.cuda, "CUDAGraph"):
        return "skipped:no-cuda"
    module = model.vision_encoder
    try:
        static_in, static_out, graph = _capture_vision_graph(module, torch, device,
                                                             dtype, edge)
        class _GraphReplay(torch.nn.Module):
            """Encodeur vision rejoué par graphe CUDA, à forme d'entrée fixe.

            Un ``nn.Module`` et pas une fonction : ``vision_encoder`` est un
            sous-module enregistré, PyTorch refuse d'y assigner une closure
            (``cannot assign ... as child module``). La forme est stable parce que
            ``runtime_edge`` est faux ; si une autre forme arrive, on repasse par le
            module d'origine au lieu de corrompre le graphe. Nichée ici : torch
            n'est importable qu'à l'intérieur (pas de stack GPU aux tests).
            """

            def __init__(self) -> None:
                super().__init__()
                self.static_in = static_in
                self.static_out = static_out
                self.shape = tuple(static_in.shape)
                self.original = module
                self._graph = graph

            def forward(self, pixel_values):
                if tuple(pixel_values.shape) != self.shape:
                    return self.original(pixel_values)
                self.static_in.copy_(pixel_values)
                self._graph.replay()
                return self.static_out

        model.vision_encoder = _GraphReplay()
        return "ok"
    except Exception as exc:  # noqa: BLE001
        model.vision_encoder = module
        return f"failed:{type(exc).__name__}:{str(exc)[:120]}"


def _capture_vision_graph(module, torch, device: str, dtype, edge: int):
    """Capture le graphe CUDA de l'encodeur (warm-up + enregistrement) ; lève sinon."""
    static_in = torch.zeros(1, 3, edge, edge, device=device, dtype=dtype)
    side = torch.cuda.Stream()
    side.wait_stream(torch.cuda.current_stream())
    with torch.cuda.stream(side):
        _warmup(module, device, dtype, edge)
    torch.cuda.current_stream().wait_stream(side)
    graph = torch.cuda.CUDAGraph()
    with torch.inference_mode(), torch.cuda.graph(graph):
        static_out = module(static_in)
    return static_in, static_out, graph
def prepare(model, processor, device, dtype, edge=EDGE, compile_flag=COMPILE,
            graph_flag=CUDA_GRAPH) -> dict:
    """Applique les leviers structurels au conteneur et dit ce qui a réellement tenu.

    La taille d'entrée effective est ``edge`` si demandé, sinon la grille du modèle
    (1008 par défaut) : compile/graph se calent sur CETTE taille, pas sur le concept.
    """
    applied = apply_edge(processor, edge)
    grid = vision_grid(model)
    effective = applied or grid
    use_compile = COMPILE if compile_flag is None else compile_flag
    use_graph = CUDA_GRAPH if graph_flag is None else graph_flag
    if use_graph:
        status = graph_vision(model, device, dtype, effective)
    elif use_compile:
        status = compile_vision(model, device, dtype, effective)
    else:
        status = "none"
    return {"edge_applied": effective, "vision_accel": status}


def use_edge(ctx, edge) -> bool:
    """Résolution effective pour CETTE frame ; False = demande ignorée.

    Une taille différente de la grille du modèle n'est acceptée qu'avec
    ``FAST_EDGE_RUNTIME=1`` (recalcul du RoPE à chaud). Sinon on garde la résolution du
    conteneur : mieux vaut une frame cohérente qu'une sortie faussée.
    """
    if not edge:
        return True
    size = round_edge(int(edge))
    if size == ctx.get("edge_applied"):
        return True
    if not ctx.get("runtime_edge"):
        return False
    set_vision_grid(ctx["model"], size)
    apply_edge(ctx["processor"], size)
    ctx["edge_applied"] = size
    original = getattr(ctx["model"], "_vision_original", None)
    if ctx.get("vision_accel", "none") != "none" and original is not None:
        ctx["model"].vision_encoder = original
        ctx["vision_accel"] = "bypassed:edge-changed"
    return True


def repeat_vision(vision, n: int):
    """Réplique les embeddings vision sur ``n`` concepts (le DETR exige le batch aligné).

    Ne garde que ce que ``Sam3Model.forward`` consomme (``fpn_hidden_states``,
    ``fpn_position_encoding``) et élimine ``hidden_states``/``attentions`` : les 32 couches
    du ViT pèsent plus lourd que le FPN et ne servent pas à la segmentation.
    """
    torch = _torch()
    if n <= 1:
        return vision

    def rep(tensor):
        if not torch.is_tensor(tensor) or tensor.shape[0] != 1:
            return tensor
        return tensor.repeat((n,) + (1,) * (tensor.dim() - 1))

    return SimpleNamespace(
        fpn_hidden_states=tuple(rep(t) for t in vision.fpn_hidden_states),
        fpn_position_encoding=tuple(rep(t) for t in vision.fpn_position_encoding),
        last_hidden_state=rep(vision.last_hidden_state),
        pooler_output=None, hidden_states=None, attentions=None)


def _detections(model, processor, device, dtype, vision, concepts, work_size, timers,
                graphs=None):
    """UN forward pour les N concepts (batch=N) -> N détections post-traitées.

    ``graphs`` (cache `DetrGraphs`) est optionnel : sans lui, eager only. La capture et la
    vérification sont dans `detr_graph.py` ; ici on ne fait que présenter la passe.
    """
    torch = _torch()
    from segmentation import MASK_THRESHOLD, SCORE_THRESHOLD

    started = now()
    inputs = tokenize_cached(processor, concepts, device)
    add_ms(timers, "tokenize_ms", elapsed_ms(started))
    embeds = repeat_vision(vision, len(concepts))

    def forward(static_embeds, static_inputs):
        with torch.inference_mode():
            return model(vision_embeds=static_embeds, **static_inputs)

    started = now()
    outputs = graphs(forward, embeds, inputs) if graphs is not None else forward(embeds, inputs)
    add_ms(timers, "detr_ms", elapsed_ms(started))
    started = now()
    found = processor.post_process_instance_segmentation(
        outputs, threshold=SCORE_THRESHOLD, mask_threshold=MASK_THRESHOLD,
        target_sizes=[(work_size[1], work_size[0])] * len(concepts))
    add_ms(timers, "post_ms", elapsed_ms(started))
    return found


def _fast_pixels(ctx, work):
    """``pixel_values`` fusionnés (levier ``FAST_PREPROCESS=fast``) ; None = repli HF."""
    if PREPROCESS != "fast":
        return None
    from imaging import try_pixels_fast

    return try_pixels_fast(work, ctx.get("processor"), ctx.get("device") or "cpu",
                           ctx.get("dtype"))


def _preprocess(ctx, image, work_edge: int):
    """Remise à l'échelle + ``pixel_values`` (HF historique, ou fusionné si levier)."""
    from imaging import resize_max_edge

    work = resize_max_edge(image, work_edge)
    fast = _fast_pixels(ctx, work)
    if fast is not None:
        return work, fast
    return work, ctx["processor"](images=work, return_tensors="pt")["pixel_values"]


def _vision(ctx, pixels, timers: dict):
    """Encodeur vision de la frame — une seule fois, sous verrou GPU (poste ``vision_ms``)."""
    import torch

    started = now()
    with torch.inference_mode():
        vision = ctx["model"].vision_encoder(pixels.to(device=ctx["device"], dtype=ctx["dtype"]))
    add_ms(timers, "vision_ms", elapsed_ms(started))
    return vision


def _collect(ctx, vision, prompts, concepts, work_size, image_size, batch: bool,
             keep: int, timers: dict) -> list[dict]:
    """Passe(s) DETR -> objets **sans masque encodé**, borés aux ``keep`` meilleurs par concept.

    ``batch=True`` (levier ``FAST_BATCH_PROMPTS``) fait les N concepts en un seul forward ;
    sinon une passe par concept. Le contenu et l'ordre de la liste finale ne changent pas
    quand ``keep`` vaut ``max_objects`` : un objet plus mal classé que ``keep`` objets de son
    propre concept ne peut pas entrer dans le top global (voir ``segmentation._top_indices``).
    """
    from segmentation import detection_entries

    if batch:
        found = _detections(ctx["model"], ctx["processor"], ctx["device"], ctx["dtype"],
                           vision, concepts, work_size, timers, ctx.get("detr_graphs"))
        pairs = list(zip(found, prompts))
    else:
        pairs = []
        for prompt, concept in zip(prompts, concepts):
            one = _detections(ctx["model"], ctx["processor"], ctx["device"], ctx["dtype"],
                             vision, [concept], work_size, timers)
            pairs.append((one[0], prompt))
    entries: list[dict] = []
    for detection, label in pairs:
        entries.extend(detection_entries(detection, label, work_size, image_size, keep))
    return entries


@contextmanager
def _locked(gpu_lock, timers: dict):
    """Le verrou GPU, en publiant l'attente dans ``queue_ms`` ; ``None`` = rien à sérialiser.

    Le verrou ne couvre plus que l'encodeur vision et les passes DETR : le pré-traitement
    (PIL + normalisation numpy) et l'encodage des masques se font **hors** du verrou, donc
    ils se recouvrent avec la frame suivante au lieu de rendre la carte muette (~16 ms de
    créneau GPU rendus par frame, rapport cpu-serveur piste 3).
    """
    if gpu_lock is None:
        yield
        return
    started = now()
    gpu_lock.acquire()
    add_ms(timers, "queue_ms", elapsed_ms(started))
    try:
        yield
    finally:
        gpu_lock.release()


def _payload(entries, image_size, max_objects, mask_encoding, timers: dict,
             mask_format=None, mask_edge: int = 0) -> dict:
    """Tri global, troncature à ``max_objects``, puis encodage des seuls objets gardés.

    La troncature passe par ``feux_guard`` : un petit feu (score faible) garde une
    place au lieu d'être évincé — seuils globaux inchangés, tri pur sinon.
    """
    from feux_guard import finalize_guarded
    from segmentation import finalize

    started = now()
    result = finalize_guarded(finalize, entries, image_size, max_objects, mask_encoding,
                              mask_format=mask_format, mask_edge=mask_edge)
    add_ms(timers, "masks_ms", elapsed_ms(started))
    return result


def _decorate(ctx, result: dict, edge, edge_ok: bool, timers: dict) -> dict:
    """Marqueurs de frame pour l'app + chronos par étage, selon le levier ``FAST_TIMING``.

    ``timings_ms`` n'est émis **que** si le levier est vrai : un client s'appuie sur
    l'absence du champ, une carte vide serait un mensonge. Les marques ``_edge_*`` restent
    internes (l'app les transforme en objet ``fast``) pour ne pas changer le contrat.
    """
    result["_edge_applied"] = ctx.get("edge_applied")
    result["_edge_ok"] = edge_ok
    if TIMING:
        result["timings_ms"] = {key: round(value, 1) for key, value in timers.items()}
    return result


def run_frame(ctx, image, prompts: list[str], work_edge: int, max_objects: int, batch: bool,
              edge=None, mask_encoding=None, gpu_lock=None, timers: dict | None = None,
              mask_format=None, mask_edge: int = 0, with_depth: bool = False) -> dict:
    """UNE frame de bout en bout : CPU hors verrou, GPU sous verrou, encodage hors verrou.

    Chemin chaud unique de ``/live`` (HTTP et WebSocket) — les deux routes appellent ici,
    il n'y a pas deux versions de la frame. ``gpu_lock=None`` garde le comportement
    historique (l'appelant sérialise déjà, comme ``_segment_sync``). ``timings`` partagé
    permet à l'appelant d'ajouter ses propres postes (``decode_ms``) à la même carte.
    """
    from depth import attach_frame_depth
    from feux_guard import routed_concept

    timers = {} if timers is None else timers
    # `use_edge` peut réécrire la grille RoPE du ViT (FAST_EDGE_RUNTIME=1) : hors verrou, ce
    # serait une course avec une 2e entrée concurrente. En prod le levier est éteint et
    # aucun client n'envoie `edge` ; les deux en même temps reste non supporté.
    edge_ok = use_edge(ctx, edge)
    image_size = image.size
    with Stage(timers, "preprocess_ms"):
        work, pixels = _preprocess(ctx, image, work_edge)
    concepts = [routed_concept(prompt) for prompt in prompts]
    with _locked(gpu_lock, timers):
        vision = _vision(ctx, pixels, timers)
        entries = _collect(ctx, vision, prompts, concepts, work.size, image_size, batch,
                           max_objects, timers)
        entries = attach_frame_depth(ctx, image, entries, with_depth, timers)
    result = _payload(entries, image_size, max_objects, mask_encoding, timers,
                      mask_format=mask_format, mask_edge=mask_edge)
    return _decorate(ctx, result, edge, edge_ok, timers)


def segment_frame(ctx, image, prompts: list[str], work_edge: int, max_objects: int,
                  batch: bool, edge=None, mask_encoding=None, mask_format=None,
                  mask_edge: int = 0) -> dict:
    """Une frame : vision UNE fois, puis un forward groupé (batch) ou N passes seules.

    Interface historique, sans verrou (l'appelant tient le sien). Équivalente à
    :func:`run_frame` avec ``gpu_lock=None`` — conservée pour ``_segment_sync`` et les bancs.
    """
    return run_frame(ctx, image, prompts, work_edge, max_objects, batch, edge=edge,
                     mask_encoding=mask_encoding, mask_format=mask_format, mask_edge=mask_edge)
