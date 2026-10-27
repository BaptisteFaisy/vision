"""Graphe CUDA de la **passe DETR** (têtes de détection), mise en forme fixe.

L'encodeur vision est déjà graphé (`fast_core.graph_vision`) : après lui, le poste dominant
de `/live` est `detr_ms` — 53-58 ms mesurés le 25 octobre 2026 pour ~1,5 ms de vrai calcul GPU.
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
"""

from types import SimpleNamespace

# Formes capturées au maximum : une par nombre de concepts, une par longueur de tokens.
MAX_SHAPES = 3
# Écart maximal accepté entre la sortie eager et la sortie graphée (bf16 : marge large).
TOLERANCE = 2e-2


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
        return {k: _clone(v, memo) for k, v in value.items()}
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
        if not torch.cuda.is_available() or len(self._entries) >= self.budget:
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
            self.budget = len(self._entries)
            self.state = f"failed:{type(exc).__name__}:{str(exc)[:90]}"

    @staticmethod
    def _shape(embeds, inputs):
        """Signature de forme : tout ce qui doit être stable pour un graphe donné."""
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
            return None

        return (str(part(embeds)), str(part(inputs)))

    def status(self) -> dict:
        """État lisible depuis /health et le banc : formes capturées, politique en vigueur."""
        return {"detr_graph": self.state, "shapes": len(self._entries), "budget": self.budget}


def graph_holder(ctx):
    """Le cache de la session, ou `None` si le levier est éteint (alors eager pur)."""
    return ctx.get("detr_graphs")
