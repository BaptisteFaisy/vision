"""Tests du conteneur de PROD (``live_app_fast``) : négociation, chronos, ordonnancement.

Le GPU est absent : le pipeline (``_live_sync``) est remplacé par une doublure, et les
étages de ``fast_core`` par des sondes — on vérifie **l'ordre** et **ce qui tient sous le
verrou GPU**, pas les perfs du modèle. ``scripts/test_units.py`` fournit la plomberie.
"""

from __future__ import annotations

import asyncio
import json
import threading
import time as _time
from types import SimpleNamespace

import numpy as np


class _Tensor:
    """Tensor factice : ne sert qu'à ``detection_entries`` (``.float().cpu().numpy()``)."""

    def __init__(self, array) -> None:
        self._array = np.asarray(array)

    def float(self):
        return self

    def cpu(self):
        return self

    def numpy(self):
        return self._array


def _detection(count: int, work_size=(8, 8)):
    """Détection factice : ``count`` boîtes/points/masques, scores strictement décroissants."""
    scores = np.linspace(0.9, 0.1, count)
    boxes = np.tile(np.array([0.0, 0.0, 4.0, 4.0]), (count, 1))
    masks = np.zeros((count, work_size[1], work_size[0]), dtype=np.uint8)
    masks[:, 1, 1] = 1
    return {"boxes": _Tensor(boxes), "scores": _Tensor(scores), "masks": _Tensor(masks)}


def test_detection_entries(segmentation) -> None:
    """Une détection → objets du contrat, masque brut, borné aux ``keep`` meilleurs scores."""
    from test_units import check

    entries = segmentation.detection_entries(_detection(30), "chaise", (8, 8), (16, 16), keep=12)
    check("detection_entries: cap au nombre demandé", len(entries) == 12, str(len(entries)))
    check("detection_entries: scores décroissants",
          all(a["score"] >= b["score"] for a, b in zip(entries, entries[1:])))
    check("detection_entries: masque laissé non encodé",
          isinstance(entries[0]["mask"], np.ndarray) and "mask_b64" not in entries[0])
    check("detection_entries: label = prompt saisi", entries[0]["label"] == "chaise")
    check("detection_entries: box ramenée à la taille d'origine",
          entries[0]["box"] == [0.0, 0.0, 8.0, 8.0], str(entries[0]["box"]))
    check("detection_entries: keep=0 → aucun objet",
          segmentation.detection_entries(_detection(4), "car", (8, 8), (8, 8), keep=0) == [])


def _scored(count: int, scores):
    """Détection factice dont les scores sont imposés (pour tester les égalités)."""
    boxes = np.tile(np.array([0.0, 0.0, 4.0, 4.0]), (count, 1))
    masks = np.zeros((count, 8, 8), dtype=np.uint8)
    masks[:, 1, 1] = 1
    return {"boxes": _Tensor(boxes), "scores": _Tensor(np.array(scores)),
            "masks": _Tensor(masks)}


def test_cap_equivalence(segmentation) -> None:
    """Cap par concept puis tri global = tri global complet puis troncature (égalités comprises)."""
    from test_units import check

    scores = [0.42, 0.91, 0.42, 0.13, 0.77, 0.91, 0.05, 0.42, 0.63, 0.30]
    concepts = ["chaise", "person", "table"]
    cap, keep = 5, 4
    limited, naive = [], []
    for label in concepts:
        detection = _scored(len(scores), scores)
        limited.extend(segmentation.detection_entries(detection, label, (8, 8), (8, 8), keep))
        naive.extend(segmentation.detection_entries(detection, label, (8, 8), (8, 8), 10_000))
    small = segmentation.finalize(limited, (8, 8), cap, "none")
    full = segmentation.finalize(naive, (8, 8), cap, "none")
    check("cap par concept: sélection identique au tri complet",
          [(item["label"], item["score"]) for item in small["objects"]]
          == [(item["label"], item["score"]) for item in full["objects"]],
          str(small["objects"])[:160])
    check("cap par concept: moins d'entrées encodées", len(limited) < len(naive),
          f"{len(limited)} / {len(naive)}")
    # Top-5 attendu : cinq 0,91 (le 6e du même score tombe à 0,77), égalités dans l'ordre
    # de détection puis d'ordre des concepts — c'est exactement le tri stable d'avant.
    check("cap par concept: égalités départagées comme le tri global",
          [(item["label"], item["score"]) for item in small["objects"]]
          == [("chaise", 0.91), ("chaise", 0.91), ("person", 0.91), ("person", 0.91),
              ("table", 0.91)],
          str([(i["label"], i["score"]) for i in small["objects"]]))


