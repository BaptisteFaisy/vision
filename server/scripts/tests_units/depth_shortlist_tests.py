"""Shortlist depth : médianes mesurées sur les seuls survivants (top + rescapés).

Extrait de ``depth_tests`` (garde < 500 lignes/fichier) : ``shortlist_entries``
et son câblage ``run_frame``. Chargé par ``scripts/test_units.py``.
"""

from __future__ import annotations

import numpy as np

from tests_units.live_fast_tests import _StubProcessor


def _shortlist_entries(count: int = 30):
    """Voitures (scores distincts décroissants) + 2 bas score : rescapés témoins."""
    mask = np.zeros((32, 64), dtype=np.uint8)
    mask[4:20, 8:40] = 1
    box = [8.0, 4.0, 40.0, 20.0]
    entries = [{"label": f"car{i}", "score": round(0.95 - i * 0.01, 4),
                "box": list(box), "mask": mask} for i in range(count)]
    entries.append({"label": "low0", "score": 0.05, "box": list(box), "mask": mask})
    entries.append({"label": "low1", "score": 0.04, "box": list(box), "mask": mask})
    return entries


def test_depth_shortlist(depth) -> None:
    """Shortlist : top + rescapés mesurés, médianes identiques, reste intact."""
    from PIL import Image

    from test_units import check

    entries = _shortlist_entries()
    extras = entries[-2:]
    short = depth.shortlist_entries(entries, 8, extras)
    top8 = sorted(entries, key=lambda entry: entry["score"], reverse=True)[:8]
    wanted = {id(entry) for entry in top8} | {id(entry) for entry in extras}
    check("shortlist: top + rescapés, ordre conservé",
          [id(entry) for entry in short]
          == [id(entry) for entry in entries if id(entry) in wanted]
          and len(short) == 10, str(len(short)))
    again = depth.shortlist_entries(entries, 8, [top8[0], *extras])
    check("shortlist: rescapé déjà dans le top mesuré une fois",
          len(again) == 10, str(len(again)))
    check("shortlist: None → tout (historique)",
          depth.shortlist_entries(entries, None) == entries)
    check("shortlist: cap ≤ 0 → rien",
          depth.shortlist_entries(entries, 0) == []
          and depth.shortlist_entries(entries, -3) == [])
    check("shortlist: cap illisible → tout",
          depth.shortlist_entries(entries, "huit") == entries)

    norm = np.linspace(0, 1, 32 * 64, dtype=np.float32).reshape(32, 64)
    ref = [dict(entry) for entry in entries]
    depth.attach_medians(norm, ref, (64, 32))
    got = [dict(entry) for entry in entries]
    timers: dict = {}
    saved = depth.depth_map_for
    try:
        depth.depth_map_for = lambda *_args: norm
        out = depth.attach_frame_depth(
            {"depth_model": "m", "depth_processor": "p", "device": "cpu"},
            Image.new("RGB", (64, 32)), got, True, timers, 8, lambda lst: lst[-2:])
    finally:
        depth.depth_map_for = saved
    measured = list(range(8)) + [30, 31]
    check("shortlist: même liste rendue (mesure en place)", out is got)
    check("shortlist: médianes identiques sur top + rescapés",
          all(got[i].get("depth") == ref[i].get("depth")
              and isinstance(got[i].get("depth"), float) for i in measured),
          str([got[i].get("depth") for i in measured[:3]]))
    check("shortlist: 22 entrées non mesurées",
          all("depth" not in got[i] for i in range(8, 30)))
    check("shortlist: poste depth_ms émis", "depth_ms" in timers, str(sorted(timers)))


def test_depth_shortlist_frame(fast_core) -> None:
    """run_frame racine : 32 entrées, max 8 → 8 mesurées, toutes servies."""
    import depth as depth_mod
    from PIL import Image

    from test_units import check

    image = Image.new("RGB", (64, 32))
    entries = _shortlist_entries()
    seen: dict = {}
    ctx = {"edge_applied": 518, "processor": _StubProcessor(), "device": "cpu",
           "dtype": None, "depth_model": "model", "depth_processor": "processor"}
    saved = (fast_core._vision, fast_core._collect, depth_mod.depth_map_for)
    try:
        fast_core._vision = lambda _ctx, _pixels, _timers: "vision"

        def collect(*_args):
            seen["entries"] = [dict(entry) for entry in entries]
            return seen["entries"]

        fast_core._collect = collect
        depth_mod.depth_map_for = lambda *_a: np.linspace(
            0, 1, 32 * 64, dtype=np.float32).reshape(32, 64)
        result = fast_core.run_frame(ctx, image, ["voiture"], 512, 8, True,
                                     mask_encoding="none", with_depth=True)
    finally:
        fast_core._vision, fast_core._collect = saved[0], saved[1]
        depth_mod.depth_map_for = saved[2]
    measured = sum("depth" in entry for entry in seen["entries"])
    check("run_frame: 8 mesurées sur 32 (top seul, sans garde)",
          measured == 8, str(measured))
    check("run_frame: 8 objets servis, tous avec depth",
          len(result["objects"]) == 8
          and all("depth" in item for item in result["objects"]),
          str(len(result["objects"])))
