"""Graphe CUDA de la **passe DETR** (têtes de détection), mise en forme fixe.

L'encodeur vision est déjà graphé (`fast_core.graph_vision`) : après lui, le poste dominant
de `/live` est `detr_ms` — 53-58 ms mesurés (relevé antérieur, hors de cet arbre) pour ~1,5 ms de vrai calcul GPU.
C'est du lancement de kernels : un graphe CUDA le paie une fois.

Un forward de `Sam3Model` prend deux structures imbriquées (embeddings vision + tokens).
Ce module les clone en **tenseurs statiques**, capture le graphe sur ces buffers, puis à
chaque frame recopie dedans et rejoue. Trois garde-fous, dans l'ordre :

* **forme inconnue = eager** : la première passe d'une forme (n concepts, longueur de
  tokens) s'exécute normalement et sert à la capture ; au-delà d'un budget de formes,
  on ne capture plus (la VRAM n'est pas extensible) ;
* **vérification obligatoire** : après capture, graphe et eager sont comparés sur la même
  entrée ; au moindre écart la forme est marquée « sans graphe ». Une sortie fausse de 2
  % serait invisible à l'œil et fausserait les distances ;
* **échec de capture silencieux** : le paquet peut contenir un contrôle Python qui dépend
  de la valeur des tenseurs ; dans ce cas on laisse tomber pour cette forme et on continue
  en eager, le service n'est jamais cassé.

Le module s'importe **sans torch** : torch n'est touché qu'une fois sur le conteneur GPU.

Contournements capture (`patch_detr_capture()`, au chargement quand le levier est
actif), mathématiquement identiques, pinnés à `transformers==5.20.2` :

- `Sam3DetrEncoder._prepare_multilevel_features` fait `torch.tensor(liste_python,
  device="cuda")` à chaque forward, copie CPU→CUDA interdite en capture → tenseur
  mémoïsé par formes ;
- `Sam3DetrDecoder._get_coords` alloue `torch.arange` à chaque forward (son
  `lru_cache` keye des tenseurs 0-d par identité, donc miss systématique) →
  grilles mémoïsées par stockage parent **et** dimensions (`_coords_key` :
  le `data_ptr` seul resservait une grille périmée — mort prod du 29/10),
  dimensions résolues via la table latérale `_spatial_dims` — zéro sync,
  car `int()` pendant la capture = capture refusée (8/8 sur 3060 Ti, 01/11).
"""

from types import SimpleNamespace

# Formes capturées au maximum : une par nombre de concepts, une par longueur de tokens.
MAX_SHAPES = 3
# Écart maximal accepté entre la sortie eager et la sortie graphée (bf16 : marge large).
TOLERANCE = 2e-2
# `spatial_shapes` mémoïsés (un par forme vision — même budget que les graphes, +1).
_SPATIAL_MAX = MAX_SHAPES + 1
_spatial_cache: dict = {}
# Grilles RPB mémoïsées par stockage parent (même budget).
_COORDS_MAX = MAX_SHAPES + 1
_coords_cache: dict = {}
# Dimensions spatiales par stockage, `(ptr, device)` → `((h, w), ...)` par niveau.
# Remplie par `_cached_shapes` (H/W = int Python issus de `.shape`, zéro sync),
# lue par `_cached_coords` (zéro sync). L'allocateur réutilisant les stockages,
# l'entrée d'un ptr est écrasée à chaque nouvel alloc : une grille périmée
# (anciennes H/W) n'est plus jamais adressable. Bornée : une entrée perdue
# vaut juste un repli eager, jamais une grille fausse.
_SPATIAL_DIMS_MAX = 16
_spatial_dims: dict = {}


def enabled() -> bool:
    """Levier explicite : `FAST_DETR_GRAPH=1` au déploiement, jamais par défaut."""
    import os

    return os.environ.get("FAST_DETR_GRAPH") == "1"


def _torch():
    import torch

    return torch


