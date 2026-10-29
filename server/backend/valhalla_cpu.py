"""Modal CPU backend: Valhalla pedestrian routing on IdF tiles.

HTTP contract: GET /health, POST /route (Valhalla request JSON in, trip JSON
out), GET /signals (traffic lights in a bbox, static OSM extract).
CPU-only: no GPU, no secrets, scales to zero (~0 idle cost).
Tiles live on Modal Volume ``valhalla-idf-tiles`` (/vol).
Only ``modal`` is imported at module level, so this file stays importable
locally (py_compile) without pyvalhalla/fastapi installed.
"""

import asyncio
import pathlib

import modal

# Feux OSM : marge autour du cadre demandé (degrés ≈ 110 m).
SIGNALS_MARGIN_DEG = 0.001
SIGNALS_MAX_NODES = 2000
# Extract statique highway=traffic_signals (IdF, 23 170 points) : Overpass
# (direct + 4 miroirs) est injoignable depuis le conteneur — les feux ne
# bougent quasiment jamais, autant les embarquer (471 ko dans l'image).
SIGNALS_JSON_REMOTE = "/signals.json"

APP_NAME = "valhalla-idf-cpu"
VOLUME_NAME = "valhalla-idf-tiles"
VOL_DIR = "/vol"
TILE_DIR = f"{VOL_DIR}/tiles"
ADMIN_DB = f"{VOL_DIR}/admin.sqlite"

image = (
    modal.Image.debian_slim(python_version="3.13")
    .pip_install(
        "pyvalhalla==3.9.1",  # same version that built the tiles (r630)
        "fastapi[standard]==0.162.0",  # repo pin (server/backend/requirements.txt)
    )
    .add_local_file(
        str(pathlib.Path(__file__).parent / "traffic_signals_idf.json"),
        SIGNALS_JSON_REMOTE,
    )
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
        self._signals = _load_signals()

    @modal.asgi_app()
    def web(self):
        """FastAPI application: /health + /route + /signals."""
        import json

        from fastapi import FastAPI, HTTPException, Request
        from fastapi.responses import JSONResponse

        api = FastAPI(title=APP_NAME, version="1.2.0")

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
            """Feux OSM dans le cadre (margé), depuis l'extract embarqué.

            Toujours 200 : ``{"signals": [...], "cached": bool}`` — le filtre
            est instantané (23 170 points en mémoire), aucun cache.
            """
            points = _filter_signals(self._signals, s, w, n, e)
            return {"signals": points, "cached": False}

        return api


def _load_signals() -> list:
    """Extract embarqué ([[lat, lon], …]) : [] si le fichier manque."""
    import json

    try:
        with open(SIGNALS_JSON_REMOTE, encoding="utf-8") as handle:
            points = json.load(handle)
    except Exception as exc:
        print(f"[signals] extract manquant : {exc}")
        return []
    print(f"[signals] {len(points)} feux chargés")
    return points


def _filter_signals(all_points: list, s: float, w: float, n: float, e: float) -> list:
    """Points dans le cadre margé, plafonnés, au format ``{"lat", "lon"}``."""
    margin = SIGNALS_MARGIN_DEG
    south, west, north, east = s - margin, w - margin, n + margin, e + margin
    found = []
    for point in all_points:
        lat, lon = point[0], point[1]
        if south <= lat <= north and west <= lon <= east:
            found.append({"lat": lat, "lon": lon})
            if len(found) >= SIGNALS_MAX_NODES:
                break
    return found

