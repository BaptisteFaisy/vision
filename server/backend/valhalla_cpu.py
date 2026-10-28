"""Modal CPU backend: Valhalla pedestrian routing on IdF tiles.

HTTP contract: GET /health, POST /route (Valhalla request JSON in, trip JSON out).
CPU-only: no GPU, no secrets, scales to zero (~0 idle cost).
Tiles live on Modal Volume ``valhalla-idf-tiles`` (/vol).
Only ``modal`` is imported at module level, so this file stays importable
locally (py_compile) without pyvalhalla/fastapi installed.
"""

import asyncio

import modal

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
        """FastAPI application: /health + /route."""
        import json

        from fastapi import FastAPI, HTTPException, Request
        from fastapi.responses import JSONResponse

        api = FastAPI(title=APP_NAME, version="1.0.0")

        @api.get("/health")
        async def health():
            return {"status": "ok", "tiles": "idf", "actions": ["route"]}

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

        return api
