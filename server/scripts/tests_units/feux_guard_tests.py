"""Garde serveur des feux FR : labels, anti-éviction, work_edge cohérent (sans GPU).

Exerce ``backend/live_fast/feux_guard.py`` et son câblage (``fast_core._payload``,
``run_frame``, ``service._live_from_image``) importés à neuf depuis le dossier
déployé — même motif que ``crossing_concepts_tests``. Les imports frères
(``segmentation``, ``timing``…) retombent sur les copies racine, prouvées
byte-identiques par ``VENDORED_PARITY``. Chargé par ``scripts/test_units.py``.
"""

from __future__ import annotations

import importlib.util
import io
import sys
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace

BACKEND_DIR = Path(__file__).resolve().parents[2] / "backend"
LIVE_FAST_DIR = BACKEND_DIR / "live_fast"


def _live_fast_module(name):
    """``live_fast/<name>.py`` importé à neuf (jamais la copie racine)."""
    sys.path.insert(0, str(LIVE_FAST_DIR))
    try:
        spec = importlib.util.spec_from_file_location(
            f"live_fast_{name}_feux_probe", LIVE_FAST_DIR / f"{name}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    finally:
        sys.path.remove(str(LIVE_FAST_DIR))
    if name == "feux_guard":
        # Le déployé fait ``from feux_guard import …`` : le cache doit répondre
        # (aucune copie racine, pas de collision possible).
        sys.modules.setdefault("feux_guard", module)
    return module


def _entries(feu_label="feu piéton"):
    """5 voitures (scores 0,9..0,7) + un petit feu (0,12) : évincé sans garde."""
    import numpy as np

    mask = np.zeros((8, 8), dtype=np.uint8)
    mask[2:6, 2:6] = 1
    entries = [{"label": f"car{index}", "score": 0.9 - 0.05 * index,
                "box": [0.0, 0.0, 8.0, 8.0], "mask": mask} for index in range(5)]
    entries.append({"label": feu_label, "score": 0.12,
                    "box": [1.0, 1.0, 4.0, 4.0], "mask": mask})
    return entries


def _labels(guard, check) -> None:
    """Famille « feu » reconnue, le reste (dont piéton) exclu."""
    for label in ["feu piéton", "Feux Piétons", "un feu pieton", "signal piéton",
                  "feu tricolore", "feu rouge", "feu vert", "traffic light",
                  "pedestrian traffic light", "pedestrian light", "Pedestrian Signal",
                  "signal", "a signal"]:
        check(f"feux: label feu reconnu ({label})", guard.is_feu_label(label), label)
    for label in ["car", "person", "piéton", "pedestrian", "passant", "stop",
                  "panneau", "", "   ", None, 42, ["feu"]]:
        check(f"feux: pas un feu ({label!r})", not guard.is_feu_label(label), repr(label))


def _routing(guard, check) -> None:
    """Variantes EN routées vers le modèle, table déployée et inconnus intacts."""
    check("feux: signal -> pedestrian traffic light",
          guard.routed_concept("signal") == "pedestrian traffic light")
    check("feux: Pedestrian Light (casse) routé",
          guard.routed_concept("Pedestrian Light") == "pedestrian traffic light")
    check("feux: feu piéton inchangé (table déployée)",
          guard.routed_concept("feu piéton") == "pedestrian traffic light")
    check("feux: feu tricolore inchangé (sens voiture)",
          guard.routed_concept("feu tricolore") == "traffic light")
    check("feux: alias FR intact (une voiture -> car)",
          guard.routed_concept("une voiture") == "car")
    check("feux: inconnu passe (kayak)", guard.routed_concept("kayak") == "kayak")


def _work_edge(guard, check) -> None:
    """work_edge déclaré clampé à l'image reçue + log, sinon silencieux."""
    buf = io.StringIO()
    with redirect_stdout(buf):
        small = guard.coherent_work_edge(768, (640, 480))
    check("feux: work_edge clampé à l'image reçue", small == 640, str(small))
    check("feux: clamp loggé (déclaré + reçu)",
          "768" in buf.getvalue() and "640" in buf.getvalue(), buf.getvalue().strip())
    buf = io.StringIO()
    with redirect_stdout(buf):
        big = guard.coherent_work_edge(512, (2000, 1000))
    check("feux: image plus grande -> inchangé, silencieux",
          big == 512 and buf.getvalue() == "", f"{big} {buf.getvalue()!r}")
    check("feux: taille illisible -> inchangé, sans lever",
          guard.coherent_work_edge(768, None) == 768
          and guard.coherent_work_edge(768, (0, 0)) == 768)


def _finalize(guard, seg, check) -> None:
    """Slot réservé au feu évincé, tri pur sinon, seuils inchangés."""
    direct = seg.finalize(_entries(), (16, 16), 3, "none")
    check("feux: sans garde le petit feu est évincé",
          all(item["label"] != "feu piéton" for item in direct["objects"]),
          str([item["label"] for item in direct["objects"]]))
    kept = guard.finalize_guarded(seg.finalize, _entries(), (16, 16), 3, "none")
    check("feux: garde réserve la dernière place au feu",
          [item["label"] for item in kept["objects"]] == ["car0", "car1", "feu piéton"],
          str([item["label"] for item in kept["objects"]]))
    check("feux: seuils globaux inchangés",
          seg.SCORE_THRESHOLD == 0.35 and seg.MASK_THRESHOLD == 0.5)
    strong = _entries()
    strong[-1]["score"] = 0.95
    same = guard.finalize_guarded(seg.finalize, strong, (16, 16), 3, "none")
    check("feux: feu déjà dans le top -> tri pur",
          [item["label"] for item in same["objects"]] == ["feu piéton", "car0", "car1"],
          str([item["label"] for item in same["objects"]]))
    one = guard.finalize_guarded(seg.finalize, _entries(), (16, 16), 1, "none")
    check("feux: max_objects=1 -> tri pur, pas de réserve",
          [item["label"] for item in one["objects"]] == ["car0"])
    sig = guard.finalize_guarded(seg.finalize, _entries("signal"), (16, 16), 3, "none")
    check("feux: label 'signal' acheminé (pas évincé)",
          any(item["label"] == "signal" for item in sig["objects"]),
          str([item["label"] for item in sig["objects"]]))
    rle = guard.finalize_guarded(seg.finalize, _entries(), (512, 512), 3, "png",
                                 mask_format="rle", mask_edge=256)
    check("feux: RLE garde le feu + mask_scale par objet",
          rle["mask_encoding"] == "rle"
          and any(item["label"] == "feu piéton" for item in rle["objects"])
          and all("mask_scale" in item for item in rle["objects"]),
          str(rle)[:160])


def _payload_wiring(check) -> None:
    """``_payload`` déployé garde le feu ; ``run_frame`` route les concepts."""
    core = _live_fast_module("fast_core")
    timers: dict = {}
    result = core._payload(_entries(), (16, 16), 3, "none", timers)
    check("feux: _payload déployé garde le feu",
          [item["label"] for item in result["objects"]] == ["car0", "car1", "feu piéton"],
          str(result)[:160])
    check("feux: _payload mesure masks_ms", "masks_ms" in timers, str(sorted(timers)))
    seen: dict = {}
    saved = (core._preprocess, core._vision, core._collect)
    try:
        core._preprocess = lambda _ctx, _img, _w: (SimpleNamespace(size=(8, 8)), "px")
        core._vision = lambda _ctx, _px, _timers: "vision"

        def collect(_ctx, _vision, prompts, concepts, *_rest):
            seen["prompts"], seen["concepts"] = list(prompts), list(concepts)
            return []

        core._collect = collect
        core.run_frame({"edge_applied": 518}, SimpleNamespace(size=(16, 16)),
                       ["signal", "chaise"], 512, 3, True, mask_encoding="none")
    finally:
        core._preprocess, core._vision, core._collect = saved
    check("feux: run_frame route signal vers le modèle",
          seen.get("concepts") == ["pedestrian traffic light", "chair"], str(seen))
    check("feux: labels intacts pour le client",
          seen.get("prompts") == ["signal", "chaise"], str(seen))


def _service_wiring(check) -> None:
    """``_live_from_image`` déployé clampe work_edge à l'image reçue."""
    import threading

    from PIL import Image
    from timing import now

    service_mod = _live_fast_module("service")
    service = service_mod.Sam3LiveService()
    service.ctx = {"model": None, "processor": None, "vision_accel": "none"}
    service._gpu_lock = threading.Lock()
    captured: dict = {}

    def fake_run_frame(_ctx, _image, concepts, work, cap, _batch, **_knobs):
        captured.update(work=work, cap=cap, concepts=list(concepts))
        return {"width": 640, "height": 480, "objects": [],
                "_edge_applied": None, "_edge_ok": True}

    service_mod.run_frame = fake_run_frame
    buf = io.StringIO()
    with redirect_stdout(buf):
        service._live_from_image(Image.new("RGB", (640, 480)), ["person"], 5, 768, 1,
                                 None, "none", None, 0, {}, now(), False)
    check("feux: service clampe work_edge à l'image reçue",
          captured.get("work") == 640, str(captured))
    check("feux: clamp loggé côté service", "640" in buf.getvalue(), buf.getvalue().strip())
    check("feux: cap max_objects transmis", captured.get("cap") == 5, str(captured))


def test_feux_guard(_) -> None:
    """Garde feux : point d'entrée unique (un seul slot RUNNERS, harnais à 497 l)."""
    from test_units import check

    guard = _live_fast_module("feux_guard")
    _labels(guard, check)
    _routing(guard, check)
    _work_edge(guard, check)
    _finalize(guard, _live_fast_module("segmentation"), check)
    _payload_wiring(check)
    _service_wiring(check)
