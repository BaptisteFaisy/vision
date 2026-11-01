#!/usr/bin/env python3
"""Banc du WebSocket ``/live/ws`` : N frames sur une seule connexion.

Une connexion, N frames texte JSON enchaînées, une réponse par frame : on relève l'``id``
renvoyé (corrélation), ``elapsed_ms`` du serveur et l'aller-retour mesuré côté client,
puis moyenne et p95. Sert à comparer au même protocole en ``POST /live``.

    python3 scripts/bench_live_ws.py --url wss://...modal.run/live/ws --image /tmp/frame.jpg
    python3 scripts/bench_live_ws.py --url wss://...modal.run --image-b64 "$B64" --runs 6
    python3 scripts/bench_live_ws.py --url wss://...modal.run --image /tmp/f.jpg --with-depth --runs 8

``--with-depth`` demande la profondeur à chaque frame (comme le téléphone) et
relève ``depth_ms`` (poste ``timings_ms``, présent seulement sur les frames où
la carte est calculée) plus le nombre d'objets portant une médiane : on y lit
le throttle (1 carte sur ``FAST_DEPTH_EVERY``, médianes répétées entre deux).

``--url`` est OBLIGATOIRE : le banc ouvre une vraie connexion à un backend déployé (donc
réveille potentiellement un GPU) — il n'est jamais lancé automatiquement, et aucune
valeur par défaut ne permet de le lancer par accident.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import statistics
import sys
import time
import urllib.parse
from pathlib import Path

from bench_live import stats

DEFAULT_PROMPTS = ["person", "door"]
DEFAULT_PATH = "/live/ws"


def websockets_module():
    """Import tardif : ``--help`` et la lecture d'image marchent sans le module."""
    try:
        import websockets
    except ImportError as exc:  # pragma: no cover - dépend de l'environnement
        raise SystemExit("module 'websockets' absent (aucune installation automatique)") from exc
    return websockets


def ws_url(url: str) -> str:
    """``http(s)://`` → ``ws(s)://``, et chemin ``/live/ws`` quand l'URL n'en porte pas."""
    base = url.strip().replace("https://", "wss://").replace("http://", "ws://")
    parts = urllib.parse.urlsplit(base)
    if parts.scheme not in ("ws", "wss") or not parts.netloc:
        raise SystemExit(f"--url invalide : {url!r} (attendu ws:// ou wss://)")
    path = parts.path if parts.path not in ("", "/") else DEFAULT_PATH
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, path, parts.query, ""))


def load_image_b64(image: str | None, image_b64: str | None) -> str:
    """Image de test : chaîne fournie, fichier binaire (encodé) ou JSON portant ``image_b64``."""
    if image_b64:
        return image_b64.strip()
    if not image:
        raise SystemExit("image manquante : --image <fichier> ou --image-b64 <chaîne>")
    data = Path(image).read_bytes()
    if data.lstrip()[:1] == b"{":
        payload = json.loads(data)
        value = payload.get("image_b64") or payload.get("b64")
        if not value:
            raise SystemExit(f"--image {image} : JSON sans clé image_b64")
        return str(value)
    return base64.b64encode(data).decode("ascii")


async def run_frames(ws, image_b64: str, runs: int, prompts: list[str], max_objects: int,
                     work_edge: int, timeout: float, with_depth: bool) -> list[dict]:
    """N frames consécutives sur la connexion ouverte : id renvoyé, serveur, aller-retour."""
    samples: list[dict] = []
    for index in range(runs):
        payload = {"id": index + 1, "image_b64": image_b64, "prompts": prompts,
                   "max_objects": max_objects, "work_edge": work_edge}
        if with_depth:
            payload["with_depth"] = True
        started = time.monotonic()
        await ws.send(json.dumps(payload))
        reply = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        roundtrip = (time.monotonic() - started) * 1000
        if reply.get("error"):
            raise SystemExit(f"id={reply.get('id')} : erreur {reply['error']}")
        objects = reply.get("objects") or []
        timings = reply.get("timings_ms") or {}
        depth_ms = timings.get("depth_ms")
        samples.append({"id": reply.get("id"), "server_ms": float(reply["elapsed_ms"]),
                        "roundtrip_ms": roundtrip, "objects": len(objects),
                        "depth_ms": float(depth_ms) if depth_ms is not None else None,
                        "depth_n": sum(1 for obj in objects
                                       if isinstance(obj, dict) and "depth" in obj)})
    return samples


