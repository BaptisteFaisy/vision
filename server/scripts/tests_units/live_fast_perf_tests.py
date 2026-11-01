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


class _ShapeTensor:
    """Tensor factice avec forme seule (miroir minimal pour `DetrGraphs._shape`)."""

    def __init__(self, shape) -> None:
        self.shape = shape


def _detr_module():
    """`detr_graph` déployé avec un faux torch (que `is_tensor`), sys.path géré."""
    import sys
    import types

    from test_units import BACKEND_DIR

    live_fast = str(BACKEND_DIR / "live_fast")
    saved_torch = sys.modules.get("torch")
    fake_torch = types.ModuleType("torch")
    fake_torch.is_tensor = lambda value: isinstance(value, (_CloneTensor, _ShapeTensor))
    sys.path.insert(0, live_fast)
    sys.modules["torch"] = fake_torch
    import detr_graph

    return detr_graph, live_fast, saved_torch


def _release_detr(live_fast, saved_torch) -> None:
    import sys

    if saved_torch is None:
        sys.modules.pop("torch", None)
    else:
        sys.modules["torch"] = saved_torch
    sys.path.remove(live_fast)


def test_detr_shape_scalars(_timing) -> None:
    """`DetrGraphs._shape` distingue les scalaires : pas de collision de graphe."""
    from test_units import check

    detr_graph, live_fast, saved_torch = _detr_module()
    try:
        tenseur = _ShapeTensor((2, 3))
        vrai = detr_graph.DetrGraphs._shape((tenseur,), {"flag": True})
        faux = detr_graph.DetrGraphs._shape((tenseur,), {"flag": False})
        check("shape : scalaire distinct → clé distincte", vrai != faux, f"{vrai} vs {faux}")
        check("shape : entrées égales → clé égale",
              detr_graph.DetrGraphs._shape((tenseur,), {"flag": True}) == vrai)
        check("shape : None vs scalaire → clé distincte",
              detr_graph.DetrGraphs._shape((tenseur,), {"flag": None}) != vrai)
    finally:
        _release_detr(live_fast, saved_torch)


def test_detr_coords_key(_timing) -> None:
    """Clé coords : même stockage + H/W distincts → clés distinctes.

    Non-régression de la mort prod du 29/10 (replay à 0,3 ms puis plus rien,
    sans traceback) : la clé `data_ptr` seul resservait une grille périmée
    quand l'allocateur réutilisait le stockage pour d'autres H/W.
    """
    from test_units import check

    detr_graph, live_fast, saved_torch = _detr_module()
    try:
        base = detr_graph._coords_key(12345, 10, 20, "torch.float32", "cuda:0")
        check("coords : H distinct → clé distincte",
              detr_graph._coords_key(12345, 11, 20, "torch.float32", "cuda:0") != base)
        check("coords : W distinct → clé distincte",
              detr_graph._coords_key(12345, 10, 21, "torch.float32", "cuda:0") != base)
        check("coords : mêmes entrées → clé égale",
              detr_graph._coords_key(12345, 10, 20, "torch.float32", "cuda:0") == base)
    finally:
        _release_detr(live_fast, saved_torch)


def test_detr_coords_no_sync(_timing) -> None:
    """Clé coords sans sync : ptr réutilisé à H/W différents → grilles fraîches."""
    from test_units import check

    detr_graph, live_fast, saved_torch = _detr_module()
    try:
        saved_dims = dict(detr_graph._spatial_dims)
        detr_graph._spatial_dims.clear()
        try:
            ptr = 0xC0FFEE
            detr_graph._record_spatial_dims(ptr, "cuda:0", ((24, 32),))
            check("coords : ptr connu → H/W résolus sans sync",
                  detr_graph._lookup_coords_dims(ptr, ptr, 8, "cuda:0") == (24, 32))
            # Réutilisation du stockage à H/W différents : la table suit, les
            # grilles périmées (anciennes H/W) ne sont plus adressables.
            detr_graph._record_spatial_dims(ptr, "cuda:0", ((12, 16),))
            check("coords : ptr réutilisé → nouveaux H/W (pas de grille périmée)",
                  detr_graph._lookup_coords_dims(ptr, ptr, 8, "cuda:0") == (12, 16))
            check("coords : ptr réutilisé → clés distinctes",
                  detr_graph._coords_key(ptr, 24, 32, "torch.float32", "cuda:0")
                  != detr_graph._coords_key(ptr, 12, 16, "torch.float32", "cuda:0"))
            check("coords : ptr inconnu → None (repli eager)",
                  detr_graph._lookup_coords_dims(0xDEAD, 0xDEAD, 8, "cuda:0") is None)
            check("coords : device distinct → None",
                  detr_graph._lookup_coords_dims(ptr, ptr, 8, "cuda:1") is None)
        finally:
            detr_graph._spatial_dims.clear()
            detr_graph._spatial_dims.update(saved_dims)
    finally:
        _release_detr(live_fast, saved_torch)


