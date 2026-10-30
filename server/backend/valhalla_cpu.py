"""Modal CPU backend: Valhalla pedestrian routing on IdF tiles.

HTTP contract: GET /health, POST /route (Valhalla request JSON in, trip JSON
out), GET /signals (traffic lights in a bbox, static OSM extract).

Safety: pedestrian /route answers are repaired so street crossings happen at
traffic lights when a small detour allows it (see _repair_crossings).
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
# Traversées sûres : manœuvres "tout droit" (Valhalla types 7 = Becomes,
# 8 = Continue) sur passage marqué OU près d'un carrefour routier (v2),
# sans feu à 40 m → reroutage via le feu le plus proche (150 m max),
# détours bornés (2 réparations, +20 % max).
CROSSING_TYPES = (7, 8)
SIGNAL_NEAR_M = 40.0
JUNCTION_NEAR_M = 30.0
REPAIR_SEARCH_M = 150.0
MAX_REPAIRS = 2
DETOUR_FACTOR = 1.20
# Grille spatiale des carrefours (493 406 points : balayage linéaire trop
# lent) : cellules 0,01° (~1,1 km, IdF-only), requêtes en 3×3 cellules.
GRID_CELL_DEG = 0.01
# Mots d'infrastructure piétonne (Valhalla anglophone) : une manœuvre qui
# n'en contient aucun ET qui touche un carrefour traverse une chaussée
# (v2) ; "crosswalk"/"crossing" couvrent les passages marqués (v1).
CROSSING_WORDS = frozenset({"crosswalk", "crosswalks", "crossing", "crossings"})
FOOTWAY_WORDS = frozenset({
    "walkway", "walkways", "footway", "footways", "sidewalk", "sidewalks",
    "footpath", "footpaths", "path", "paths", "trail", "trails", "steps",
    "stairs", "staircase", "staircases", "cycleway", "cycleways", "bikeway",
    "bikeways", "bridge", "bridges", "footbridge", "footbridges", "tunnel",
    "tunnels", "elevator", "elevators", "corridor", "corridors", "escalator",
    "escalators", "boardwalk", "boardwalks", "promenade", "promenades",
    "esplanade", "esplanades",
})
# Extract statique highway=traffic_signals (IdF, 23 170 points) : Overpass
# (direct + 4 miroirs) est injoignable depuis le conteneur — les feux ne
# bougent quasiment jamais, autant les embarquer (471 ko dans l'image).
SIGNALS_JSON_REMOTE = "/signals.json"
# Carrefours routiers IdF (nœuds partagés par ≥2 voies voitures) : même
# extract, même pipeline que les feux (v2 : détecter les vraies traversées
# de chaussée, pas les jonctions de sentiers).
JUNCTIONS_JSON_REMOTE = "/junctions.json"

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
    .add_local_file(
        str(pathlib.Path(__file__).parent / "road_junctions_idf.json"),
        JUNCTIONS_JSON_REMOTE,
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
    # Plafond piéton explicite (vol d'oiseau entre étapes) : 25 km observés
    # en prod — un Cergy→Paris pourtant dans les tuiles est rejeté ; 250 km
    # = défaut amont Valhalla, couvre l'IdF à pied.
    limits = cfg.setdefault("service_limits", {}).setdefault("pedestrian", {})
    limits["max_distance"] = 250000.0
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
        self._signals = _load_points(SIGNALS_JSON_REMOTE, "feux")
        self._junctions = _load_points(JUNCTIONS_JSON_REMOTE, "carrefours")
        self._jgrid = _build_grid(self._junctions)

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
                data = await asyncio.to_thread(
                    _route_with_repairs, self._actor, self._signals, self._jgrid, body
                )
            except Exception as exc:
                raise HTTPException(status_code=500, detail=str(exc)[:2000])
            return JSONResponse(content=data)

        @api.get("/signals")
        async def signals(s: float, w: float, n: float, e: float):
            """Feux OSM dans le cadre (margé), depuis l'extract embarqué.

            Toujours 200 : ``{"signals": [...], "cached": bool}`` — le filtre
            est instantané (23 170 points en mémoire), aucun cache.
            """
            points = _filter_signals(self._signals, s, w, n, e)
            return {"signals": points, "cached": False}

        return api


def _load_points(remote: str, label: str) -> list:
    """Extract embarqué ([[lat, lon], …]) : [] si le fichier manque."""
    import json

    try:
        with open(remote, encoding="utf-8") as handle:
            points = json.load(handle)
    except Exception as exc:
        print(f"[signals] extract {label} manquant : {exc}")
        return []
    print(f"[signals] {len(points)} {label} chargés")
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


def _route_with_repairs(actor, signals: list, jgrid: dict, body: dict) -> dict:
    """Itinéraire Valhalla, réparé (piéton uniquement, voir _repair_crossings).

    Lève si l'appel de base échoue (→ 500) ; une réparation qui échoue rend
    la route de base (sécurité = bonus, jamais de panne ajoutée).
    """
    import json

    base = json.loads(actor.route(json.dumps(body)))
    if body.get("costing") != "pedestrian" or not signals:
        return base
    try:
        return _repair_crossings(actor, signals, jgrid, body, base)
    except Exception as exc:
        print(f"[route] réparation annulée : {exc}")
        return base


def _repair_crossings(actor, signals: list, jgrid: dict, body: dict, base: dict) -> dict:
    """Traversées sans feu → reroutage via le feu proche (borné, accepté si
    moins de traversées dangereuses et au plus +20 % de temps)."""
    import json

    best = base
    best_time = _trip_time(base)
    locations = body.get("locations", [])
    if len(locations) < 2:
        return base
    for _ in range(MAX_REPAIRS):
        unsafe = _unsafe_crossings(best, signals, jgrid)
        if not unsafe:
            return best
        via = _nearest_point(signals, unsafe[0][0], unsafe[0][1], REPAIR_SEARCH_M)
        if via is None:
            return best
        attempt = dict(body)
        attempt["locations"] = (
            [locations[0]] + [{"lat": via[0], "lon": via[1], "type": "via"}] + [locations[-1]]
        )
        candidate = json.loads(actor.route(json.dumps(attempt)))
        if _trip_time(candidate) > best_time * DETOUR_FACTOR:
            return best
        if len(_unsafe_crossings(candidate, signals, jgrid)) >= len(unsafe):
            return best
        best, best_time = candidate, _trip_time(candidate)
        print(f"[route] traversée réparée via ({via[0]:.5f}, {via[1]:.5f})")
    return best


def _trip_time(data: dict) -> float:
    """Durée annoncée (s), infini si illisible (le garde-fou rejette)."""
    try:
        return float(data["trip"]["summary"]["time"])
    except (KeyError, TypeError, ValueError):
        return float("inf")


def _unsafe_crossings(trip_data: dict, signals: list, jgrid: dict) -> list:
    """Points des traversées de chaussée sans feu proche (v2).

    Candidat = manœuvre "tout droit" (types 7/8) : passage marqué
    ("crosswalk"/"crossing", v1) OU point près d'un carrefour routier sans
    mot d'infrastructure piétonne (v2 : "Continue.", "Continue on Rue X",
    "Continue on Place X" — mais pas "… on the walkway"). Les virages
    restent hors scope (coins de trottoir, pas de traversée).
    """
    try:
        legs = trip_data["trip"]["legs"]
    except (KeyError, TypeError):
        return []
    unsafe = []
    for leg in legs:
        try:
            shape = _decode_polyline6(leg.get("shape", ""))
        except Exception:
            continue
        for maneuver in leg.get("maneuvers", []):
            if maneuver.get("type") not in CROSSING_TYPES:
                continue
            index = maneuver.get("begin_shape_index", 0)
            if not isinstance(index, int) or index >= len(shape):
                continue
            lat, lon = shape[index]
            if not _is_crossing(maneuver.get("instruction"), lat, lon, jgrid):
                continue
            if _nearest_point(signals, lat, lon, SIGNAL_NEAR_M) is None:
                unsafe.append((lat, lon))
    return unsafe


def _is_crossing(instruction, lat: float, lon: float, jgrid: dict) -> bool:
    """Vrai si la manœuvre "tout droit" traverse une chaussée (v2)."""
    import re

    words = set(re.findall(r"[a-z]+", (instruction or "").lower()))
    if words & CROSSING_WORDS:
        return True
    if words & FOOTWAY_WORDS:
        return False
    return _nearest_point(_grid_near(jgrid, lat, lon), lat, lon, JUNCTION_NEAR_M) is not None


def _build_grid(points: list) -> dict:
    """Index spatial grossier : cellule 0,01° → points (IdF-only)."""
    grid: dict = {}
    for point in points:
        key = (int(point[0] / GRID_CELL_DEG), int(point[1] / GRID_CELL_DEG))
        grid.setdefault(key, []).append(point)
    return grid


def _grid_near(grid: dict, lat: float, lon: float) -> list:
    """Points des 3×3 cellules autour (couvre ±1,1 km ≫ 30 m)."""
    ilat, ilon = int(lat / GRID_CELL_DEG), int(lon / GRID_CELL_DEG)
    found = []
    for a in (ilat - 1, ilat, ilat + 1):
        for o in (ilon - 1, ilon, ilon + 1):
            found.extend(grid.get((a, o), ()))
    return found


def _meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance équirectangulaire (suffit à Paris, IdF-only)."""
    import math

    return math.hypot(
        (lat1 - lat2) * 111320.0,
        (lon1 - lon2) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2.0)),
    )


def _nearest_point(points: list, lat: float, lon: float, max_m: float):
    """Point [lat, lon] le plus proche (tuple), ou None au-delà de [max_m]."""
    best, best_m = None, max_m
    for point in points:
        dist = _meters(lat, lon, point[0], point[1])
        if dist <= best_m:
            best, best_m = (point[0], point[1]), dist
    return best


def _decode_polyline6(shape: str) -> list:
    """Tracé Valhalla (polyline précision 6) → [(lat, lon), …]."""
    points = []
    lat, lon, index = 0, 0, 0
    while index < len(shape):
        for axis in range(2):
            shift, result = 0, 0
            while True:
                byte = ord(shape[index]) - 63
                index += 1
                result |= (byte & 31) << shift
                shift += 5
                if byte < 32:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if axis == 0:
                lat += delta
            else:
                lon += delta
        points.append((lat / 1e6, lon / 1e6))
    return points