def test_timing_gate(fast_core) -> None:
    """``FAST_TIMING`` : levier éteint ⇒ pas de ``timings_ms`` (jamais une carte vide)."""
    from test_units import check

    was = fast_core.TIMING
    try:
        fast_core.TIMING = False
        off = fast_core._decorate({}, {"objects": []}, None, True, {"vision_ms": 31.0})
        fast_core.TIMING = True
        on = fast_core._decorate({}, {"objects": []}, None, True, {"vision_ms": 31.0})
    finally:
        fast_core.TIMING = was
    check("timing: levier éteint → champ absent", "timings_ms" not in off, str(sorted(off)))
    check("timing: levier allumé → postes arrondis", on.get("timings_ms") == {"vision_ms": 31.0},
          str(on.get("timings_ms")))
    check("timing: marqueurs internes d'edge posés",
          on["_edge_ok"] is True and "_edge_applied" in on)


def _fake_stages(fast_core, lock: threading.Lock):
    """Les quatre étages de ``run_frame`` en sondes : chaque sonde dit si le verrou est pris.

    Une sonde qui **réussit** à prendre le verrou est hors verrou ; une sonde qui échoue est
    sous verrou. C'est la seule chose qu'on veut prouver ici : le CPU (préprocess, encodage
    des masques) ne doit jamais tenir la carte.
    """
    calls: list = []

    def probe(name: str) -> None:
        free = lock.acquire(blocking=False)
        if free:
            lock.release()
        calls.append((name, not free))

    def preprocess(_ctx, _image, _work_edge):
        probe("preprocess")
        return SimpleNamespace(size=(64, 64)), "pixels"

    def vision(_ctx, _pixels, _timers):
        probe("vision")
        return "vision"

    def collect(_ctx, _vision, prompts, _concepts, _work, _size, _batch, _keep, _timers):
        probe("collect")
        return [{"label": label, "score": 0.5, "box": [0.0, 0.0, 1.0, 1.0],
                 "mask": np.zeros((4, 4), dtype=np.uint8)} for label in prompts]

    def payload(_entries, _size, _max, mask_encoding, _timers, **_knobs):
        probe("payload")
        applied = mask_encoding or "png"  # le vrai _payload passe par check_mask_encoding
        calls.append(("mask_encoding", mask_encoding))
        return {"width": 64, "height": 64, "objects": [], "mask_encoding": applied}

    fast_core._preprocess, fast_core._vision = preprocess, vision
    fast_core._collect, fast_core._payload = collect, payload
    return calls