def test_detr_coords_levels(_timing) -> None:
    """Table latérale multi-niveaux : le niveau se déduit de l'offset de la vue."""
    from test_units import check

    detr_graph, live_fast, saved_torch = _detr_module()
    try:
        saved_dims = dict(detr_graph._spatial_dims)
        detr_graph._spatial_dims.clear()
        try:
            ptr = 0xC0FFEE
            detr_graph._record_spatial_dims(ptr, "cuda:0", ((24, 32), (12, 16)))
            check("coords : vue niveau 0 → H/W du niveau 0",
                  detr_graph._lookup_coords_dims(ptr, ptr, 8, "cuda:0") == (24, 32))
            check("coords : vue niveau 1 → H/W du niveau 1",
                  detr_graph._lookup_coords_dims(ptr, ptr + 2 * 8, 8, "cuda:0") == (12, 16))
            check("coords : offset hors table → None",
                  detr_graph._lookup_coords_dims(ptr, ptr + 4 * 8, 8, "cuda:0") is None)
        finally:
            detr_graph._spatial_dims.clear()
            detr_graph._spatial_dims.update(saved_dims)
    finally:
        _release_detr(live_fast, saved_torch)


def test_detr_coords_capture_static(_timing) -> None:
    """Table bornée + chemin de capture sans sync (analyse statique).

    Non-régression du refus de capture (01/11, 3060 Ti) : `int()` sur les
    vues H/W synchronisait pendant la capture CUDA → 8/8 captures refusées
    (`cudaErrorStreamCaptureUnsupported`), eager déguisé. Les H/W viennent
    de la table latérale (zéro sync) ; un sync réintroduit est refusé ici
    et par le harness GPU (capture refusée).
    """
    import inspect

    from test_units import check

    detr_graph, live_fast, saved_torch = _detr_module()
    try:
        saved_dims = dict(detr_graph._spatial_dims)
        detr_graph._spatial_dims.clear()
        try:
            ptr = 0xC0FFEE
            for index in range(detr_graph._SPATIAL_DIMS_MAX + 4):
                detr_graph._record_spatial_dims(0x1000 + index, "cuda:0", ((8, 8),))
            detr_graph._record_spatial_dims(ptr, "cuda:0", ((24, 32),))
            check("coords : table bornée",
                  len(detr_graph._spatial_dims) <= detr_graph._SPATIAL_DIMS_MAX,
                  str(len(detr_graph._spatial_dims)))
            check("coords : entrée courante conservée après éviction",
                  detr_graph._lookup_coords_dims(ptr, ptr, 8, "cuda:0") == (24, 32))
        finally:
            detr_graph._spatial_dims.clear()
            detr_graph._spatial_dims.update(saved_dims)
        src = inspect.getsource(detr_graph._patched_coords)
        src += inspect.getsource(detr_graph._patched_shapes)
        check("coords : gate capture présent", "is_current_stream_capturing" in src)
        check("coords : pas de lecture sync inline sur les vues pendant la capture",
              "int(height)" not in src and "int(width)" not in src)
        check("coords : pas de .item()/.tolist() dans les contournements",
              ".item(" not in src and ".tolist(" not in src)
    finally:
        _release_detr(live_fast, saved_torch)


class _SyncTensor:
    """Tensor factice : ``detection_entries`` (``[top].byte().cpu().numpy()``)."""

    def __init__(self, array) -> None:
        import numpy as np

        self._array = np.asarray(array)

    def __getitem__(self, key):
        return _SyncTensor(self._array[key])

    def byte(self):
        return _SyncTensor(self._array.astype("uint8"))

    def float(self):
        return self

    def cpu(self):
        return self

    def numpy(self):
        return self._array


def _sync_detection(count):
    """Détection factice : ``count`` boîtes/scores/masques uint8 (doublure passe DETR)."""
    import numpy as np

    scores = np.linspace(0.9, 0.1, count)
    boxes = np.tile(np.array([0.0, 0.0, 4.0, 4.0]), (count, 1))
    masks = np.zeros((count, 8, 8), dtype=np.uint8)
    masks[:, 1, 1] = 1
    return {"boxes": _SyncTensor(boxes), "scores": _SyncTensor(scores),
            "masks": _SyncTensor(masks)}


def test_sync_timing(_timing) -> None:
    """``FAST_SYNC_TIMING`` off par défaut ; ``_sync_cuda`` no-op sans GPU ; poste ``d2h_ms``."""
    import importlib.util
    import sys

    from test_units import BACKEND_DIR, check

    live_fast = str(BACKEND_DIR / "live_fast")
    sys.path.insert(0, live_fast)
    try:
        spec = importlib.util.spec_from_file_location(
            "fast_core_sync_test", BACKEND_DIR / "live_fast" / "fast_core.py")
        fast_core = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fast_core)
        check("sync_timing : levier éteint par défaut", fast_core.SYNC_TIMING is False)
        saved_torch = sys.modules.pop("torch", None)
        fast_core.SYNC_TIMING = True
        try:
            fast_core._sync_cuda()
            check("sync_timing : no-op sans torch ni CUDA", True)
        finally:
            fast_core.SYNC_TIMING = False
            if saved_torch is not None:
                sys.modules["torch"] = saved_torch
        check("sync_timing : exposé dans levers()",
              fast_core.levers().get("sync_timing") is False, str(sorted(fast_core.levers())))
        real = fast_core._detections
        fast_core._detections = lambda *a, **k: [_sync_detection(4), _sync_detection(4)]
        try:
            timers: dict = {}
            ctx = {"model": None, "processor": None, "device": "cpu", "dtype": None}
            entries = fast_core._collect(ctx, "vision", ["a", "b"], ["a", "b"],
                                         (8, 8), (8, 8), True, 4, timers)
        finally:
            fast_core._detections = real
        check("d2h : poste mesuré dans _collect", "d2h_ms" in timers, str(sorted(timers)))
        check("d2h : objets intacts", len(entries) == 8, str(len(entries)))
    finally:
        sys.path.remove(live_fast)
