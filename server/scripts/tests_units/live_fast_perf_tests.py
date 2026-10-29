"""Perf live_fast déployé : cache tokenize, clone graphe DETR (sans torch ni GPU).

Extrait de ``live_fast_tests`` (plafond 500 lignes) : mêmes conventions (stubs locaux,
``test_units`` importé dans les fonctions, ``sys.path`` restauré).
"""

from __future__ import annotations




class _TextTensor:
    """Tensor texte factice : seul `.to(device)` compte (enregistre la cible)."""

    def __init__(self, device) -> None:
        self.device = device

    def to(self, device):
        return _TextTensor(device)


class _CountingTextProcessor:
    """Préprocesseur texte factice qui compte ses appels."""

    def __init__(self) -> None:
        self.calls = 0

    def __call__(self, text=None, return_tensors=None):
        self.calls += 1
        return {"input_ids": _TextTensor("cpu")}


def test_tokenize_cache(_timing) -> None:
    """`token_cache` (live_fast déployé) : un appel par (concepts, device), FIFO à 8."""
    import sys

    from test_units import BACKEND_DIR, check

    live_fast = str(BACKEND_DIR / "live_fast")
    sys.path.insert(0, live_fast)
    try:
        import token_cache

        token_cache.cache_clear()
        proc = _CountingTextProcessor()
        first = token_cache.tokenize_cached(proc, ["person"], "cuda")
        second = token_cache.tokenize_cached(proc, ["person"], "cuda")
        check("tokenize : 2e appel identique sans re-tokeniser",
              proc.calls == 1 and second is first, f"calls={proc.calls}")
        check("tokenize : tenseurs montés sur le device demandé",
              first["input_ids"].device == "cuda", str(first["input_ids"].device))
        token_cache.tokenize_cached(proc, ["car"], "cuda")
        check("tokenize : nouveaux concepts re-tokenisent",
              proc.calls == 2, f"calls={proc.calls}")
        token_cache.tokenize_cached(proc, ["person"], "cpu")
        check("tokenize : device distinct re-tokenise", proc.calls == 3, f"calls={proc.calls}")
        for index in range(10):
            token_cache.tokenize_cached(proc, [f"concept{index}"], "cuda")
        check("tokenize : FIFO bornée à 8", len(token_cache._token_cache) <= 8,
              str(len(token_cache._token_cache)))
        token_cache.tokenize_cached(proc, ["person"], "cuda")
        check("tokenize : entrée évincée re-tokenise", proc.calls == 14, f"calls={proc.calls}")
        token_cache.cache_clear()
    finally:
        sys.path.remove(live_fast)


class _CloneTensor:
    """Tensor factice clonable (miroir minimal pour `_clone`)."""

    def clone(self):
        return _CloneTensor()


class _ModelOutput(dict):
    """Mapping typé à accès attribut (miroir sortie transformers, ex. `fpn_hidden_states`)."""

    def __getattr__(self, key):
        try:
            return self[key]
        except KeyError:
            raise AttributeError(key)


def test_detr_clone(_timing) -> None:
    """`detr_graph._clone` préserve les mappings typés (plantage prod n=1)."""
    import sys
    import types

    from test_units import BACKEND_DIR, check

    live_fast = str(BACKEND_DIR / "live_fast")
    saved_torch = sys.modules.get("torch")
    fake_torch = types.ModuleType("torch")
    fake_torch.is_tensor = lambda value: isinstance(value, _CloneTensor)
    sys.path.insert(0, live_fast)
    sys.modules["torch"] = fake_torch
    try:
        import detr_graph

        out = _ModelOutput(fpn_hidden_states=(_CloneTensor(),))
        cloned = detr_graph._clone(out, {})
        check("clone : mapping typé préservé", type(cloned) is _ModelOutput, str(type(cloned)))
        check("clone : accès attribut conservé",
              cloned.fpn_hidden_states is not None and len(cloned.fpn_hidden_states) == 1)
        check("clone : tenseurs clonés, pas partagés",
              cloned["fpn_hidden_states"][0] is not out["fpn_hidden_states"][0])
        plain = detr_graph._clone({"tenseur": _CloneTensor()}, {})
        check("clone : dict simple inchangé", type(plain) is dict, str(type(plain)))
        graphs = detr_graph.DetrGraphs()
        graphs._failed.add("forme-exotique")
        check("graphe : budget intact malgré un échec ailleurs",
              graphs.budget == detr_graph.MAX_SHAPES, str(graphs.budget))
    finally:
        if saved_torch is None:
            sys.modules.pop("torch", None)
        else:
            sys.modules["torch"] = saved_torch
        sys.path.remove(live_fast)
