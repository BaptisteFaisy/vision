"""Vecteurs de contrat WS binaire M1, exécutés contre le parseur déployé.

``m1_ws_vectors.json`` (même dossier) est la source unique, partagée avec le
client Kotlin (``M1VectorsTest``) : chaque vecteur y porte sa trame hex, son
attente, et — pour les trames émises par le client — les paramètres de
``frameMessageBinary`` qui doivent la reproduire à l'octet près.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

VECTORS_PATH = Path(__file__).resolve().parent / "m1_ws_vectors.json"


def _parse_frame_bytes():
    """``parse_frame_bytes`` du fork déployé (``live_fast``, pur, sans GPU)."""
    from test_units import BACKEND_DIR

    live_fast = str(BACKEND_DIR / "live_fast")
    if live_fast not in sys.path:
        sys.path.insert(0, live_fast)
    from live_ws import parse_frame_bytes

    return parse_frame_bytes


def _check_vector(check, parse, vector: dict) -> None:
    """Un vecteur : trame hex → ``parse_frame_bytes`` → attente exacte."""
    name = vector["name"]
    result = parse(bytes.fromhex(vector["frame_hex"]))
    expected = vector["expect"]
    ok = result["error"] is None
    check(f"M1 vecteurs: {name} accepte/rejete", ok == expected["ok"], str(result)[:160])
    check(f"M1 vecteurs: {name} id", result["id"] == expected["id"], str(result)[:160])
    if expected["ok"]:
        check(f"M1 vecteurs: {name} JPEG intact",
              result["jpeg"] == bytes.fromhex(expected["jpeg_hex"]), name)
    else:
        check(f"M1 vecteurs: {name} statut", result["error"]["status"] == expected["error_status"],
              str(result)[:160])
        check(f"M1 vecteurs: {name} detail",
              result["error"]["detail"].startswith(expected["detail_prefix"]),
              str(result)[:160])


def test_m1_contract_vectors(_) -> None:
    """Les 16 vecteurs passent contre ``parse_frame_bytes`` (zéro GPU)."""
    from test_units import check

    parse = _parse_frame_bytes()
    try:
        doc = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        check("M1 vecteurs: JSON lisible", False, str(exc)[:160])
        return
    check("M1 vecteurs: version 1", doc.get("version") == 1, str(doc.get("version")))
    for vector in doc["vectors"]:
        _check_vector(check, parse, vector)
