"""Tests depth côté déploiement : levier transmissible, chargement paresseux."""

from __future__ import annotations


def test_depth_every_deploy(_module) -> None:
    """``FAST_DEPTH_EVERY`` recuit dans l'env de l'image (réglable au deploy)."""
    from test_units import check
    from tests_units.weights_tests import _fresh_live_fast

    app = _fresh_live_fast("live_app_fast")
    check("depth : FAST_DEPTH_EVERY dans LEVER_NAMES",
          "FAST_DEPTH_EVERY" in app.LEVER_NAMES, str(app.LEVER_NAMES))
    check("depth : lever_env() transmet le levier",
          "FAST_DEPTH_EVERY" in app.lever_env(), "absent de lever_env()")


def test_depth_lazy_load(_module) -> None:
    """DA2 paresseux : 1re frame with_depth tente le chargement, échec gracieux."""
    from test_units import check
    from tests_units.weights_tests import _fresh_live_fast

    depth = _fresh_live_fast("depth")
    calls = []
    real = depth.ensure_depth

    def fake_ensure(ctx, device, dtype):
        calls.append((device, dtype))  # échec simulé : aucune clé posée

    try:
        depth.ensure_depth = fake_ensure
        entries = [{"label": "x", "score": 0.9}]
        out = depth.attach_frame_depth({"device": "cpu", "dtype": None}, None,
                                       entries, True, {})
        check("depth : 1re frame tente le chargement, échec = intact",
              calls == [("cpu", None)] and out is entries
              and "depth" not in entries[0], f"{calls} {entries}")
        calls.clear()
        depth.attach_frame_depth({"device": "cpu"}, None, entries, False, {})
        depth.attach_frame_depth({"device": "cpu"}, None, [], True, {})
        check("depth : frame muette ou vide = zéro chargement",
              calls == [], str(calls))
    finally:
        depth.ensure_depth = real
