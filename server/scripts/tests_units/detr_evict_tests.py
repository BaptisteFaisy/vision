"""Budget + éviction TOTALE de `DetrGraphs` (sans torch ni GPU).

Non-régression du batch-8 prod jamais graphé (01/11) : à budget plein, toutes
les formes sont éjectées d'un coup (génération exclusive) au lieu de condamner
toute nouvelle forme à l'eager pour toujours. PAS de LRU par entrée : détruire
un graphe puis en capturer un autre corrompt les co-résidents (torch 2.14.1,
prouvé en torch pur — voir `DetrGraphs._evict_all`).
"""

from __future__ import annotations

import contextlib


class _EvictTensor:
    """Tensor factice : clone, recopie, comparaison (miroir capture + replay)."""

    def __init__(self, shape=(2,)) -> None:
        self.shape = shape
        self.dtype = "f32"

    def clone(self):
        return _EvictTensor(self.shape)

    def copy_(self, _source):
        return self

    def is_floating_point(self):
        return False


class _EvictGraph:
    """Graphe CUDA factice : replay/reset comptés."""

    def __init__(self) -> None:
        self.replays = 0
        self.resets = 0

    def replay(self):
        self.replays += 1

    def reset(self):
        self.resets += 1


class _EvictStream:
    def wait_stream(self, _other) -> None:
        pass


class _EvictCuda:
    """`torch.cuda` factice : GPU présent, capture sans effet."""

    CUDAGraph = _EvictGraph

    def is_available(self):
        return True

    def Stream(self):
        return _EvictStream()

    def current_stream(self):
        return _EvictStream()

    def stream(self, _side):
        return contextlib.nullcontext()

    def graph(self, _graph):
        return contextlib.nullcontext()


def _evict_module():
    """`detr_graph` déployé avec un faux torch capturant (sys.path géré)."""
    import sys
    import types

    from test_units import BACKEND_DIR

    live_fast = str(BACKEND_DIR / "live_fast")
    saved_torch = sys.modules.get("torch")
    fake_torch = types.ModuleType("torch")
    fake_torch.is_tensor = lambda value: isinstance(value, _EvictTensor)
    fake_torch.equal = lambda _left, _right: True
    fake_torch.inference_mode = lambda: contextlib.nullcontext()
    fake_torch.cuda = _EvictCuda()
    sys.path.insert(0, live_fast)
    sys.modules["torch"] = fake_torch
    import detr_graph

    return detr_graph, live_fast, saved_torch


def _release_evict(live_fast, saved_torch) -> None:
    import sys

    if saved_torch is None:
        sys.modules.pop("torch", None)
    else:
        sys.modules["torch"] = saved_torch
    sys.path.remove(live_fast)


def _forme(n: int):
    """Une forme : batch de n concepts (tokens paddés à 32, miroir prod)."""
    return ((_EvictTensor((n, 8)),), {"ids": _EvictTensor((n, 32))})


def _passe(_embeds, _inputs):
    return {"out": _EvictTensor((2,))}


def _capture(graphs, n: int) -> None:
    """Deux passes d'une forme : warm puis capture (vérifiée, acceptée)."""
    embeds, inputs = _forme(n)
    graphs(_passe, embeds, inputs)
    graphs(_passe, embeds, inputs)


def _cles(detr_graph, graphs, *ns):
    return {n: detr_graph.DetrGraphs._shape(*_forme(n)) for n in ns}


def test_detr_evict_all(_timing) -> None:
    """Budget plein : TOUT sort (graphes libérés), la nouvelle se capture."""
    from test_units import check

    detr_graph, live_fast, saved_torch = _evict_module()
    try:
        graphs = detr_graph.DetrGraphs(budget=3)
        for n in (1, 2, 3):
            _capture(graphs, n)
        cles = _cles(detr_graph, graphs, 1, 2, 3, 4, 5)
        vieux = [graphs._entries[cles[n]]["graph"] for n in (1, 2, 3)]
        _capture(graphs, 4)
        check("evict : tous les vieux graphes libérés (reset)",
              all(g.resets == 1 for g in vieux),
              str([g.resets for g in vieux]))
        check("evict : seule la nouvelle forme reste",
              set(graphs._entries) == {cles[4]},
              str(len(graphs._entries)))
        check("evict : compteur + formes éjectées",
              graphs._evictions == 1
              and graphs._evicted == sorted(str(cles[n]) for n in (1, 2, 3)),
              f"{graphs._evictions} {graphs._evicted}")
        check("evict : état ok:1", graphs.state == "ok:1", graphs.state)
        # Re-capture d'une forme éjectée : possible, nouveau graphe.
        avant = vieux[0].resets
        embeds, inputs = _forme(1)
        graphs(_passe, embeds, inputs)
        check("evict : forme éjectée re-capturée en une passe (restée warm)",
              cles[1] in graphs._entries)
        check("evict : re-capture = nouveau graphe, pas le reseté",
              graphs._entries[cles[1]]["graph"] is not vieux[0]
              and vieux[0].resets == avant)
        # Deuxième génération : le compteur suit.
        _capture(graphs, 2)
        _capture(graphs, 5)
        check("evict : deuxième génération", graphs._evictions == 2
              and set(graphs._entries) == {cles[5]},
              f"{graphs._evictions} {len(graphs._entries)}")
    finally:
        _release_evict(live_fast, saved_torch)