def _clone(value, memo):
    """Copie récursivement tenseurs/tuples/dicts/namespaces ; les non-tenseurs passent tels quels."""
    torch = _torch()
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if torch.is_tensor(value):
        key = id(value)
        if key not in memo:
            memo[key] = value.clone()
        return memo[key]
    if isinstance(value, tuple):
        return tuple(_clone(v, memo) for v in value)
    if isinstance(value, list):
        return [_clone(v, memo) for v in value]
    if isinstance(value, dict):
        cloned = {k: _clone(v, memo) for k, v in value.items()}
        # Sortie vision brute (un concept) : un mapping typé (ex. sortie transformers,
        # accès attribut) — le reconstruire au même type, sinon le forward plante
        # (`'dict' object has no attribute 'fpn_hidden_states'`, vu en prod).
        return type(value)(cloned) if type(value) is not dict else cloned
    if isinstance(value, SimpleNamespace):
        return SimpleNamespace(**{k: _clone(v, memo) for k, v in vars(value).items()})
    return value


def _copy_into(target, source) -> bool:
    """Recopie `source` dans les buffers statiques `target` ; `False` si les formes divergent."""
    torch = _torch()
    if target is None or source is None:
        return target is source
    if torch.is_tensor(target):
        if tuple(target.shape) != tuple(source.shape) or target.dtype != source.dtype:
            return False
        target.copy_(source)
        return True
    if isinstance(target, (tuple, list)) and isinstance(source, (tuple, list)):
        return len(target) == len(source) and all(
            _copy_into(t, s) for t, s in zip(target, source))
    if isinstance(target, dict) and isinstance(source, dict):
        return set(target) == set(source) and all(
            _copy_into(target[k], source[k]) for k in target)
    if isinstance(target, SimpleNamespace) and isinstance(source, SimpleNamespace):
        return vars(target).keys() == vars(source).keys() and all(
            _copy_into(getattr(target, k), getattr(source, k)) for k in vars(target))
    return target == source


def _tensors(value, out):
    """Aplatit une structure en liste de tenseurs (pour la comparaison eager/graphé)."""
    torch = _torch()
    if value is None:
        return out
    if torch.is_tensor(value):
        out.append(value)
        return out
    if isinstance(value, (tuple, list)):
        for item in value:
            _tensors(item, out)
    elif isinstance(value, dict):
        for item in value.values():
            _tensors(item, out)
    elif isinstance(value, SimpleNamespace):
        for item in vars(value).values():
            _tensors(item, out)
    elif hasattr(value, "items"):
        for _, item in value.items():
            _tensors(item, out)
    return out


def _max_diff(left, right) -> float:
    """Pire écart absolu entre deux sorties ; `inf` si elles ne sont pas comparables."""
    torch = _torch()
    a, b = _tensors(left, []), _tensors(right, [])
    if len(a) != len(b):
        return float("inf")
    worst = 0.0
    for x, y in zip(a, b):
        if x.shape != y.shape:
            return float("inf")
        if x.is_floating_point():
            worst = max(worst, float((x.float() - y.float()).abs().max()))
        elif not torch.equal(x, y):
            return float("inf")
    return worst


