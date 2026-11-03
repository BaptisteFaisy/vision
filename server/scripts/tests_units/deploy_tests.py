"""Tests du déploiement : pins workspace/GPU/région, orthographe graphe."""

from __future__ import annotations


def test_no_cudagraph_typo(_module) -> None:
    """Pas de `CUDGraph` (sans A) : la faute a cassé vision_accel au déploiement du 27/10."""
    from test_units import BACKEND_DIR, check

    src = (BACKEND_DIR / "live_fast" / "vision_setup.py").read_text(encoding="utf-8")
    check("graphe : CUDAGraph orthographié", "CUDAGraph" in src, "absent")
    check("graphe : pas de CUDGraph", "CUDGraph" not in src, "faute présente")


def test_deploy_pin(_module) -> None:
    """Le déploiement vise forcément gaiamelie / A100-40GB / eu / eu-west."""
    from test_units import BACKEND_DIR, check
    from tests_units.weights_tests import _fresh_live_fast

    script = (BACKEND_DIR.parent / "scripts" / "deploy-live-fast.sh").read_text(encoding="utf-8")
    for pin in ("MODAL_PROFILE-gaiamelie", "gaiamelie--sam3-eu-a100",
                "FAST_GPU:-A100-40GB", "FAST_REGION:-eu", "FAST_ROUTING_REGION:-eu-west"):
        check(f"déploiement : pin {pin}", pin in script, "absent du script")
    check("déploiement : aucun workspace baptiste-faisy",
          "baptiste-faisy" not in script, "référence périmée dans le script")
    app = _fresh_live_fast("live_app_fast")
    check("déploiement : défaut code A100-40GB", app.GPU_TYPE == "A100-40GB",
          repr(app.GPU_TYPE))
    check("déploiement : défaut code eu / eu-west",
          app.REGION == "eu" and app.ROUTING == "eu-west",
          f"{app.REGION!r} / {app.ROUTING!r}")
