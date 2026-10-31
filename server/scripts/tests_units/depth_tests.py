"""Tests de la profondeur fusionnée (``with_depth``) : contrat, médianes, run_frame.

Doublures partagées avec ``live_fast_tests`` (même pipeline, mêmes étages).
"""

from __future__ import annotations

import asyncio
import json

import numpy as np

from tests_units.live_fast_tests import _Legacy, _StubProcessor, _pipeline


def test_depth_contract(live_app_fast) -> None:
    """``with_depth`` : défaut False, transmis au pipeline, doublure ancienne servie."""
    from schemas import LiveFastRequest

    from test_units import _live_service, asgi_call, check, sample_png

    png = sample_png()
    check("schéma: with_depth défaut False",
          LiveFastRequest(image_b64=png).with_depth is False)
    check("schéma: with_depth accepté",
          LiveFastRequest(image_b64=png, with_depth=True).with_depth is True)

    service, api = _live_service(live_app_fast)
    captured: dict = {}
    service._live_sync = _pipeline(captured)
    status, _ = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "with_depth": True}).encode()))
    check("/live: with_depth transmis au pipeline",
          status == 200 and captured.get("with_depth") is True, str(captured))
    captured.clear()
    status, _ = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png}).encode()))
    check("/live: client muet → False",
          status == 200 and captured.get("with_depth") is False, str(captured))

    legacy = _Legacy()
    service._live_sync = legacy
    status, _ = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "with_depth": True}).encode()))
    check("/live: doublure à six paramètres servie malgré with_depth",
          status == 200 and legacy.calls == 1, f"{status} {legacy.calls}")


def test_depth_contract_binary(live_app_fast) -> None:
    """``POST /live`` binaire : ``with_depth=1`` transmis, absent → False."""
    import base64

    from test_units import _live_service, asgi_call, check, sample_png

    captured: dict = {}

    def from_image(image, prompts, max_objects, work_edge, batch_prompts, edge,
                   mask_encoding, mask_format, mask_edge, timers, started, with_depth=False):
        captured.update(with_depth=with_depth)
        return {"width": 64, "height": 40, "elapsed_ms": 12, "objects": []}

    service, api = _live_service(live_app_fast)
    service._live_from_image = from_image
    raw = base64.b64decode(sample_png())
    status, _ = asyncio.run(asgi_call(
        api, "POST", "/live", raw, content_type="application/octet-stream",
        query_string=b"with_depth=1"))
    check("/live binaire: with_depth=1 transmis",
          status == 200 and captured.get("with_depth") is True, f"{status} {captured}")
    captured.clear()
    status, _ = asyncio.run(asgi_call(
        api, "POST", "/live", raw, content_type="application/octet-stream"))
    check("/live binaire: with_depth absent → False",
          status == 200 and captured.get("with_depth") is False, f"{status} {captured}")


def test_depth_math(depth) -> None:
    """Médianes de profondeur : 0..1 (grand = proche), boîtes bornées, finalize relaie."""
    from segmentation import finalize

    from test_units import check

    assert depth.MODEL_ID.startswith("depth-anything/"), depth.MODEL_ID
    far = np.full((4, 4), 2.0, dtype=np.float32)
    far[0:2, 0:2] = 8.0
    norm = depth.normalize_disparity(far)
    check("profondeur: 0..1, proche > loin",
          norm.min() >= 0.0 and norm.max() <= 1.0 and norm[0, 0] > norm[3, 3],
          f"{norm.min()} {norm.max()}")
    check("profondeur: carte plate → neutre 0.5",
          bool((depth.normalize_disparity(np.full((2, 2), 5.0)) == 0.5).all()))
    check("profondeur: médiane de boîte",
          depth.median_in_box(norm, [0, 0, 2, 2], (4, 4)) == float(np.median(norm[0:2, 0:2])))
    check("profondeur: boîte vide → None",
          depth.median_in_box(norm, [1, 1, 1, 1], (4, 4)) is None)
    check("profondeur: boîte hors cadre bornée",
          depth.median_in_box(norm, [-10, -10, 100, 100], (4, 4)) == float(np.median(norm)))

    mask = np.zeros((4, 4), dtype=np.uint8)
    mask[0:2, 0:2] = 1
    near = {"label": "person", "score": 0.9, "box": [0.0, 0.0, 2.0, 2.0], "mask": mask}
    entries = depth.attach_medians(norm, [dict(near), dict(near, score=0.1)], (4, 4))
    check("profondeur: médiane jointe aux entrées",
          entries[0].get("depth") == float(np.median(norm[0:2, 0:2])), str(entries[0]))
    payload = finalize(entries, (4, 4), 12, "none")
    check("profondeur: finalize relaie depth",
          payload["objects"][0].get("depth") == entries[0]["depth"],
          str(payload["objects"][0])[:120])
    bare = finalize([dict(near)], (4, 4), 12, "none")
    check("profondeur: finalize sans depth ⇒ clé absente",
          "depth" not in bare["objects"][0], str(bare["objects"][0])[:120])


def test_run_frame_depth(fast_core) -> None:
    """``run_frame(with_depth)`` : médianes jointes sous le verrou, repli gracieux."""
    import depth as depth_mod
    from PIL import Image

    from test_units import check

    image = Image.new("RGB", (64, 32), (10, 200, 30))
    mask = np.zeros((32, 64), dtype=np.uint8)
    mask[4:20, 8:40] = 1
    entries = [{"label": "chaise", "score": 0.9, "box": [8.0, 4.0, 40.0, 20.0], "mask": mask}]
    ctx = {"edge_applied": 518, "processor": _StubProcessor(), "device": "cpu", "dtype": None,
           "depth_model": "model", "depth_processor": "processor"}
    saved = (fast_core._vision, fast_core._collect, depth_mod.depth_map_for)
    calls = []
    try:
        fast_core._vision = lambda _ctx, _pixels, _timers: "vision"
        fast_core._collect = lambda *_args: [dict(entry) for entry in entries]

        def fake_map(_model, _processor, _image, _device):
            calls.append(True)
            return np.linspace(0, 1, 32 * 64, dtype=np.float32).reshape(32, 64)

        depth_mod.depth_map_for = fake_map
        full = fast_core.run_frame(ctx, image, ["chaise"], 512, 12, True, with_depth=True)
        quiet = fast_core.run_frame(ctx, image, ["chaise"], 512, 12, True)
        bare = fast_core.run_frame({"edge_applied": 518, "processor": _StubProcessor(),
                                    "device": "cpu", "dtype": None},
                                   image, ["chaise"], 512, 12, True, with_depth=True)
    finally:
        fast_core._vision, fast_core._collect = saved[0], saved[1]
        depth_mod.depth_map_for = saved[2]
    got = full["objects"][0].get("depth")
    check("run_frame: with_depth joint une médiane 0..1",
          isinstance(got, float) and 0.0 <= got <= 1.0, str(got))
    check("run_frame: sans with_depth, un seul appel et pas de clé",
          len(calls) == 1 and "depth" not in quiet["objects"][0], str(len(calls)))
    check("run_frame: modèle absent ⇒ repli gracieux",
          "depth" not in bare["objects"][0], str(bare["objects"][0])[:120])