class DetrGraphs:
    """Cache de graphes DETR par forme ; eager par défaut, graphe seulement si vérifié."""

    def __init__(self, budget: int = MAX_SHAPES, tolerance: float = TOLERANCE) -> None:
        self.budget = budget
        self.tolerance = tolerance
        self._entries = {}
        self._warm = set()
        self._failed = set()
        self.state = "idle"

    def __call__(self, forward, embeds, inputs):
        """Passe DETR : replay si la forme est capturée et vérifiée, sinon eager."""
        key = self._shape(embeds, inputs)
        entry = self._entries.get(key)
        if entry is not None:
            if _copy_into(entry["static_embeds"], embeds) and _copy_into(entry["static_inputs"], inputs):
                entry["graph"].replay()
                return entry["out"]
            self._entries.pop(key, None)
            self.state = "bypassed:shape"
        out = forward(embeds, inputs)
        self._capture(forward, embeds, inputs, key, out)
        return out

    def _capture(self, forward, embeds, inputs, key, eager_out) -> None:
        """Capture après une première passe eager, puis vérifie avant d'accepter le graphe."""
        torch = _torch()
        if key in self._failed or not torch.cuda.is_available():
            return
        if len(self._entries) >= self.budget:
            return
        if key not in self._warm:
            self._warm.add(key)
            return
        try:
            memo = {}
            static_embeds = _clone(embeds, memo)
            static_inputs = _clone(inputs, memo)
            side = torch.cuda.Stream()
            side.wait_stream(torch.cuda.current_stream())
            with torch.cuda.stream(side), torch.inference_mode():
                forward(static_embeds, static_inputs)
            torch.cuda.current_stream().wait_stream(side)
            graph = torch.cuda.CUDAGraph()
            with torch.inference_mode(), torch.cuda.graph(graph):
                static_out = forward(static_embeds, static_inputs)
            graph.replay()
            if _max_diff(static_out, eager_out) > self.tolerance:
                self.state = "rejected:diff"
                return
            self._entries[key] = {"graph": graph, "out": static_out,
                                  "static_embeds": static_embeds, "static_inputs": static_inputs}
            self.state = f"ok:{len(self._entries)}"
        except Exception as exc:  # noqa: BLE01 — le service doit continuer en eager
            # Cette forme seulement : un échec (contrôle Python, forme exotique) ne doit
            # pas interdire les captures des autres formes (budget préservé).
            import traceback

            print(f"[detr_graph] capture {key} impossible, eager conservé :\n"
                  f"{traceback.format_exc()}", flush=True)
            self._failed.add(key)
            self.state = f"failed:{type(exc).__name__}:{str(exc)[:90]}"

    @staticmethod
    def _shape(embeds, inputs):
        """Signature de forme : tout ce qui doit être stable pour un graphe donné.

        Les scalaires (flag, `None`) font partie de la clé : un contrôle Python
        qui en dépend rejouerait sinon un graphe capturé sur un autre chemin.
        Au pire (scalaire qui varie à chaque frame), la forme ne se capture
        jamais et le DETR reste en eager — lent, jamais faux.
        """
        torch = _torch()

        def part(value):
            if torch.is_tensor(value):
                return tuple(value.shape)
            if isinstance(value, (tuple, list)):
                return tuple(part(v) for v in value)
            if isinstance(value, dict):
                return tuple((k, part(v)) for k, v in sorted(value.items()))
            if isinstance(value, SimpleNamespace):
                return tuple((k, part(v)) for k, v in sorted(vars(value).items()))
            if value is None or isinstance(value, (bool, int, float, str)):
                return value
            return type(value).__name__

        return (str(part(embeds)), str(part(inputs)))

    def status(self) -> dict:
        """État lisible depuis /health et le banc : formes capturées, politique en vigueur."""
        return {"detr_graph": self.state, "shapes": len(self._entries), "budget": self.budget}


def graph_holder(ctx):
    """Le cache de la session, ou `None` si le levier est éteint (alors eager pur)."""
    return ctx.get("detr_graphs")


def _coords_key(storage_ptr: int, height: int, width: int, dtype, device) -> tuple:
    """Clé des grilles RPB : stockage parent **et** dimensions résolues sans sync.

    Le `data_ptr` seul ne suffit pas — l'allocateur réutilise les stockages
    (voir `_cached_coords`). Testée sans torch (`test_detr_coords_key`).
    """
    return (storage_ptr, height, width, str(dtype), str(device))


def _record_spatial_dims(storage_ptr: int, device, key) -> None:
    """Mémorise les H/W Python d'un tenseur `spatial_shapes` (zéro sync).

    `key` : tuple `((h, w), ...)` par niveau, int Python issus de `.shape`.
    Réinsertion en fin : l'éviction FIFO n'éjecte jamais l'entrée du forward
    courant, relue par `_cached_coords` juste après. Testée sans torch
    (`test_detr_coords_no_sync`).
    """
    skey = (storage_ptr, str(device))
    _spatial_dims.pop(skey, None)
    _spatial_dims[skey] = tuple(key)
    if len(_spatial_dims) > _SPATIAL_DIMS_MAX:
        _spatial_dims.pop(next(iter(_spatial_dims)))


