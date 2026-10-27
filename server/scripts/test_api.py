#!/usr/bin/env python3
"""Test E2E du backend sam3-vision (stdlib uniquement, zéro dépendance).

Exemples :
    python3 scripts/test_api.py https://xxxx.modal.run
    API_URL=https://xxxx.modal.run python3 scripts/test_api.py
    python3 scripts/test_api.py --url ... --image photo.jpg --timeout 90
    python3 scripts/test_api.py --url ... --only health
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
import zlib

DEFAULT_TIMEOUT = 60.0
PNG_SIG = b"\x89PNG\r\n\x1a\n"
SAMPLE_URLS = (
    "https://raw.githubusercontent.com/facebookresearch/segment-anything/main/notebooks/images/truck.jpg",
    "https://raw.githubusercontent.com/facebookresearch/segment-anything-2/main/notebooks/images/truck.jpg",
)
ALL_TESTS = ("health", "segment", "segment-prompts", "analyze", "ask", "bad-image")


class Failure(Exception):
    """Échec d'un test, avec un message lisible."""


def _color(code: str, text: str) -> str:
    return text if not sys.stdout.isatty() else f"\033[{code}m{text}\033[0m"


class Report:
    """Compte les succès/échecs et imprime un rapport lisible."""

    def __init__(self) -> None:
        self.passed = 0
        self.failed: list[str] = []
        self.warnings: list[str] = []
        self.started = time.time()

    def ok(self, name: str, detail: str) -> None:
        self.passed += 1
        print(f"  {_color('32', 'PASS')} {name}" + (f" — {detail}" if detail else ""))

    def warn(self, name: str, detail: str) -> None:
        self.warnings.append(f"{name}: {detail}")
        print(f"  {_color('33', 'WARN')} {name} — {detail}")

    def fail(self, name: str, detail: str) -> None:
        self.failed.append(f"{name}: {detail}")
        print(f"  {_color('31', 'FAIL')} {name} — {detail}")

    def summary(self) -> int:
        elapsed = time.time() - self.started
        print("\n" + "=" * 62)
        print(f"RÉSULTAT : {self.passed} OK, {len(self.failed)} KO, "
              f"{len(self.warnings)} avertissement(s) — {elapsed:.1f}s")
        if self.warnings:
            print("Avertissements :")
            for item in self.warnings:
                print(f"  - {item}")
        if self.failed:
            print("Échecs :")
            for item in self.failed:
                print(f"  - {item}")
            print(_color("31", "ÉCHEC"))
            return 1
        print(_color("32", "SUCCÈS — contrat respecté"))
        return 0


def run_test(report: Report, name: str, fn) -> None:
    started = time.time()
    try:
        detail = fn() or ""
        report.ok(name, f"{detail} ({time.time() - started:.1f}s)")
    except Failure as exc:
        report.fail(name, str(exc))
    except Exception as exc:  # garde-fou : aucune exception non gérée
        report.fail(name, f"{type(exc).__name__}: {exc}")


def _require(cond: bool, msg: str) -> None:
    if not cond:
        raise Failure(msg)


