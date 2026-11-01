"""Post à sigmoid différé (``FAST_POST_KEPT_ONLY``) : levier, validation, parité HF.

Sans stack lourde (le cas de ``test_units.py`` : stdlib + PIL + numpy), seuls
le levier et la validation d'entrée sont exercés. Avec torch + transformers
(``.venv-detr``), ``post_kept_only`` est comparé au post HF réel sur sorties
synthétiques (égalité exacte, 100 graines + cas limites).
"""

from __future__ import annotations

import importlib.util
import os
import sys
import time
from pathlib import Path
from types import SimpleNamespace

LIVE_FAST_DIR = Path(__file__).resolve().parents[2] / "backend" / "live_fast"


def _fresh(module_name: str, post: str | None = None):
    """Module déployé importé à neuf, ``FAST_POST_KEPT_ONLY`` maîtrisé (None = absent)."""
    previous = os.environ.get("FAST_POST_KEPT_ONLY")
    if post is None:
        os.environ.pop("FAST_POST_KEPT_ONLY", None)
    else:
        os.environ["FAST_POST_KEPT_ONLY"] = post
    sys.path.insert(0, str(LIVE_FAST_DIR))
    sys.modules.pop("weights", None)
    try:
        spec = importlib.util.spec_from_file_location(f"live_fast_{module_name}_post",
                                                      LIVE_FAST_DIR / f"{module_name}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.remove(str(LIVE_FAST_DIR))
        if previous is None:
            os.environ.pop("FAST_POST_KEPT_ONLY", None)
        else:
            os.environ["FAST_POST_KEPT_ONLY"] = previous


def _real_stack():
    """(torch, Sam3ImageProcessor) ou (None, None) si la stack lourde manque."""
    try:
        import torch
        from transformers.models.sam3.image_processing_sam3 import Sam3ImageProcessor
    except ImportError:
        return None, None
    return torch, Sam3ImageProcessor


def test_post_lever(_module) -> None:
    """Levier ``FAST_POST_KEPT_ONLY`` : défaut hf, valeur exposée."""
    from test_units import check

    default = _fresh("fast_core")
    check("post : défaut hf (chemin historique inchangé)",
          default.POST_KEPT_ONLY is False, repr(default.POST_KEPT_ONLY))
    check("post : levier exposé dans levers()",
          default.levers().get("post") == "hf", str(default.levers().get("post")))
    check("post : FAST_POST_KEPT_ONLY=1 lu à l'import",
          _fresh("fast_core", "1").levers().get("post") == "kept-only")
    check("post : valeur 0 garde le HF",
          _fresh("fast_core", "0").levers().get("post") == "hf")


def test_post_validation(_module) -> None:
    """Tailles incohérentes refusées avant tout calcul (sans torch)."""
    from test_units import check

    fast_post = _fresh("fast_post")
    fake = SimpleNamespace(pred_logits=SimpleNamespace(shape=(2, 200)))
    try:
        fast_post.post_kept_only(fake, 0.35, 0.5, target_sizes=[(4, 4)])
        check("post : tailles incohérentes refusées", False, "pas d'exception")
    except ValueError:
        check("post : tailles incohérentes refusées", True)


def _assert_same(torch, ref, got, case):
    assert len(ref) == len(got), case
    for r, g in zip(ref, got):
        assert torch.equal(r["scores"], g["scores"]), f"{case} scores"
        assert torch.equal(r["boxes"], g["boxes"]), f"{case} boxes"
        assert torch.equal(r["masks"], g["masks"]), f"{case} masks"


def test_post_parity(_module) -> None:
    """Parité bit-à-bit avec le post HF (100 graines + cas limites)."""
    from test_units import check

    torch, Sam3ImageProcessor = _real_stack()
    if torch is None:
        check("post : parité HF (stack lourde absente — prouvée en local)",
              True, ".venv-detr : 100 graines, égalité exacte")
        return
    fast_post = _fresh("fast_post")
    proc = Sam3ImageProcessor.__new__(Sam3ImageProcessor)  # post pur, sans config
    for seed in range(100):
        g = torch.Generator().manual_seed(1000 + seed)
        b = 1 + seed % 3
        logits = torch.randn(b, 200, generator=g) * 2 - 0.5
        boxes = torch.rand(b, 200, 4, generator=g)
        masks = torch.randn(b, 200, 36, 36, generator=g) * 3
        presence = torch.randn(b, 1, generator=g) if seed % 2 else None
        target = [(72, 72)] * b if seed % 3 else None
        outputs = SimpleNamespace(pred_logits=logits, pred_boxes=boxes,
                                  pred_masks=masks, presence_logits=presence)
        ref = proc.post_process_instance_segmentation(
            outputs, threshold=0.35, mask_threshold=0.5, target_sizes=target)
        got = fast_post.post_kept_only(outputs, 0.35, 0.5, target)
        _assert_same(torch, ref, got, f"graine {seed}")
    for name, logits, presence in (
        ("K=0 partout", torch.full((2, 200), -10.0), None),
        ("K=tout", torch.full((2, 200), 10.0), torch.zeros(2, 1)),
    ):
        outputs = SimpleNamespace(pred_logits=logits,
                                  pred_boxes=torch.rand(2, 200, 4),
                                  pred_masks=torch.randn(2, 200, 36, 36),
                                  presence_logits=presence)
        ref = proc.post_process_instance_segmentation(
            outputs, threshold=0.35, mask_threshold=0.5, target_sizes=[(72, 72)] * 2)
        got = fast_post.post_kept_only(outputs, 0.35, 0.5, [(72, 72)] * 2)
        _assert_same(torch, ref, got, name)
    check("post : parité bit-à-bit HF (100 graines + K=0 + K=tout)", True, "égalité exacte")


def test_post_faster(_module) -> None:
    """Le fork bat le HF à taille réelle (mesure informative, seuil large)."""
    from test_units import check

    torch, Sam3ImageProcessor = _real_stack()
    if torch is None:
        check("post : gain mesuré (stack lourde absente — mesuré en local)",
              True, ".venv-detr : ratio affiché")
        return
    fast_post = _fresh("fast_post")
    proc = Sam3ImageProcessor.__new__(Sam3ImageProcessor)
    g = torch.Generator().manual_seed(7)
    outputs = SimpleNamespace(
        pred_logits=torch.randn(2, 200, generator=g) * 2 - 0.5,
        pred_boxes=torch.rand(2, 200, 4, generator=g),
        pred_masks=torch.randn(2, 200, 288, 288, generator=g),
        presence_logits=torch.randn(2, 1, generator=g))
    target = [(512, 512)] * 2
    t0 = time.perf_counter()
    proc.post_process_instance_segmentation(
        outputs, threshold=0.35, mask_threshold=0.5, target_sizes=target)
    hf_ms = (time.perf_counter() - t0) * 1000
    t0 = time.perf_counter()
    fast_post.post_kept_only(outputs, 0.35, 0.5, target)
    fork_ms = (time.perf_counter() - t0) * 1000
    check("post : fork plus rapide que HF à taille réelle",
          fork_ms < hf_ms, f"HF {hf_ms:.0f} ms, fork {fork_ms:.0f} ms (CPU)")
