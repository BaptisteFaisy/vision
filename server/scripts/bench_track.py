#!/usr/bin/env python3
"""Benchmark de l'app Modal « sam3-track » : /session puis N × /frame en séquence.

Pour chaque résolution d'envoi, décode la vidéo fournie, ré-encode les frames en JPEG q80
puis base64, et mesure l'aller-retour HTTP **et** le temps serveur (`elapsed_ms`) :
min / médiane / p95 / max, fps soutenu réel, et stabilité des ids d'objets.

    python3 scripts/bench_track.py --url https://...modal.run --frames 60 \
        --sizes 1280x720,854x480,640x360 --prompt person

Sortie : tableau console + /tmp/track-bench.json (brut, pour docs/TRACK-PERF.md).
"""

from __future__ import annotations

import argparse
import base64
import http.client
import io
import json
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from PIL import Image

JPEG_QUALITY = 80


class Poster:
    """Client HTTP keep-alive : une seule connexion TLS pour toutes les frames.

    Sans lui chaque POST paie un handshake complet (deux allers-retours vers eu-west,
    ~100–150 ms) : l'aller-retour mesuré ne dit plus rien de la latence réelle de l'app,
    qui garde sa connexion ouverte.
    """

    def __init__(self, url: str, timeout: float):
        parts = urllib.parse.urlsplit(url.rstrip("/"))
        self.host, self.prefix, self.timeout = parts.netloc, parts.path or "", timeout
        self.conn = None

    def _send(self, method: str, path: str, body: str = "") -> tuple:
        """Écrit la requête sur la connexion (reconnecte une fois si elle a expiré)."""
        for attempt in (1, 2):
            if self.conn is None:
                self.conn = http.client.HTTPSConnection(self.host, timeout=self.timeout)
            try:
                self.conn.request(method, self.prefix + path, body=body,
                                  headers={"Content-Type": "application/json",
                                           "Accept": "application/json"})
                response = self.conn.getresponse()
                return response.status, response.read()
            except (http.client.HTTPException, OSError):
                self.close()
                if attempt == 2:
                    raise
        raise RuntimeError("unreachable")

    def post(self, path: str, payload: dict) -> tuple[int, dict, float]:
        """POST JSON, renvoie (statut, corps, durée murale en ms)."""
        started = time.monotonic()
        try:
            status, raw = self._send("POST", path, json.dumps(payload))
        except Exception as exc:  # noqa: BLE001 — réseau : on remonte l'erreur textuellement
            return 0, {"detail": str(exc)}, (time.monotonic() - started) * 1000
        return status, json.loads(raw.decode() or "{}"), (time.monotonic() - started) * 1000

    def delete(self, path: str) -> tuple[int, dict]:
        """DELETE JSON (fermeture de session)."""
        status, raw = self._send("DELETE", path)
        return status, json.loads(raw.decode() or "{}")

    def close(self) -> None:
        """Ferme la connexion (silencieux)."""
        conn, self.conn = self.conn, None
        if conn is not None:
            try:
                conn.close()
            except OSError:
                pass


def post_json(url: str, path: str, payload: dict, timeout: float) -> tuple[int, dict, float]:
    """POST JSON, renvoie (statut, corps, durée murale en ms)."""
    request = urllib.request.Request(
        url.rstrip("/") + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            body, status = json.loads(resp.read().decode() or "{}"), resp.status
    except urllib.error.HTTPError as exc:
        body = json.loads(exc.read().decode() or "{}")
        status = exc.code
    except Exception as exc:  # noqa: BLE001 — réseau : on remonte l'erreur textuellement
        return 0, {"detail": str(exc)}, (time.monotonic() - started) * 1000
    return status, body, (time.monotonic() - started) * 1000


def delete_json(url: str, path: str, timeout: float = 30.0) -> tuple[int, dict]:
    """DELETE JSON (fermeture de session)."""
    request = urllib.request.Request(url.rstrip("/") + path, method="DELETE",
                                     headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode() or "{}")


def extract_frames(video: str, width: int, height: int, count: int) -> list:
    """`count` frames RGB de la vidéo, redimensionnées par ffmpeg (rawvideo, pipe)."""
    command = ["ffmpeg", "-v", "error", "-i", video, "-vf", f"scale={width}:{height}",
               "-frames:v", str(count), "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"]
    raw = subprocess.run(command, capture_output=True, check=True).stdout
    plane = width * height * 3
    frames = []
    for offset in range(0, min(len(raw), count * plane), plane):
        chunk = raw[offset:offset + plane]
        if len(chunk) < plane:
            break
        frames.append(Image.frombytes("RGB", (width, height), chunk))
    return frames


def jpeg_b64(frame: Image.Image) -> str:
    """JPEG q80 -> base64 (ce que le téléphone enverrait)."""
    buffer = io.BytesIO()
    frame.save(buffer, format="JPEG", quality=JPEG_QUALITY)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def stats(values: list) -> dict:
    """min / médiane / p95 / max en millisecondes."""
    if not values:
        return {"count": 0}
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(0.95 * (len(ordered) - 1))))
    return {"count": len(ordered), "min": round(ordered[0], 1),
            "median": round(statistics.median(ordered), 1), "p95": round(ordered[index], 1),
            "max": round(ordered[-1], 1)}