def _is_num(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _snippet(body) -> str:
    text = json.dumps(body, ensure_ascii=False) if not isinstance(body, str) else body
    return text[:200]


def _parse_json(raw: bytes, target: str, strict: bool = True):
    if not raw:
        return ""
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except json.JSONDecodeError:
        text = raw.decode("utf-8", "replace").strip()
        if not strict:
            return text
        raise Failure(f"{target}: réponse non JSON ({text[:120]!r})") from None


def http_json(url: str, path: str, payload=None, timeout: float = DEFAULT_TIMEOUT,
              method: str | None = None):
    """Renvoie (status, corps). Lève Failure si le serveur est injoignable."""
    target = url.rstrip("/") + path
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(target, data=data, headers=headers,
                                     method=method or ("POST" if data else "GET"))
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return resp.status, _parse_json(resp.read(), f"{target} (HTTP {resp.status})")
    except urllib.error.HTTPError as exc:
        return exc.code, _parse_json(exc.read(), target, strict=False)
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", exc)
        raise Failure(f"impossible de joindre {target} ({reason}) — URL ou déploiement ?") from None
    except (TimeoutError, OSError) as exc:
        raise Failure(f"réseau/timeout ({timeout}s) sur {target} : {exc}") from None


def png_info(raw: bytes) -> tuple[int, int, int, int]:
    """(largeur, hauteur, profondeur, type_couleur) lus dans le chunk IHDR."""
    _require(raw[:8] == PNG_SIG, "masque : signature PNG absente")
    _require(len(raw) >= 33, "masque : PNG tronqué")
    length = struct.unpack(">I", raw[8:12])[0]
    _require(raw[12:16] == b"IHDR" and length == 13, "masque : chunk IHDR invalide")
    width, height, depth, color = struct.unpack(">IIBB", raw[16:26])
    return width, height, depth, color


def decode_mask(mask_b64: str, label: str) -> bytes:
    try:
        raw = base64.b64decode(mask_b64, validate=True)
    except Exception:
        raise Failure(f"{label} : mask_b64 n'est pas du base64 valide") from None
    _require(len(raw) > 0, f"{label} : masque vide")
    return raw


def validate_object(obj, index: int, width: int, height: int, where: str) -> None:
    label = f"{where}.objects[{index}]"
    _require(isinstance(obj, dict), f"{label} : objet non-dict")
    _require(isinstance(obj.get("label"), str) and obj["label"], f"{label}.label invalide")
    _require(_is_num(obj.get("score")), f"{label}.score doit être un nombre")
    box = obj.get("box")
    _require(isinstance(box, list) and len(box) == 4 and all(_is_num(v) for v in box),
             f"{label}.box doit être [x1,y1,x2,y2] numériques")
    x1, y1, x2, y2 = box
    _require(x1 <= x2 and y1 <= y2, f"{label}.box mal ordonnée : {box}")
    _require(x1 >= 0 and y1 >= 0, f"{label}.box négative : {box}")
    _require(x2 <= width and y2 <= height,
             f"{label}.box ({box}) hors image {width}x{height}")
    mask = obj.get("mask_b64")
    _require(isinstance(mask, str) and mask, f"{label}.mask_b64 absent")
    mw, mh, depth, color = png_info(decode_mask(mask, label))
    _require((mw, mh) == (width, height),
             f"{label}.mask_b64 fait {mw}x{mh}, attendu {width}x{height}")
    _require(depth == 8 and color == 0,
             f"{label}.mask_b64 doit être PNG 8 bits niveaux de gris "
             f"(depth={depth}, color_type={color})")


def validate_objects(body, where: str) -> tuple[int, int, int]:
    _require(isinstance(body, dict), f"{where} : la réponse n'est pas un objet JSON")
    width, height = body.get("width"), body.get("height")
    _require(isinstance(width, int) and not isinstance(width, bool) and width > 0,
             f"{where}.width invalide : {width!r}")
    _require(isinstance(height, int) and not isinstance(height, bool) and height > 0,
             f"{where}.height invalide : {height!r}")
    objects = body.get("objects")
    _require(isinstance(objects, list), f"{where}.objects doit être une liste")
    for index, obj in enumerate(objects):
        validate_object(obj, index, width, height, where)
    return width, height, len(objects)


# --------------------------------------------------------------------------- image


def fill_rect(buf: bytearray, w: int, h: int, x0: int, y0: int, x1: int, y1: int, rgb) -> None:
    for y in range(max(0, y0), min(h, y1)):
        start = y * w * 3
        for x in range(max(0, x0), min(w, x1)):
            i = start + x * 3
            buf[i:i + 3] = bytes(rgb)


def fill_circle(buf: bytearray, w: int, h: int, cx: int, cy: int, r: int, rgb) -> None:
    for y in range(max(0, cy - r), min(h, cy + r + 1)):
        dy = y - cy
        for x in range(max(0, cx - r), min(w, cx + r + 1)):
            if (x - cx) ** 2 + dy * dy <= r * r:
                i = (y * w + x) * 3
                buf[i:i + 3] = bytes(rgb)


def make_png(width: int = 512, height: int = 512) -> bytes:
    """Scène synthétique (ciel, sol, caisse, ballon, soleil) en PNG RGB."""
    buf = bytearray(bytes((235, 238, 242)) * (width * height))
    fill_rect(buf, width, height, 0, 0, width, height // 3, (120, 170, 225))
    fill_rect(buf, width, height, 0, int(height * 0.72), width, height, (95, 160, 90))
    fill_rect(buf, width, height, 60, 300, 210, 470, (200, 120, 70))
    fill_circle(buf, width, height, 350, 300, 70, (205, 60, 60))
    fill_circle(buf, width, height, 430, 190, 32, (250, 205, 60))
    rows = b"".join(b"\x00" + bytes(buf[y * width * 3:(y + 1) * width * 3])
                    for y in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (PNG_SIG + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def download_sample(timeout: float) -> bytes | None:
    for url in SAMPLE_URLS:
        try:
            with urllib.request.urlopen(url, timeout=min(timeout, 20.0)) as resp:
                raw = resp.read()
            if len(raw) > 1024:
                return raw
        except Exception:
            continue
    return None


def ensure_image(path: str | None, timeout: float) -> tuple[bytes, str]:
    if path:
        try:
            with open(path, "rb") as handle:
                raw = handle.read()
        except OSError as exc:
            sys.exit(f"ERREUR : image illisible « {path} » : {exc}")
        if not raw:
            sys.exit(f"ERREUR : image vide « {path} »")
        return raw, path
    raw = download_sample(timeout)
    if raw:
        return raw, "téléchargée (échantillon public)"
    return make_png(), "synthétique 512x512 (générée)"


# -------------------------------------------------------------------------- tests


def test_health(session) -> str:
    status, body = http_json(session.url, "/health", timeout=session.timeout)
    _require(status == 200, f"/health : HTTP {status} — {_snippet(body)}")
    _require(isinstance(body, dict), "/health : la réponse n'est pas un objet JSON")
    _require(body.get("ok") is True, f"/health.ok doit valoir true (reçu {body.get('ok')!r})")
    _require(isinstance(body.get("model"), str) and body["model"], "/health.model doit être une chaîne")
    _require(isinstance(body.get("device"), str) and body["device"], "/health.device doit être une chaîne")
    return f"model={body['model']} device={body['device']}"


def test_segment(session, prompts=None) -> str:
    where = "/segment" + (" (prompts)" if prompts else " (sans prompts)")
    status, body = http_json(session.url, "/segment",
                             {"image_b64": session.image_b64, "prompts": prompts},
                             session.timeout)
    _require(status == 200, f"{where} : HTTP {status} — {_snippet(body)}")
    width, height, count = validate_objects(body, where)
    if count == 0:
        session.report.warn(where, "aucun objet détecté (structure valide)")
    return f"{width}x{height}, {count} objet(s)"


def test_analyze(session) -> str:
    status, body = http_json(session.url, "/analyze",
                             {"image_b64": session.image_b64}, session.timeout)
    _require(status == 200, f"/analyze : HTTP {status} — {_snippet(body)}")
    _require(isinstance(body, dict), "/analyze : la réponse n'est pas un objet JSON")
    description = body.get("description")
    _require(isinstance(description, str) and description.strip(),
             "/analyze.description doit être une chaîne non vide")
    tags = body.get("tags")
    _require(isinstance(tags, list) and all(isinstance(t, str) for t in tags),
             "/analyze.tags doit être une liste de chaînes")
    width, height, count = validate_objects(body, "/analyze")
    return f"{width}x{height}, {count} objet(s), desc {len(description)} car., {len(tags)} tag(s)"


def test_ask(session) -> str:
    question = "Décris en une phrase ce que tu vois sur cette photo."
    status, body = http_json(session.url, "/ask",
                             {"image_b64": session.image_b64, "question": question,
                              "history": None}, session.timeout)
    _require(status == 200, f"/ask : HTTP {status} — {_snippet(body)}")
    _require(isinstance(body, dict), "/ask : la réponse n'est pas un objet JSON")
    answer = body.get("answer")
    _require(isinstance(answer, str) and answer.strip(), "/ask.answer doit être une chaîne non vide")
    history = [{"role": "user", "content": question},
               {"role": "assistant", "content": answer}]
    status, body = http_json(session.url, "/ask",
                             {"image_b64": session.image_b64,
                              "question": "Et combien d'objets au total ?",
                              "history": history}, session.timeout)
    _require(status == 200, f"/ask (avec history) : HTTP {status} — {_snippet(body)}")
    _require(isinstance(body, dict) and isinstance(body.get("answer"), str) and body["answer"].strip(),
             "/ask (avec history) : answer manquante")
    return f"{len(answer)} car. + relance avec history OK"


def test_bad_image(session) -> str:
    status, body = http_json(session.url, "/segment",
                             {"image_b64": "cGFzIHVuZSBpbWFnZQ==", "prompts": None},
                             session.timeout)
    _require(status == 400, f"image illisible : attendu HTTP 400, reçu {status}")
    _require(isinstance(body, dict) and isinstance(body.get("detail"), str),
             "image illisible : corps {detail: ...} attendu")
    return "400 + detail OK"


# --------------------------------------------------------------------------- main


class Session:
    def __init__(self, url: str, timeout: float, image: bytes, source: str):
        self.url = url
        self.timeout = timeout
        self.image_b64 = base64.b64encode(image).decode("ascii")
        self.source = source
        self.report = Report()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Test E2E du backend sam3-vision (stdlib uniquement).")
    parser.add_argument("url", nargs="?", default=os.environ.get("API_URL"),
                        help="URL de base du backend (défaut : $API_URL)")
    parser.add_argument("--url", dest="url_opt", default=None, help="idem, explicite")
    parser.add_argument("--image", default=None, help="image de test (défaut : échantillon/générée)")
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("API_TIMEOUT", DEFAULT_TIMEOUT)),
                        help=f"timeout par requête en secondes (défaut {DEFAULT_TIMEOUT:.0f})")
    parser.add_argument("--only", default="all",
                        help="tests à lancer, séparés par des virgules "
                             f"({', '.join(ALL_TESTS)}) ou all")
    args = parser.parse_args(argv)
    args.url = args.url_opt or args.url
    if not args.url:
        parser.error("URL de base manquante : argument positionnel, --url ou $API_URL")
    if not args.url.startswith(("http://", "https://")):
        parser.error(f"URL invalide : {args.url!r} (attendu http(s)://...)")
    return args


def selected_tests(only: str) -> list[str]:
    if only.strip().lower() in ("all", ""):
        return list(ALL_TESTS)
    chosen = [item.strip().lower() for item in only.split(",") if item.strip()]
    unknown = [item for item in chosen if item not in ALL_TESTS]
    if unknown:
        sys.exit(f"ERREUR : test(s) inconnu(s) {unknown} — choix : {', '.join(ALL_TESTS)}, all")
    return chosen


def main(argv=None) -> int:
    args = parse_args(argv)
    tests = selected_tests(args.only)
    needs_image = any(test != "health" for test in tests)
    image, source = ensure_image(args.image, args.timeout) if needs_image else (b"", "non requise")
    session = Session(args.url.rstrip("/"), args.timeout, image, source)
    report = session.report

    print(f"Backend  : {session.url}")
    if needs_image:
        print(f"Image    : {source} ({len(image)} octets, base64 {len(session.image_b64)} car.)")
    print(f"Timeout  : {args.timeout:.0f}s par requête")
    print(f"Tests    : {', '.join(tests)}\n")

    steps = {
        "health": lambda: test_health(session),
        "segment": lambda: test_segment(session, None),
        "segment-prompts": lambda: test_segment(session, ["personne", "voiture", "objet"]),
        "analyze": lambda: test_analyze(session),
        "ask": lambda: test_ask(session),
        "bad-image": lambda: test_bad_image(session),
    }
    labels = {
        "health": "GET /health",
        "segment": "POST /segment (sans prompts)",
        "segment-prompts": "POST /segment (avec prompts)",
        "analyze": "POST /analyze",
        "ask": "POST /ask (+ history)",
        "bad-image": "POST /segment (image invalide → 400)",
    }
    for key in tests:
        run_test(report, labels[key], steps[key])
    return report.summary()


if __name__ == "__main__":
    sys.exit(main())