def test_run_frame_order(fast_core) -> None:
    """Le verrou GPU ne couvre que vision + concepts ; préprocess et masques restent dehors."""
    from test_units import check

    lock = threading.Lock()
    saved = (fast_core._preprocess, fast_core._vision, fast_core._collect, fast_core._payload)
    try:
        calls = _fake_stages(fast_core, lock)
        timers: dict = {}
        result = fast_core.run_frame({"edge_applied": 518}, SimpleNamespace(size=(64, 64)),
                                     ["chaise", "chair"], 512, 12, True,
                                     mask_encoding="bits", gpu_lock=lock, timers=timers)
    finally:
        (fast_core._preprocess, fast_core._vision, fast_core._collect,
         fast_core._payload) = saved
    stages = [name for name, _ in calls if name != "mask_encoding"]
    held = {name: under_lock for name, under_lock in calls if name != "mask_encoding"}
    check("run_frame: ordre des étages",
          stages == ["preprocess", "vision", "collect", "payload"], str(stages))
    check("run_frame: préprocess HORS verrou GPU", held.get("preprocess") is False, str(held))
    check("run_frame: vision + concepts SOUS verrou GPU",
          held.get("vision") and held.get("collect"), str(held))
    check("run_frame: encodage des masques HORS verrou GPU",
          held.get("payload") is False, str(held))
    check("run_frame: mask_encoding transmis à l'encodage",
          calls[-1] == ("mask_encoding", "bits"), str(calls[-1]))
    check("run_frame: attente du verrou mesurée (queue_ms)", "queue_ms" in timers,
          str(sorted(timers)))
    check("run_frame: charge utile du contrat",
          set(result) >= {"width", "height", "objects", "mask_encoding"}, str(sorted(result)))


def test_run_frame_no_lock(fast_core) -> None:
    """``segment_frame`` (chemin historique) : fonctionne sans verrou, défaut png."""
    from test_units import check

    lock = threading.Lock()
    saved = (fast_core._preprocess, fast_core._vision, fast_core._collect, fast_core._payload)
    try:
        _fake_stages(fast_core, lock)
        result = fast_core.segment_frame({"edge_applied": 518}, SimpleNamespace(size=(64, 64)),
                                         ["person"], 512, 12, False)
    finally:
        (fast_core._preprocess, fast_core._vision, fast_core._collect,
         fast_core._payload) = saved
    check("segment_frame: servi sans verrou explicite", result["objects"] == [], str(result)[:80])
    check("segment_frame: client muet → png (défaut historique)",
          result["mask_encoding"] == "png", result["mask_encoding"])


def test_gpu_contention(fast_core) -> None:
    """Deux entrées en vol : celle qui attend la carte paie ``queue_ms``, pas le CPU."""
    from test_units import check

    lock = threading.Lock()
    waiting: dict = {}
    idle: dict = {}

    def second_input() -> None:
        with fast_core._locked(lock, waiting):
            pass

    lock.acquire()                      # la frame N tient le GPU
    thread = threading.Thread(target=second_input)
    thread.start()
    _time.sleep(0.05)                   # N travaille, N+1 attend
    lock.release()
    thread.join(2.0)
    check("contention: la 2e entrée attend bien >= 30 ms",
          waiting.get("queue_ms", 0.0) >= 30.0, str(waiting))
    with fast_core._locked(lock, idle):
        pass
    check("verrou libre: queue_ms quasi nul", idle.get("queue_ms", 99.0) < 5.0, str(idle))
    unlocked: dict = {}
    with fast_core._locked(None, unlocked):
        pass
    check("sans verrou: rien n'est mesuré dans queue_ms", "queue_ms" not in unlocked, str(unlocked))


class _StubProcessor:
    """Préprocesseur factice : ``processor(images=work)`` rend un dict avec ``pixel_values``."""

    def __call__(self, images=None, return_tensors=None, **_kwargs):
        return {"pixel_values": SimpleNamespace(shape=getattr(images, "size", None))}