def id_signature(objects: list) -> tuple:
    """Ids triés de la frame (la stabilité se lit dans cette signature)."""
    return tuple(sorted(int(item["id"]) for item in objects))


def run_size(url: str, frames: list, prompt: str, timeout: float, poster=None,
             capacity: int = 240) -> dict:
    """/session sur la frame 0 puis un /frame par frame restante ; agrège les mesures."""
    post = poster.post if poster else (lambda path, body: post_json(url, path, body, timeout))
    payload = {"image_b64": jpeg_b64(frames[0]), "prompts": [prompt], "capacity": capacity}
    status, body, rtt = post("/session", payload)
    if status != 200:
        return {"error": f"session HTTP {status}: {str(body)[:300]}"}
    session_id = body["session_id"]
    report = {"session_http": status, "session_rtt_ms": round(rtt, 1),
              "session_server_ms": body.get("elapsed_ms"), "objects": len(body.get("objects", [])),
              "ids": [int(item["id"]) for item in body.get("objects", [])],
              "labels": sorted({item["label"] for item in body.get("objects", [])}),
              "response_size": (body["width"], body["height"])}
    server, client, sizes, signatures, payloads, vrams = [], [], [], [id_signature(body.get("objects", []))], [], []
    for frame in frames[1:]:
        encoded = jpeg_b64(frame)
        payloads.append(len(encoded))
        status, body, rtt = post("/frame", {"session_id": session_id, "image_b64": encoded})
        if status != 200:
            report["error"] = f"frame HTTP {status}: {str(body)[:300]}"
            break
        server.append(float(body.get("elapsed_ms", 0)))
        client.append(rtt)
        if body.get("vram_mb") is not None:
            vrams.append(float(body["vram_mb"]))
        sizes.append(len(json.dumps(body)))
        signatures.append(id_signature(body.get("objects", [])))
    report["frames"] = len(client)
    report["http_rtt_ms"] = stats(client)
    report["server_ms"] = stats(server)
    report["steady_fps"] = round(1000.0 / statistics.median(client), 2) if client else 0
    report["server_fps"] = round(1000.0 / statistics.median(server), 2) if server else 0
    report["payload_kb_median"] = round(statistics.median(payloads) / 1024) if payloads else 0
    report["response_kb_median"] = round(statistics.median(sizes) / 1024) if sizes else 0
    report["server_first12"] = [round(value, 1) for value in server[:12]]
    report["client_first12"] = [round(value, 1) for value in client[:12]]
    if vrams:
        report["vram_mb_first12"] = [round(value) for value in vrams[:12]]
        report["vram_mb_max"] = round(max(vrams))
    distinct = sorted({str(item) for item in signatures})
    report["id_sets_distinct"] = len(distinct)
    report["id_sets"] = distinct[:4]
    report["ids_stable"] = len(distinct) == 1
    delete = poster.delete if poster else (lambda path: delete_json(url, path))
    delete(f"/session/{session_id}")
    return report


def main() -> int:
    """Boucle de benchmark : une session complète par résolution d'envoi."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--video", default="/tmp/clip_720p_300.mp4")
    parser.add_argument("--frames", type=int, default=60)
    parser.add_argument("--sizes", default="1280x720,854x480,640x360")
    parser.add_argument("--prompt", default="person")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--out", default="/tmp/track-bench.json")
    parser.add_argument("--capacity", type=int, default=240,
                        help="frames du tampon GPU demandé au serveur (dimensionne la VRAM)")
    parser.add_argument("--no-keepalive", action="store_true",
                        help="une connexion TLS par requête (le vieux comportement, +100 ms)")
    args = parser.parse_args()

    health = urllib.request.Request(args.url.rstrip("/") + "/health")
    with urllib.request.urlopen(health, timeout=args.timeout) as response:
        print("health:", response.read().decode())
    results = {}
    for size in args.sizes.split(","):
        width, height = (int(value) for value in size.lower().split("x"))
        frames = extract_frames(args.video, width, height, args.frames)
        poster = None if args.no_keepalive else Poster(args.url, args.timeout)
        print(f"--- {width}x{height} : {len(frames)} frames, {args.prompt!r}"
              f" {'keep-alive' if poster else 'connexions neuves'} ---", flush=True)
        report = run_size(args.url, frames, args.prompt, args.timeout, poster, args.capacity)
        if poster is not None:
            poster.close()
        results[f"{width}x{height}"] = report
        print(json.dumps(report, indent=2), flush=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2)
    print(f"écrit dans {args.out}")
    return 0 if all("error" not in item for item in results.values()) and results else 1


if __name__ == "__main__":
    sys.exit(main())
