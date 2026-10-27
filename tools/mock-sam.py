#!/usr/bin/env python3
"""Faux serveur SAM 3.1 local : prouve la chaîne lunettes sans GPU allumée.

Contrat servi (`core/.../net/HttpSamClient.kt`) : `GET /health` -> {"ok": true} ;
`POST /segment` <- {"image_b64", "prompts"} -> {"width", "height", "objects":
[{"label", "score", "box", "mask_b64"}], "mask_encoding": "png", "count": n}.
`count` est additif : un client l'ignore. L'image n'est jamais décodée — seules ses
dimensions sortent de l'en-tête (SOF JPEG ou IHDR PNG), défaut 640x480. Masques =
PNG gris 8 bits à la taille de l'image, réponse en quelques millisecondes.

Recette téléphone (aucune GPU) :
  python3 tools/mock-sam.py &          # écoute http://127.0.0.1:8099
  adb reverse tcp:8099 tcp:8099        # le téléphone atteint ce port-ci
  # local.properties : sam31.endpoint=http://127.0.0.1:8099  (avant le build)
Usage : python3 tools/mock-sam.py [--host 127.0.0.1] [--port 8099] [--labels a,b]
"""
from __future__ import annotations

import argparse
import base64
import binascii
import json
import math
import struct
import sys
import time
import zlib
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

# Libellés français par défaut, dans le vocabulaire de l'app (`LlmPrompts`, `:core`).
DEFAULT_LABELS = ("personne", "téléphone", "ordinateur", "chaise", "bouteille")
DEFAULT_W, DEFAULT_H = 640, 480
MIN_IMAGE_CHARS = 8  # `schemas.SegmentRequest` du backend vit aussi en min_length=8
SOF_MARKERS = frozenset(range(0xC0, 0xD0)) - {0xC4, 0xC8, 0xCC, 0xCB, 0xC9}


def _jpeg_size(data: bytes) -> tuple[int, int] | None:
    """Dimensions lues dans le marqueur SOF (aucun pixel décodé). `None` si illisible."""
    if data[:2] != b"\xff\xd8":
        return None
    i, n = 2, len(data)
    while i + 3 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker == 0xD8 or marker == 0x01 or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker == 0xDA:  # début des données entropiques : plus d'en-tête à lire
            return None
        length = int.from_bytes(data[i + 2:i + 4], "big")
        if marker in SOF_MARKERS and i + 9 < n:
            height = int.from_bytes(data[i + 5:i + 7], "big")
            width = int.from_bytes(data[i + 7:i + 9], "big")
            return (width, height) if width and height else None
        i += 2 + length
    return None


def _png_size(data: bytes) -> tuple[int, int] | None:
    """Dimensions lues dans le bloc IHDR. `None` si ce n'est pas un PNG."""
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    return (width, height) if width and height else None


def image_size(image_b64: str) -> tuple[int, int]:
    """Taille annoncée pour n'importe quelle entrée : en-tête lu, sinon défaut."""
    try:
        # L'en-tête (SOF JPEG, IHDR PNG) tient dans les premiers Ko : on décode
        # d'abord une tranche (~3 Ko), l'image entière seulement si ça ne suffit pas.
        head = base64.b64decode(image_b64[:4096], validate=False)
        size = _jpeg_size(head) or _png_size(head)
        if size is not None:
            return size
        data = base64.b64decode(image_b64, validate=False)
    except (binascii.Error, ValueError):
        return (DEFAULT_W, DEFAULT_H)
    return _jpeg_size(data) or _png_size(data) or (DEFAULT_W, DEFAULT_H)


def _chunk(kind: bytes, payload: bytes) -> bytes:
    """Bloc PNG : longueur + type + données + CRC32(type + données)."""
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))


def encode_gray_png(width: int, height: int, rows: list[bytearray]) -> bytes:
    """PNG gris 8 bits (type 0, non entrelacé) depuis des lignes de niveaux 0-255."""
    header = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    raw = b"".join(b"\x00" + bytes(row) for row in rows)  # filtre 0 par ligne
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header)
            + _chunk(b"IDAT", zlib.compress(raw, 1)) + _chunk(b"IEND", b""))