def test_run_frame_end_to_end(fast_core) -> None:
    """Vraie pipeline hors GPU : étages réels, masque encodé selon ``mask_encoding``."""
    from PIL import Image

    from imaging import decode_mask_bits_b64
    from test_units import check

    image = Image.new("RGB", (64, 32), (10, 200, 30))
    mask = np.zeros((32, 64), dtype=np.uint8)
    mask[4:20, 8:40] = 1
    entries = [{"label": label, "score": 0.9 - 0.1 * index, "box": [8.0, 4.0, 40.0, 20.0],
                 "mask": mask} for index, label in enumerate(["chaise", "person"])]
    ctx = {"edge_applied": 518, "processor": _StubProcessor(), "device": "cpu", "dtype": None}
    saved = (fast_core._vision, fast_core._collect)
    try:
        fast_core._vision = lambda _ctx, _pixels, _timers: "vision"
        fast_core._collect = lambda _c, _v, _p, _co, _w, _s, _b, _k, _t: list(entries)
        bits = fast_core.run_frame(ctx, image, ["chaise", "chair"], 512, 12, True,
                                   mask_encoding="bits")
        none = fast_core.run_frame(ctx, image, ["chaise"], 512, 12, True, mask_encoding="none")
        quiet = fast_core.run_frame(ctx, image, ["chaise"], 512, 12, True)
    finally:
        fast_core._vision, fast_core._collect = saved
    check("run_frame: masque bits décodable en M1",
          bits["mask_encoding"] == "bits"
          and decode_mask_bits_b64(bits["objects"][0]["mask_b64"]).shape == (32, 64),
          str(bits)[:120])
    check("run_frame: none ⇒ masque vide mais boîte conservée",
          none["objects"][0]["mask_b64"] == "" and none["objects"][0]["box"] == [8.0, 4.0, 40.0, 20.0],
          str(none["objects"][0])[:120])
    check("run_frame: client muet ⇒ png", quiet["mask_encoding"] == "png", quiet["mask_encoding"])
    # La dédup des alias se fait dans normalize_prompts (testée séparément) : ici run_frame
    # reçoit déjà des libellés distincts et doit en rendre un objet par libellé.
    check("run_frame: un objet par libellé reçu", len(bits["objects"]) == 2,
          str(len(bits["objects"])))


def _pipeline(captured: dict, timings=None):
    """Doublure de ``_live_sync`` à la signature courante (7 paramètres)."""
    def run(image_b64, prompts, max_objects, work_edge, batch_prompts=None, edge=None,
            mask_encoding=None):
        captured.update(mask_encoding=mask_encoding, prompts=prompts, max_objects=max_objects)
        result = {"width": 64, "height": 40, "elapsed_ms": 12, "objects": [],
                  "mask_encoding": mask_encoding or "png"}
        if timings is not None:
            result["timings_ms"] = timings
        return result
    return run


class _Legacy:
    """Doublure d'avant le portage : six paramètres, pas de ``mask_encoding``."""

    def __init__(self) -> None:
        self.calls = 0

    def __call__(self, image_b64, prompts, max_objects, work_edge, batch_prompts=None,
                 edge=None):
        self.calls += 1
        return {"width": 64, "height": 40, "elapsed_ms": 12, "objects": []}


def test_live_fast_contract(live_app_fast) -> None:
    """``POST /live`` transmet ``mask_encoding``, refuse l'inconnu, émet ``Server-Timing``."""
    from test_units import _live_service, asgi_call, check, sample_png

    png = sample_png()
    service, api = _live_service(live_app_fast)
    captured: dict = {}
    service._live_sync = _pipeline(captured)

    status, payload = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "prompts": ["chaise"], "mask_encoding": "bits"}).encode()))
    check("/live: mask_encoding transmis au pipeline",
          status == 200 and captured.get("mask_encoding") == "bits", str(captured))
    check("/live: représentation appliquée renvoyée", payload.get("mask_encoding") == "bits",
          str(payload)[:120])

    captured.clear()
    status, _ = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "prompts": ["chaise"]}).encode()))
    check("/live: client muet → None (défaut serveur png)",
          status == 200 and captured.get("mask_encoding") is None, str(captured))

    status, _ = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "mask_encoding": "gif"}).encode()))
    check("/live: encoding inconnu → 400", status == 400, str(status))

    headers: dict = {}
    service._live_sync = _pipeline({}, timings={"decode_ms": 0.7, "vision_ms": 31.0})
    status, payload = asyncio.run(asgi_call(api, "POST", "/live",
                                           json.dumps({"image_b64": png}).encode(),
                                           headers_out=headers))
    check("/live: en-tête Server-Timing présent",
          headers.get("server-timing") == "decode_ms;dur=0.7, vision_ms;dur=31.0",
          str(headers.get("server-timing")))
    check("/live: corps identique sous l'en-tête",
          status == 200 and payload.get("timings_ms") == {"decode_ms": 0.7, "vision_ms": 31.0},
          str(payload)[:120])

    headers.clear()
    service._live_sync = _pipeline({})
    asyncio.run(asgi_call(api, "POST", "/live", json.dumps({"image_b64": png}).encode(),
                          headers_out=headers))
    check("/live: levier éteint → aucun en-tête", "server-timing" not in headers, str(headers))