def depth_summary(samples: list[dict]) -> dict:
    """Cartes calculées, depth_ms médiane, coût amorti et présence des médianes."""
    computed = [item["depth_ms"] for item in samples if item["depth_ms"] is not None]
    full = sum(1 for item in samples if item["objects"] > 0 and item["depth_n"] == item["objects"])
    return {"computed": len(computed), "depth_ms": stats(computed) if computed else None,
            "amortized_ms": round(sum(computed) / len(samples), 1) if samples else 0.0,
            "full_frames": full}


async def bench(url: str, image_b64: str, runs: int, prompts: list[str], max_objects: int,
                work_edge: int, timeout: float, with_depth: bool) -> dict:
    """Ouvre ``/live/ws`` une fois et enchaîne les frames, une à la fois comme le client."""
    websockets = websockets_module()
    async with websockets.connect(url, max_size=None, open_timeout=timeout) as ws:
        samples = await run_frames(ws, image_b64, runs, prompts, max_objects, work_edge,
                                   timeout, with_depth)
    server = [item["server_ms"] for item in samples]
    client = [item["roundtrip_ms"] for item in samples]
    result = {"url": url, "runs": len(samples), "samples": samples,
              "server": stats(server), "client": stats(client),
              "server_mean": statistics.mean(server), "client_mean": statistics.mean(client)}
    if with_depth:
        result["depth"] = depth_summary(samples)
    return result


def report(result: dict) -> None:
    """Une ligne par frame, puis moyenne et p95 des deux mesures."""
    depth = result.get("depth")
    print(f"\n{result['url']} — {result['runs']} frames, une connexion")
    header = f"{'id':>5} {'serveur':>10} {'A/R':>10} {'objets':>7}"
    if depth is not None:
        header += f" {'depth_ms':>9} {'médianes':>8}"
    print(header)
    for item in result["samples"]:
        line = (f"{item['id']:>5} {item['server_ms']:>8.0f}ms {item['roundtrip_ms']:>8.0f}ms "
                f"{item['objects']:>7}")
        if depth is not None:
            mark = f"{item['depth_ms']:.0f}ms" if item["depth_ms"] is not None else "répétée"
            line += f" {mark:>9} {item['depth_n']:>8}"
        print(line)
    server, client = result["server"], result["client"]
    print(f"\nmoyenne : serveur {result['server_mean']:.0f} ms · A/R {result['client_mean']:.0f} ms")
    print(f"p95     : serveur {server['p95']:.0f} ms · A/R {client['p95']:.0f} ms")
    if depth is not None:
        med = depth["depth_ms"]["median"] if depth["depth_ms"] else None
        med_txt = f"{med:.0f} ms" if med is not None else "absente (FAST_TIMING coupé ?)"
        print(f"depth   : cartes {depth['computed']}/{result['runs']} · "
              f"carte médiane {med_txt} · amortie {depth['amortized_ms']:.1f} ms/frame · "
              f"médianes complètes {depth['full_frames']}/{result['runs']} réponses")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Banc du WebSocket /live/ws (une connexion, N frames, moyenne + p95).")
    parser.add_argument("--url", required=True,
                        help="URL de /live/ws (ws:// ou wss://) — obligatoire, aucune valeur par défaut")
    parser.add_argument("--image", help="image de test : fichier binaire, ou JSON portant image_b64")
    parser.add_argument("--image-b64", help="image déjà encodée en base64")
    parser.add_argument("--runs", type=int, default=6, help="frames envoyées (défaut 6)")
    parser.add_argument("--prompts", default=",".join(DEFAULT_PROMPTS),
                        help="concepts séparés par des virgules (défaut person,door)")
    parser.add_argument("--max-objects", type=int, default=4, help="objets maximum (défaut 4)")
    parser.add_argument("--work-edge", type=int, default=640, help="résolution de travail (défaut 640)")
    parser.add_argument("--timeout", type=float, default=120.0, help="timeout par frame (défaut 120 s)")
    parser.add_argument("--with-depth", action="store_true",
                        help="demande la profondeur à chaque frame (comme le téléphone)")
    args = parser.parse_args(argv)
    if args.runs < 1:
        parser.error("--runs doit être ≥ 1")
    image_b64 = load_image_b64(args.image, args.image_b64)
    prompts = [item.strip() for item in args.prompts.split(",") if item.strip()]
    print(f"Image : {len(image_b64)} car. base64 · prompts={prompts} · runs={args.runs} · "
          f"work_edge={args.work_edge} · max_objects={args.max_objects} · "
          f"with_depth={int(args.with_depth)}")
    result = asyncio.run(bench(ws_url(args.url), image_b64, args.runs, prompts,
                               args.max_objects, args.work_edge, args.timeout, args.with_depth))
    report(result)
    summary = {key: value for key, value in result.items() if key != "samples"}
    print("JSON " + json.dumps(summary, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