def geometry(width: int, height: int, index: int, total: int) -> tuple[int, int, int, int]:
    """Ellipse de l'objet [index] sur [total] : centre, demi-axes. Déterministe."""
    radius_x = max(2, width // (2 * total + 2))
    radius_y = max(2, height // 4)
    center_x = (2 * index + 1) * width // (2 * total)
    return center_x, height // 2, radius_x, radius_y


def mask_rows(width: int, height: int, index: int, total: int) -> list[bytearray]:
    """Ellipse pleine, remplie par tranches horizontales (jamais pixel par pixel)."""
    center_x, center_y, radius_x, radius_y = geometry(width, height, index, total)
    rows = [bytearray(width) for _ in range(height)]
    for y in range(max(0, center_y - radius_y), min(height, center_y + radius_y + 1)):
        dy = (y - center_y) / radius_y
        half = int(radius_x * math.sqrt(max(0.0, 1.0 - dy * dy)))
        if half <= 0:
            continue
        x0, x1 = max(0, center_x - half), min(width - 1, center_x + half)
        rows[y][x0:x1 + 1] = b"\xff" * (x1 - x0 + 1)
    return rows


def box_of(width: int, height: int, index: int, total: int) -> list[float]:
    """Boîte [x1,y1,x2,y2] de l'ellipse, bornée à l'image (contrat `box`)."""
    center_x, center_y, radius_x, radius_y = geometry(width, height, index, total)
    return [float(max(0, center_x - radius_x)), float(max(0, center_y - radius_y)),
            float(min(width, center_x + radius_x)), float(min(height, center_y + radius_y))]


def labels_for(prompts: list[str] | None, count: int, default: tuple[str, ...]) -> list[str]:
    """Concepts demandés s'il y en a (SAM les suit), sinon le vocabulaire par défaut."""
    pool = [p for p in (prompts or []) if isinstance(p, str) and p.strip()] or list(default)
    return [pool[i % len(pool)] for i in range(count)]


def objects_of(width: int, height: int, prompts: list[str] | None, count: int,
               default: tuple[str, ...]) -> list[dict]:
    """[count] objets segmentés : libellé, score décroissant, boîte, masque PNG."""
    objects = []
    for index, label in enumerate(labels_for(prompts, count, default)):
        mask = encode_gray_png(width, height, mask_rows(width, height, index, count))
        objects.append({
            "label": label,
            "score": round(0.98 - 0.05 * index, 2),
            "box": box_of(width, height, index, count),
            "mask_b64": base64.b64encode(mask).decode("ascii"),
        })
    return objects


def segment_response(raw: bytes, labels: tuple[str, ...]) -> tuple[int, dict]:
    """Réponse de /segment depuis le corps brut : validation puis charge utile."""
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return 422, {"detail": [{"loc": ["body"], "msg": "JSON illisible"}]}
    image = payload.get("image_b64") if isinstance(payload, dict) else None
    if not isinstance(image, str) or len(image) < MIN_IMAGE_CHARS:
        return 422, {"detail": [{"loc": ["body", "image_b64"], "msg": "champ requis"}]}
    width, height = image_size(image)
    prompts = payload.get("prompts")
    prompts = prompts if isinstance(prompts, list) else None
    count = 2 + len(image) % 3  # 2 à 4 objets, stable pour une même entrée
    return 200, {
        "width": width,
        "height": height,
        "mask_encoding": "png",
        "count": count,
        "objects": objects_of(width, height, prompts, count, labels),
    }


class MockSamHandler(BaseHTTPRequestHandler):
    """Sert /health et /segment, journalise chaque appel, ne garde aucune image."""

    protocol_version = "HTTP/1.1"
    server_version = "mock-sam/1.0"
    default_labels = DEFAULT_LABELS

    def log_message(self, fmt, *args):  # le journal maison remplace celui de http.server
        return

    def do_GET(self):
        path = urlsplit(self.path).path
        if path != "/health":
            self.send_json(404, {"detail": "chemin inconnu"})
            return
        self.send_json(200, {"ok": True, "model": "sam3.1-mock", "device": "cpu",
                             "gpu": "none (mock local, aucune carte allumée)", "region": "local"})
        self.journal("GET", path, 0, 0)

    def do_POST(self):
        path = urlsplit(self.path).path
        if path != "/segment":
            self.send_json(404, {"detail": "chemin inconnu"})
            return
        raw = self.read_body()
        started = time.perf_counter()
        status, payload = segment_response(raw, self.default_labels)
        self.send_json(status, payload)
        self.journal("POST", path, len(raw), payload.get("count", 0), started)

    def read_body(self) -> bytes:
        """Corps de la requête, borné : un client fautif ne mange pas la mémoire."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return b""
        return self.rfile.read(max(0, min(length, 4 * 1024 * 1024)))

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def journal(self, method: str, path: str, size: int, count: int,
                started: float | None = None) -> None:
        """Une ligne par requête : horodatage, taille reçue, nombre de masques, durée."""
        elapsed = (time.perf_counter() - started) * 1000 if started is not None else 0.0
        print(f"{time.strftime('%Y-%m-%dT%H:%M:%S%z')} {method} {path} "
              f"{size} octets {count} masques {elapsed:.1f} ms", flush=True)


class PoolHTTPServer(ThreadingHTTPServer):
    """Threads bornés (8) : des bancs déterministes même en rafale."""

    daemon_threads = True

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="mock-sam")

    def process_request(self, request, client_address):
        self._pool.submit(self.process_request_thread, request, client_address)

    def server_close(self):
        super().server_close()
        self._pool.shutdown(wait=False)


def announce(host: str, port: int) -> None:
    """Ligne de démarrage lisible par les scripts (et par un humain)."""
    print(f"mock-sam: écoute sur http://{host}:{port} "
          f"(contrat /health + /segment, aucune GPU)", flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Faux serveur SAM 3.1 local (sans GPU).")
    parser.add_argument("--host", default="127.0.0.1", help="interface (défaut 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8099, help="port (0 = libre, défaut 8099)")
    parser.add_argument("--labels", default="", help="libellés par défaut, séparés par des virgules")
    args = parser.parse_args(argv)
    if args.labels:
        MockSamHandler.default_labels = tuple(filter(None, args.labels.split(",")))

    server = PoolHTTPServer((args.host, args.port), MockSamHandler)
    announce(args.host, server.server_address[1])
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())