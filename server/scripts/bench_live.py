#!/usr/bin/env python3
"""Benchmark de la route /live du backend sam3-live (stdlib uniquement).

Une image, N appels consécutifs par résolution de travail, puis min/médiane/p95/max
en millisecondes — côté serveur (`elapsed_ms`) et côté client (aller-retour complet).

    python3 scripts/bench_live.py --url https://...modal.run --image /tmp/street.png
    python3 scripts/bench_live.py --url $URL --image a.jpg --edges 512,640,768 --runs 10
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import statistics
import struct
import sys
import time
import urllib.request

DEFAULT_PROMPTS = ["person", "car", "dog", "chair"]
PNG_SIG = b"\x89PNG\r\n\x1a\n"


def post_json(url: str, path: str, payload: dict, timeout: float) -> tuple[int, dict, float]:
    """POST JSON, renvoie (statut, corps, durée murale en ms)."""
    request = urllib.request.Request(
        url.rstrip("/") + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            body = json.loads(resp.read().decode())
            status = resp.status
    except urllib.error.HTTPError as exc:
        body = json.loads(exc.read().decode() or "{}")
        status = exc.code
    return status, body, (time.monotonic() - started) * 1000


def png_size(mask_b64: str) -> tuple[int, int]:
    """(largeur, hauteur) lues dans le chunk IHDR d'un PNG base64."""
    raw = base64.b64decode(mask_b64)
    if raw[:8] != PNG_SIG:
        return 0, 0
    width, height = struct.unpack(">II", raw[16:24])
    return width, height


def stats(samples: list[float]) -> dict:
    """min / médiane / p95 / max (p95 = plus proche rang, effectif petit)."""
    ordered = sorted(samples)
    index = max(0, min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1)))))
    return {"min": ordered[0], "median": statistics.median(ordered),
            "p95": ordered[index], "max": ordered[-1]}


def bench_edge(url: str, image_b64: str, edge: int, runs: int, prompts: list[str],
               max_objects: int, timeout: float) -> dict:
    """N appels /live consécutifs à une résolution donnée, puis statistiques."""
    server_ms: list[float] = []
    client_ms: list[float] = []
    counts: list[int] = []
    size_ok = None
    for _ in range(runs):
        status, body, wall = post_json(url, "/live", {
            "image_b64": image_b64, "prompts": prompts,
            "max_objects": max_objects, "work_edge": edge}, timeout)
        if status != 200:
            raise SystemExit(f"/live work_edge={edge} : HTTP {status} — {str(body)[:200]}")
        server_ms.append(float(body["elapsed_ms"]))
        client_ms.append(wall)
        counts.append(len(body["objects"]))
        if size_ok is None and body["objects"]:
            mask_w, mask_h = png_size(body["objects"][0]["mask_b64"])
            size_ok = (mask_w, mask_h) == (body["width"], body["height"])
    return {
        "edge": edge, "runs": runs,
        "server": stats(server_ms), "client": stats(client_ms),
        "objects": sorted(set(counts)), "mask_matches_image": size_ok,
        "width": body["width"], "height": body["height"],
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Benchmark /live du backend sam3-live.")
    parser.add_argument("--url", default=os.environ.get("API_URL"), help="URL de base (ou $API_URL)")
    parser.add_argument("--image", required=True, help="image de test (PNG/JPEG)")
    parser.add_argument("--edges", default="512,640,768", help="résolutions de travail, séparées par des virgules")
    parser.add_argument("--runs", type=int, default=10, help="appels par résolution (défaut 10)")
    parser.add_argument("--prompts", default=",".join(DEFAULT_PROMPTS), help="concepts séparés par des virgules")
    parser.add_argument("--max-objects", type=int, default=12, help="objets maximum (défaut 12)")
    parser.add_argument("--timeout", type=float, default=120.0, help="timeout par requête (défaut 120 s)")
    args = parser.parse_args(argv)
    if not args.url:
        parser.error("URL manquante : --url ou $API_URL")

    image_b64 = base64.b64encode(open(args.image, "rb").read()).decode("ascii")
    prompts = [item.strip() for item in args.prompts.split(",") if item.strip()]
    print(f"Backend : {args.url}")
    print(f"Image   : {args.image} ({len(image_b64)} car. base64)")
    print(f"Prompts : {prompts} · runs={args.runs} · max_objects={args.max_objects}\n")
    print(f"{'edge':>5} {'min':>7} {'méd.':>7} {'p95':>7} {'max':>7} | "
          f"{'cl. min':>8} {'cl. méd.':>8} {'cl. p95':>8} {'cl. max':>8} | objets")
    rows = []
    for edge in (int(item) for item in args.edges.split(",") if item.strip()):
        row = bench_edge(args.url, image_b64, edge, args.runs, prompts, args.max_objects, args.timeout)
        rows.append(row)
        server, client = row["server"], row["client"]
        print(f"{edge:>5} {server['min']:>7.0f} {server['median']:>7.0f} {server['p95']:>7.0f} "
              f"{server['max']:>7.0f} | {client['min']:>8.0f} {client['median']:>8.0f} "
              f"{client['p95']:>8.0f} {client['max']:>8.0f} | {row['objects']}")
    print(f"\nImage source : {rows[0]['width']}x{rows[0]['height']} · "
          f"masques à la taille exacte : {[row['mask_matches_image'] for row in rows]}")
    print("JSON " + json.dumps(rows, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())