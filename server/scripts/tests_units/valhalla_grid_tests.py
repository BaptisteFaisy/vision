"""Tests de l'index spatial des feux Valhalla : grille 0,01° == balayage complet.

Convention RUNNERS (``scripts/test_units.py``) : chaque fonction reçoit le
module ``valhalla_cpu`` importé avec stubs Modal. Stdlib uniquement, < 1 s.
"""

from __future__ import annotations

import json
from pathlib import Path


def _signals(valhalla):
    """Extract réel embarqué (23 170 feux IdF)."""
    path = Path(valhalla.__file__).parent / "traffic_signals_idf.json"
    return json.loads(path.read_text(encoding="utf-8"))


def test_grid_equivalence(valhalla) -> None:
    """_grid_near + _nearest_point == scan complet, sondes sur tout l'IdF."""
    from test_units import check

    signals = _signals(valhalla)
    grid = valhalla._build_grid(signals)
    mismatches = 0
    probed = 0
    for point in signals[::100]:
        lat, lon = point[0] + 0.0005, point[1] + 0.0005
        for radius in (valhalla.SIGNAL_NEAR_M, valhalla.REPAIR_SEARCH_M):
            full = valhalla._nearest_point(signals, lat, lon, radius)
            fast = valhalla._nearest_point(
                valhalla._grid_near(grid, lat, lon), lat, lon, radius
            )
            probed += 1
            mismatches += full != fast
    check("grille feux : parité scan complet", mismatches == 0, f"{mismatches}/{probed}")
    check("grille feux : extract chargé", len(signals) > 20_000, str(len(signals)))


def test_grid_borders(valhalla) -> None:
    """Bordures de cellules : le 3×3 ne perd aucun voisin (cas synthétique)."""
    from test_units import check

    cell = valhalla.GRID_CELL_DEG
    lat = 48.85 - (48.85 % cell) + cell - 0.0001
    signals = [[lat + 0.0002, 2.35]]
    grid = valhalla._build_grid(signals)
    fast = valhalla._nearest_point(valhalla._grid_near(grid, lat, 2.35), lat, 2.35, 150.0)
    check("grille feux : voisin trans-cellule trouvé", fast == (lat + 0.0002, 2.35), str(fast))


def test_unsafe_crossings_shape(valhalla) -> None:
    """_unsafe_crossings tourne sur grille : vide sans jambes, sans lever."""
    from test_units import check

    grid = valhalla._build_grid([[48.8566, 2.3522]])
    check("traversées : {} → []", valhalla._unsafe_crossings({}, grid, {}) == [])
    check(
        "traversées : jambes vides → []",
        valhalla._unsafe_crossings({"trip": {"legs": []}}, grid, {}) == [],
    )
