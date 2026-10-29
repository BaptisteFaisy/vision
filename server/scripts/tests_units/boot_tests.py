"""Tests des infos de boot de ``/health`` (``restored``, ``load_breakdown``).

Sans GPU : mêmes doublures que le test du split (``weights_tests``) — on fige la
règle ``restored = age - snap > 30`` et la forme des nouveaux champs, pas le modèle.
"""

from __future__ import annotations

import sys
from types import SimpleNamespace


class _FakeModel:
    """Modèle factice : seul ``.to(device).eval()`` est exercé."""

    def to(self, _device):
        return self

    def eval(self):
        return self


def _fake_env(service) -> None:
    """Doublures du chargement lourd : modèle factice, pas de GPU, pas de réseau."""
    fake = _FakeModel()
    service.load = lambda *a, **k: (fake, "proc", "cpu", "bf16")
    service.load_cpu = lambda *a, **k: (fake, "proc", "bf16")
    service.prepare = lambda *a, **k: {}
    service.vision_grid = lambda _m: 1008
    service.gpu_name = lambda: "TEST-GPU"


def _booted(service, snap, age):
    """Service dont ``load_container`` a tourné avec snap/âge imposés."""
    _fake_env(service)
    service.container_age_seconds = lambda: age
    instance = service.Sam3LiveService()
    if snap is not None:  # snapshot : poids CPU + chrono figés au 1er boot
        instance.cpu = {"model": _FakeModel(), "processor": "p", "dtype": "bf16"}
        instance.snap_seconds = snap
    saved = sys.modules.get("segmentation")
    sys.modules["segmentation"] = SimpleNamespace(device_label=lambda: "cpu")
    try:
        instance.load_container()
    finally:
        if saved is None:
            sys.modules.pop("segmentation", None)
        else:
            sys.modules["segmentation"] = saved
    return instance


def test_restored_rule(_module) -> None:
    """``restored`` : l'âge /proc inclut le gel, écart > 30 s (mesures 29/10)."""
    from test_units import check
    from tests_units.weights_tests import _fresh_live_fast

    cases = [("thaw mesuré (snap 20,4, âge 185,8)", 20.4, 185.8, True),
             ("complet mesuré (snap 20,4, âge 23,6)", 20.4, 23.6, False),
             ("limite haute (écart 31)", 20.0, 51.0, True),
             ("limite basse (écart 30 pile)", 20.0, 50.0, False),
             ("Baseten (pas de snap)", None, 1.0, False),
             ("âge illisible (/proc absent)", 12.0, None, False)]
    for name, snap, age, expected in cases:
        service = _fresh_live_fast("service")
        instance = _booted(service, snap, age)
        check(f"boot : restored — {name}", instance.restored is expected,
              f"restored={instance.restored!r}")


def test_health_boot_shape(_module) -> None:
    """``/health`` expose le breakdown, le flag snapshot et ``load_seconds`` intact."""
    from test_units import check
    from tests_units.weights_tests import _fresh_live_fast

    service = _fresh_live_fast("service")
    health = _booted(service, 20.4, 185.8).health()
    breakdown = health.get("load_breakdown") or {}
    check("boot : breakdown à 3 clés",
          sorted(breakdown) == ["age_conteneur_s", "gpu_s", "poids_cpu_1er_demarrage_s"],
          str(sorted(breakdown)))
    check("boot : breakdown porte snap figé + âge",
          breakdown.get("poids_cpu_1er_demarrage_s") == 20.4
          and breakdown.get("age_conteneur_s") == 185.8, str(breakdown))
    check("boot : memory_snapshot est un booléen",
          isinstance(health.get("memory_snapshot"), bool),
          repr(health.get("memory_snapshot")))
    check("boot : restored est un booléen", health.get("restored") is True,
          repr(health.get("restored")))
    check("boot : load_seconds intact (phase GPU, flottant)",
          isinstance(health.get("load_seconds"), float), repr(health.get("load_seconds")))
    check("boot : gpu_s == load_seconds",
          breakdown.get("gpu_s") == health.get("load_seconds"),
          f"{breakdown.get('gpu_s')!r} vs {health.get('load_seconds')!r}")

    bare = _fresh_live_fast("service")
    baseten = _booted(bare, None, 1.0).health()
    check("boot : Baseten restored faux, poids nul",
          baseten.get("restored") is False
          and (baseten.get("load_breakdown") or {}).get("poids_cpu_1er_demarrage_s") is None,
          str(baseten.get("load_breakdown")))
