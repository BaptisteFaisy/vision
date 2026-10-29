"""Modal CPU backend: Valhalla pedestrian routing on IdF tiles.

HTTP contract: GET /health, POST /route (Valhalla request JSON in, trip JSON
out), GET /signals (traffic lights in a bbox, proxied from OpenStreetMap).
CPU-only: no GPU, no secrets, scales to zero (~0 idle cost).
Tiles live on Modal Volume ``valhalla-idf-tiles`` (/vol).
Only ``modal`` is imported at module level, so this file stays importable
locally (py_compile) without pyvalhalla/fastapi installed.
"""

import asyncio
import time

import modal

# Feux OSM : marge autour du cadre demandé (degrés ≈ 110 m) + cache conteneur.
SIGNALS_MARGIN_DEG = 0.001
SIGNALS_CACHE_TTL_SECONDS = 24 * 3600
SIGNALS_EMPTY_TTL_SECONDS = 300
SIGNALS_MAX_NODES = 2000
OVERPASS_URL = "https://overpass-api.de/api/interpreter"
OVERPASS_TIMEOUT_SECONDS = 10
# Overpass exige un User-Agent descriptif (406 sans lui, même avec curl).
OVERPASS_USER_AGENT = "sam3-vision/1.0 (+https://github.com/BaptisteFaisy/vision)"

APP_NAME = "valhalla-idf-cpu"
VOLUME_NAME = "valhalla-idf-tiles"
VOL_DIR = "/vol"
TILE_DIR = f"{VOL_DIR}/tiles"
ADMIN_DB = f"{VOL_DIR}/admin.sqlite"

image = modal.Image.debian_slim(python_version="3.13").pip_install(
    "pyvalhalla==3.9.1",  # same version that built the tiles (r630)
    "fastapi[standard]==0.162.0",  # repo pin (server/backend/requirements.txt)
)

app = modal.App(APP_NAME)
vol = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)


def _config() -> dict:
    """Valhalla config, container side (same shape as r630 build-tiles.sh)."""
    from valhalla import get_config

    cfg = get_config(tile_extract="", tile_dir=TILE_DIR, verbose=False)
    mjolnir = cfg["mjolnir"]
    mjolnir["tile_extract"] = ""
    mjolnir["admin"] = ADMIN_DB
    mjolnir["timezone"] = ""
    mjolnir["landmarks"] = ""
    mjolnir["transit_dir"] = ""
    return cfg


@app.cls(
    image=image,
    volumes={VOL_DIR: vol},
    cpu=2.0,
    memory=4096,
    timeout=120,
    scaledown_window=60,
)
class ValhallaRouter:
    """CPU router: Actor loaded once per container, HTTP via FastAPI."""

    @modal.enter()
    def load(self) -> None:
        from valhalla import Actor

        self._actor = Actor(_config())

    @modal.asgi_app()
    def web(self):
        """FastAPI application: /health + /route + /signals."""
        import json

        from fastapi import FastAPI, HTTPException, Request
        from fastapi.responses import JSONResponse

        api = FastAPI(title=APP_NAME, version="1.1.0")
        cache: dict = {}

        @api.get("/health")
        async def health():
            return {"status": "ok", "tiles": "idf", "actions": ["route", "signals"]}

        @api.post("/route")
        async def route(request: Request):
            try:
                body = await request.json()
            except Exception:
                raise HTTPException(status_code=400, detail="invalid JSON body")
            try:
                out = await asyncio.to_thread(self._actor.route, json.dumps(body))
            except Exception as exc:
                raise HTTPException(status_code=500, detail=str(exc)[:2000])
            return JSONResponse(content=json.loads(out))

        @api.get("/signals")
        async def signals(s: float, w: float, n: float, e: float):
            """Feux OSM dans le cadre (margé), via Overpass + cache 24 h.

            Toujours 200 : ``{"signals": [...], "cached": bool}`` — liste vide
            si Overpass est injoignable (l'app dégrade : carte sans feux).
            """
            key = (round(s, 5), round(w, 5), round(n, 5), round(e, 5))
            hit = cache.get(key)
            if hit is not None:
                # Vides (Overpass en panne) : revalidés après 5 min, pas 24 h.
                ttl = SIGNALS_CACHE_TTL_SECONDS if hit[0] else SIGNALS_EMPTY_TTL_SECONDS
                if time.time() - hit[1] < ttl:
                    return {"signals": hit[0], "cached": True}
            points = await asyncio.to_thread(_fetch_signals, s, w, n, e)
            cache[key] = (points, time.time())
            return {"signals": points, "cached": False}

        return api


def _fetch_signals(s: float, w: float, n: float, e: float) -> list:
    """Interroge Overpass (nœuds highway=traffic_signals) : [] si échec."""
    import json
    import urllib.parse
    import urllib.request

    box = (
        f"{s - SIGNALS_MARGIN_DEG:.5f},{w - SIGNALS_MARGIN_DEG:.5f},"
        f"{n + SIGNALS_MARGIN_DEG:.5f},{e + SIGNALS_MARGIN_DEG:.5f}"
    )
    query = (
        "[out:json][timeout:10];"
        f'node["highway"="traffic_signals"]({box});out geom;'
    )
    url = OVERPASS_URL + "?data=" + urllib.parse.quote(query)
    request = urllib.request.Request(url, headers={"User-Agent": OVERPASS_USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=OVERPASS_TIMEOUT_SECONDS) as response:
            payload = json.load(response)
    except Exception as exc:
        print(f"[signals] overpass indisponible : {exc}")
        return []
    points = []
    for element in payload.get("elements", [])[:SIGNALS_MAX_NODES]:
        if element.get("type", "node") != "node":
            continue
        lat, lon = element.get("lat"), element.get("lon")
        if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
            points.append({"lat": lat, "lon": lon})
    return points