def test_live_fast_binary(live_app_fast) -> None:
    """``POST /live`` binaire : JPEG brut + knobs en query, même réponse que le JSON."""
    import base64

    from test_units import _live_service, asgi_call, check, sample_png

    service, api = _live_service(live_app_fast)
    captured: dict = {}

    def from_image(image, prompts, max_objects, work_edge, batch_prompts, edge,
                   mask_encoding, mask_format, mask_edge, timers, started):
        captured.update(size=image.size, prompts=prompts, max_objects=max_objects,
                        work_edge=work_edge, mask_encoding=mask_encoding,
                        mask_format=mask_format, mask_edge=mask_edge)
        return {"width": 64, "height": 40, "elapsed_ms": 12, "objects": [],
                "mask_encoding": mask_encoding or "png"}

    service._live_from_image = from_image
    raw = base64.b64decode(sample_png())
    status, payload = asyncio.run(asgi_call(
        api, "POST", "/live", raw, content_type="application/octet-stream",
        query_string=b"prompts=personne&prompts=porte&work_edge=640&max_objects=5"
                     b"&mask_encoding=bits&mask_format=rle&mask_edge=256"))
    check("/live binaire: 200 + réponse du pipeline",
          status == 200 and payload.get("elapsed_ms") == 12, f"{status} {payload!r}"[:120])
    check("/live binaire: image décodée (64x40)", captured.get("size") == (64, 40),
          str(captured.get("size")))
    check("/live binaire: knobs query transmis",
          captured.get("prompts") == ["personne", "porte"]
          and captured.get("work_edge") == 640 and captured.get("max_objects") == 5
          and captured.get("mask_encoding") == "bits"
          and captured.get("mask_format") == "rle" and captured.get("mask_edge") == 256,
          str(captured))
    status, _ = asyncio.run(asgi_call(api, "POST", "/live", b"pas une image",
                                      content_type="application/octet-stream"))
    check("/live binaire: octets non image → 400", status == 400, str(status))
    status, _ = asyncio.run(asgi_call(api, "POST", "/live", b"",
                                      content_type="application/octet-stream"))
    check("/live binaire: corps vide → 400", status == 400, str(status))


def test_live_fast_ws(live_app_fast) -> None:
    """/live/ws négocie comme /live et reste compatible avec une doublure à six args."""
    from live_ws import parse_frame

    from test_units import _live_service, asgi_call, check, sample_png

    png = sample_png()
    service, api = _live_service(live_app_fast)
    parsed = parse_frame(json.dumps({"id": 3, "image_b64": "x" * 300,
                                     "mask_encoding": "bits"}))
    check("WS: mask_encoding analysé depuis le message",
          parsed["error"] is None and parsed["request"].mask_encoding == "bits",
          str(parsed)[:120])
    check("WS: mask_encoding absent → None",
          parse_frame(json.dumps({"id": 4, "image_b64": "x" * 300}))[
              "request"].mask_encoding is None)

    legacy = _Legacy()
    service._live_sync = legacy
    check("signature: doublure à six paramètres refusée",
          live_app_fast.accepts_mask_encoding(legacy) is False)
    status, payload = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "mask_encoding": "bits"}).encode()))
    check("/live: doublure ancienne toujours servie",
          status == 200 and legacy.calls == 1 and payload.get("objects") == [],
          f"{status} {legacy.calls}")
