#!/usr/bin/env python3
"""Banc de l'attribution serveur : vision vs DETR vs overhead, sur appel réel.

Un appel ``POST /live`` porte ``breakdown_ms`` (``vision_ms``, ``detr_ms``,
``overhead_ms``, somme = total serveur) : ce banc l'y lit, N fois, et tranche
l'attribution du § 2 bis de ``docs/LATENCE-BUDGET.md`` (vision ≈ 234 ms /
détection DETR ≈ 85 ms, calculées par soustraction). Sur un backend antérieur
(sans ``breakdown_ms``), repli : le partage est recalculé depuis ``timings_ms``
(``FAST_TIMING=1``) + ``elapsed_ms`` — même formule, source signalée.

    python3 scripts/bench_breakdown.py --url https://...modal.run --image /tmp/street.png

``--url`` est OBLIGATOIRE : le banc appelle un backend déployé (donc réveille
potentiellement un GPU) — il n'est jamais lancé automatiquement, et aucune
valeur par défaut ne permet de le lancer par accident.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys

from bench_live import post_json, stats

DEFAULT_PROMPTS = ["person", "car", "dog", "chair"]
# Attribution par soustraction à trancher (docs/LATENCE-BUDGET.md § 2 bis).
BUDGET_VISION_MS = 234.0
BUDGET_DETR_MS = 85.0


def split_from_timings(timings: dict, elapsed_ms: float) -> dict:
    """Partage recalculé (miroir de ``timing.build_breakdown``, stdlib seule).

    Le banc tourne hors conteneur et n'importe pas le backend : cette formule
    est volontairement la même (vision, DETR, résidu borné à 0, une décimale).
    """

    def _num(value) -> float:
        try:
            return float(value)
        except (TypeError, ValueError):
            return 0.0

    vision = round(_num(timings.get("vision_ms")), 1)
    detr = round(_num(timings.get("detr_ms")), 1)
    overhead = round(max(_num(elapsed_ms) - vision - detr, 0.0), 1)
    return {"vision_ms": vision, "detr_ms": detr, "overhead_ms": overhead}


def read_split(body: dict) -> tuple[dict, str]:
    """``(partage, source)`` : ``breakdown_ms`` servi, sinon recalculé."""
    served = body.get("breakdown_ms")
    if isinstance(served, dict) and served:
        return {key: served.get(key, 0.0) for key in ("vision_ms", "detr_ms", "overhead_ms")}, "breakdown_ms"
    return split_from_timings(body.get("timings_ms") or {}, body.get("elapsed_ms", 0)), "timings_ms"


def bench(url: str, image_b64: str, runs: int, prompts: list[str], max_objects: int,
          work_edge: int, mask_encoding: str, timeout: float) -> list[dict]:
    """N appels /live, un partage par appel (+ source et objets)."""
    rows: list[dict] = []
    for _ in range(runs):
        status, body, _wall = post_json(url, "/live", {
            "image_b64": image_b64, "prompts": prompts, "max_objects": max_objects,
            "work_edge": work_edge, "mask_encoding": mask_encoding}, timeout)
        if status != 200:
            raise SystemExit(f"/live : HTTP {status} — {str(body)[:200]}")
        split, source = read_split(body)
        rows.append({"elapsed_ms": body["elapsed_ms"], "source": source,
                     "objects": len(body["objects"]), **split})
    return rows


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Banc breakdown_ms du backend /live.")
    parser.add_argument("--url", default=os.environ.get("API_URL"), help="URL de base (ou $API_URL)")
    parser.add_argument("--image", required=True, help="image de test (PNG/JPEG)")
    parser.add_argument("--runs", type=int, default=6, help="appels consécutifs (défaut 6)")
    parser.add_argument("--prompts", default=",".join(DEFAULT_PROMPTS),
                        help="concepts (§ 2 bis : 4 concepts)")
    parser.add_argument("--max-objects", type=int, default=12, help="objets maximum (défaut 12)")
    parser.add_argument("--work-edge", type=int, default=512, help="résolution de travail (défaut 512)")
    parser.add_argument("--mask-encoding", default="none",
                        help="masques : none (défaut, attribution GPU pure) | png | bits")
    parser.add_argument("--timeout", type=float, default=120.0, help="timeout par requête (défaut 120 s)")
    args = parser.parse_args(argv)
    if not args.url:
        parser.error("URL manquante : --url ou $API_URL")

    image_b64 = base64.b64encode(open(args.image, "rb").read()).decode("ascii")
    prompts = [item.strip() for item in args.prompts.split(",") if item.strip()]
    rows = bench(args.url, image_b64, args.runs, prompts, args.max_objects, args.work_edge,
                 args.mask_encoding, args.timeout)
    medians = {key: stats([row[key] for row in rows])["median"]
               for key in ("elapsed_ms", "vision_ms", "detr_ms", "overhead_ms")}
    print(f"Backend : {args.url}")
    print(f"Image   : {args.image} · prompts={prompts} · runs={args.runs} · "
          f"max_objects={args.max_objects} · work_edge={args.work_edge} · "
          f"mask_encoding={args.mask_encoding}")
    print(f"{'run':>4} {'total':>7} {'vision':>7} {'detr':>7} {'overhead':>9} | objets source")
    for index, row in enumerate(rows, 1):
        print(f"{index:>4} {row['elapsed_ms']:>7} {row['vision_ms']:>7.1f} "
              f"{row['detr_ms']:>7.1f} {row['overhead_ms']:>9.1f} | "
              f"{row['objects']:>6} {row['source']}")
    print(f"\nMédianes (n={args.runs}) : total={medians['elapsed_ms']:.0f} ms · "
          f"vision={medians['vision_ms']:.1f} ms · detr={medians['detr_ms']:.1f} ms · "
          f"overhead={medians['overhead_ms']:.1f} ms")
    print(f"Budget § 2 bis : vision≈{BUDGET_VISION_MS:.0f} detr≈{BUDGET_DETR_MS:.0f} — "
          f"mesuré : vision={medians['vision_ms']:.1f} "
          f"({medians['vision_ms'] / BUDGET_VISION_MS:.2f}×), "
          f"detr={medians['detr_ms']:.1f} ({medians['detr_ms'] / BUDGET_DETR_MS:.2f}×)")
    print("JSON " + json.dumps({"rows": rows, "medians": medians}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