def _lookup_coords_dims(storage_ptr: int, data_ptr: int, elem_size: int, device):
    """`(H, W)` Python d'une vue 0-d de `spatial_shapes`, `None` si inconnu.

    Pure arithmétique sur métadonnées (zéro sync) : le niveau se déduit de
    l'offset de la vue dans le stockage (`spatial_shapes` = `[niveaux, 2]`).
    `None` = stockage inconnu (tenseur hors `_cached_shapes`) ou offset hors
    table → l'appelant bascule sur le repli eager. Testée sans torch
    (`test_detr_coords_no_sync`).
    """
    dims = _spatial_dims.get((storage_ptr, str(device)))
    if dims is None or elem_size <= 0:
        return None
    level = ((data_ptr - storage_ptr) // elem_size) // 2
    if level < 0 or level >= len(dims):
        return None
    return dims[level]


def _coords_hw_eager(height, width) -> tuple:
    """Lecture sync des H/W : eager UNIQUEMENT, jamais pendant une capture."""
    return int(height), int(width)


def _patched_shapes(self, vision_features, vision_pos_embeds):
    """Remplace `_prepare_multilevel_features` : cache + table ptr→H/W."""
    torch = _torch()
    key = tuple((f.shape[-2], f.shape[-1]) for f in vision_features)
    feats = torch.cat([f.flatten(2).transpose(1, 2) for f in vision_features], dim=1)
    pos = torch.cat([p.flatten(2).transpose(1, 2) for p in vision_pos_embeds], dim=1)
    entry = _spatial_cache.get((key, str(feats.device)))
    if entry is None:
        # Copie CPU→CUDA interdite en capture : le warmup eager précède
        # toujours la capture, donc un miss ici est anormal → erreur
        # propre (eager conservé), jamais d'erreur CUDA à effet différé.
        if torch.cuda.is_available() and torch.cuda.is_current_stream_capturing():
            raise RuntimeError("detr_graph: spatial_shapes manquant en capture")
        entry = torch.tensor(list(key), dtype=torch.long, device=feats.device)
        if len(_spatial_cache) >= _SPATIAL_MAX:
            _spatial_cache.pop(next(iter(_spatial_cache)))
        _spatial_cache[(key, str(feats.device))] = entry
    # Table latérale ptr→H/W : `key` = int Python issus de `.shape`
    # (zéro sync), relus par `_patched_coords` pendant la capture.
    _record_spatial_dims(entry.untyped_storage().data_ptr(), entry.device, key)
    return feats, pos, entry


def _patched_coords(self, height, width, dtype, device):
    """Remplace `_get_coords` : clé ptr+H/W SANS sync (table latérale).

    La clé `data_ptr` seul resservait une grille périmée à la réutilisation
    du stockage (mort prod du 29/10) ; lire les valeurs ici synchroniserait
    pendant la capture CUDA → capture refusée, eager déguisé (8/8, 01/11).
    Stockage inconnu (tenseur hors `_patched_shapes`) : repli sync en
    eager (correct), erreur propre en capture (eager conservé).
    """
    torch = _torch()
    sptr = height.untyped_storage().data_ptr()
    hw = None
    if width.untyped_storage().data_ptr() == sptr:
        hw = _lookup_coords_dims(sptr, height.data_ptr(),
                                 height.element_size(), height.device)
    if hw is None:
        if torch.cuda.is_available() and torch.cuda.is_current_stream_capturing():
            raise RuntimeError("detr_graph: stockage spatial inconnu en capture")
        hlo, wlo = _coords_hw_eager(height, width)
    else:
        hlo, wlo = hw
    key = _coords_key(sptr, hlo, wlo, dtype, device)
    hit = _coords_cache.get(key)
    if hit is not None:
        return hit
    coords_h = torch.arange(0, hlo, device=device, dtype=dtype) / hlo
    coords_w = torch.arange(0, wlo, device=device, dtype=dtype) / wlo
    if len(_coords_cache) >= _COORDS_MAX:
        _coords_cache.pop(next(iter(_coords_cache)))
    _coords_cache[key] = (coords_h, coords_w)
    return coords_h, coords_w


def patch_detr_capture() -> str:
    """Contournements capture DETR (voir module) : `spatial_shapes` + grilles RPB.

    Idempotent. Pinné à `transformers==5.20.2` (requirements) : à re-vérifier à tout
    upgrade — et la vérification graphe-vs-eager rejetterait toute divergence.
    """
    from transformers.models.sam3.modeling_sam3 import Sam3DetrDecoder, Sam3DetrEncoder

    if getattr(Sam3DetrEncoder._prepare_multilevel_features, "_spatial_patched", False):
        return "already"
    _patched_shapes._spatial_patched = True
    Sam3DetrEncoder._prepare_multilevel_features = _patched_shapes
    _patched_coords._coords_patched = True
    Sam3DetrDecoder._get_coords = _patched_coords
    return "ok"