def test_detr_budget_non_plein(_timing) -> None:
    """Sous le budget : inchangé (pas d'éviction), défaut couvre presets + custom."""
    from test_units import check

    detr_graph, live_fast, saved_torch = _evict_module()
    try:
        check("budget : défaut = MAX_SHAPES",
              detr_graph.DetrGraphs().budget == detr_graph.MAX_SHAPES)
        check("budget : 3 presets + suivi custom tiennent (prod 01/11)",
              detr_graph.MAX_SHAPES >= 4, str(detr_graph.MAX_SHAPES))
        graphs = detr_graph.DetrGraphs(budget=3)
        _capture(graphs, 1)
        _capture(graphs, 2)
        check("budget : pas d'éviction sous le budget",
              graphs._evictions == 0 and len(graphs._entries) == 2)
        check("budget : état ok:2", graphs.state == "ok:2", graphs.state)
        check("budget : rien d'éjecté", graphs._evicted == [], str(graphs._evicted))
        g1 = graphs._entries[_cles(detr_graph, graphs, 1)[1]]["graph"]
        replays = g1.replays
        embeds, inputs = _forme(1)
        graphs(_passe, embeds, inputs)
        check("budget : replay sous budget", g1.replays == replays + 1,
              str(g1.replays))
        nul = detr_graph.DetrGraphs(budget=0)
        _capture(nul, 1)
        check("budget : budget 0 ne capture jamais (historique)",
              len(nul._entries) == 0 and nul.state == "idle", nul.state)
    finally:
        _release_evict(live_fast, saved_torch)


def test_detr_failed_permanent(_timing) -> None:
    """`_failed` inchangé : échec = eager définitif, budget préservé, pas d'éviction."""
    import io
    from contextlib import redirect_stdout

    from test_units import check

    detr_graph, live_fast, saved_torch = _evict_module()
    try:
        calls = []

        def _boom(embeds, _inputs):
            # Eager OK, capture KO (contrôle Python) : l'échec naît dans `_capture`.
            calls.append(embeds)
            if len(calls) == 3:
                raise RuntimeError("controle-python-factice")
            return {"out": _EvictTensor((2,))}

        graphs = detr_graph.DetrGraphs(budget=2)
        _capture(graphs, 1)
        _capture(graphs, 2)
        mauvaise = _forme(7)
        with redirect_stdout(io.StringIO()):
            for _ in range(3):
                graphs(_boom, *mauvaise)
        cle = detr_graph.DetrGraphs._shape(*mauvaise)
        check("failed : forme en échec marquée", cle in graphs._failed)
        check("failed : jamais capturée malgré les passes",
              cle not in graphs._entries, str(len(graphs._entries)))
        # Pass1 : la place est faite AVANT de savoir (éviction unique) ; ensuite
        # `_failed` court-circuite avant l'éviction : plus jamais de wipe.
        check("failed : une seule éviction pour faire place (pass1)",
              graphs._evictions == 1, str(graphs._evictions))
        with redirect_stdout(io.StringIO()):
            for _ in range(2):
                graphs(_boom, *mauvaise)
        check("failed : passes suivantes sans éviction", graphs._evictions == 1,
              str(graphs._evictions))
        _capture(graphs, 1)
        check("failed : budget réutilisable après l'échec", len(graphs._entries) == 1,
              str(len(graphs._entries)))
        check("failed : status reflète", graphs.status()["failed"] == 1,
              str(graphs.status()))
    finally:
        _release_evict(live_fast, saved_torch)


def test_detr_status(_timing) -> None:
    """`status()` : clés stables + clés debug (formes, évictions, warm, failed)."""
    from test_units import check

    detr_graph, live_fast, saved_torch = _evict_module()
    try:
        graphs = detr_graph.DetrGraphs(budget=2)
        _capture(graphs, 1)
        _capture(graphs, 2)
        _capture(graphs, 3)
        status = graphs.status()
        for cle in ("detr_graph", "shapes", "budget", "keys", "evictions",
                    "evicted", "warmed", "failed"):
            check(f"status : clé {cle} présente", cle in status, str(sorted(status)))
        cles = _cles(detr_graph, graphs, 1, 2, 3)
        check("status : valeurs cohérentes après éviction totale",
              status["shapes"] == 1 and status["budget"] == 2
              and status["keys"] == [str(cles[3])] and status["evictions"] == 1
              and status["warmed"] == 3 and status["failed"] == 0, str(status))
        check("status : evicted = génération éjectée",
              status["evicted"] == sorted([str(cles[1]), str(cles[2])]),
              str(status["evicted"]))
    finally:
        _release_evict(live_fast, saved_torch)
