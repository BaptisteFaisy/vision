"""Tests de la **fusion** du 25/10 20:30 : poids embarqués (son apport) + portage latence.

Ces cas ne mesurent rien : ils vérifient que les deux chantiers cohabitent dans l'arbre
fusionné, sans perte d'un côté ni de l'autre. Ils lisent la signature et le source (aucun
GPU, aucun torch, aucun réseau) ; l'exécution de ``load_cpu`` avec des doublures est dans
``weights_tests.py`` — ici on fige le **branchement** dans ``live_app_fast``.
"""

from __future__ import annotations

import inspect


def test_load_cpu(fast_core) -> None:
    """``load_cpu`` existe, ne touche pas au GPU et lit les poids via ``local_only()``."""
    from test_units import check

    check("poids: load_cpu(dtype_name, edge) présent",
          list(inspect.signature(fast_core.load_cpu).parameters) == ["dtype_name", "edge"],
          str(inspect.signature(fast_core.load_cpu)))
    source = inspect.getsource(fast_core.load_cpu)
    check("poids: load_cpu charge en local_only",
          source.count("**local_only()") == 2, str(source.count("**local_only()")))
    check("poids: load() charge aussi en local_only",
          inspect.getsource(fast_core.load).count("**local_only()") == 2)


def test_fusion_live_app(live_app_fast) -> None:
    """Le conteneur garde le snapshot CPU **et** le portage latence (verrou, bits, chronos)."""
    from test_units import check

    source = inspect.getsource(live_app_fast)
    check("fusion: entrée snapshottée load_weights", "def load_weights" in source)
    check("fusion: snapshot mémoire piloté par FAST_SNAPSHOT",
          "enable_memory_snapshot=SNAPSHOT" in source)
    check("fusion: quatre slots par conteneur", "@modal.concurrent(max_inputs=4)" in source)
    check("fusion: _live_sync accepte mask_encoding",
          "mask_encoding" in inspect.signature(live_app_fast.Sam3Live._live_sync).parameters)
    check("fusion: /live passe par run_frame (verrou fin)",
          "run_frame(self.ctx" in inspect.getsource(live_app_fast.Sam3Live._live_from_image))
    check("fusion: chronos exposés (Server-Timing)", "Server-Timing" in source)
