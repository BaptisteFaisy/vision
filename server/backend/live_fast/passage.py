"""Passage suggéré par le serveur : le couloir est-il bouché par une personne ?

Chaque réponse ``/live`` (HTTP et WebSocket) porte ``passage`` : la décision
gauche/droite calculée **côté SAM**, sur les boîtes segmentées — le téléphone
n'y ajoute que ses distances (sténopé on-device) et le mot parlé. Un backend
déployé antérieur ne renvoie pas ce champ : le client rebascule alors sur sa
décision locale (``Guidance.decide``), sans changer de voix.

Règle (miroir documenté de ``Guidance`` côté client, adaptée aux pixels) :
une personne dont le centre horizontal est dans la bande centrale bouche le
couloir ; le côté conseillé est le moins encombré (moins de personnes, sinon
la plus proche la plus loin — hauteur de boîte, proxy de proximité —, sinon
l'opposé du décalage de l'obstacle, sinon la gauche). Le serveur ne connaît
ni focale ni distance métrique : les naines de moins de ``MIN_HEIGHT_RATIO``
de la hauteur d'image (≃ au-delà de 60 m au sténopé, miroir de
``Pinhole.MAX_METERS``) sont ignorées, et le décalage chiffré reste calculé
sur le téléphone.

Pur Python (stdlib + ``concepts``) : testé sans GPU ni poids par
``scripts/test_units.py`` (``live_fast_tests.test_passage``). Fichier vendored :
``backend/passage.py`` et ``backend/live_fast/passage.py`` sont byte-identiques
(``VENDORED_PARITY``) — Modal ne suit pas les imports hors de son dossier.
"""

from __future__ import annotations

from concepts import model_concept

# Bande centrale du couloir marché (normalisée) : miroir de
# ``PositionText.SIDE_EDGE`` (client) — les deux côtés doivent juger pareil.
SIDE_EDGE = 0.34

# Hauteur de boîte minimale (ratio de la hauteur d'image) : en deçà, la
# personne est trop loin pour boucher un pas (≃ 60 m au sténopé du client).
MIN_HEIGHT_RATIO = 0.02

# Personnes, sur le canonique de ``model_concept`` (FR traduit, anglais
# normalisé) : miroir de ``LateralNudgeTracker.PERSONS`` (client).
PERSONS = frozenset({
    "person", "persons", "people", "human", "humans",
    "man", "men", "woman", "women", "child", "children",
    "pedestrian", "pedestrians", "boy", "boys", "girl", "girls",
})


def is_person(label: object) -> bool:
    """Vrai si [label] désigne une personne (FR/EN, casse et accents tolérés)."""
    if not isinstance(label, str) or not label.strip():
        return False
    return model_concept(label) in PERSONS


def _box(item: object) -> list | None:
    """Boîte ``[x1, y1, x2, y2]`` finie de l'objet, ``None`` si inexploitable."""
    if not isinstance(item, dict):
        return None
    box = item.get("box")
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        return None
    try:
        coords = [float(value) for value in box]
    except (TypeError, ValueError):
        return None
    if any(value != value or value in (float("inf"), float("-inf")) for value in coords):
        return None
    return coords


def _persons(objects: object, width: int, height: int) -> list[dict]:
    """Personnes exploitables : centre normalisé + hauteur en pixels."""
    if not isinstance(objects, list) or width <= 0 or height <= 0:
        return []
    found = []
    for item in objects:
        if not isinstance(item, dict) or not is_person(item.get("label")):
            continue
        box = _box(item)
        if box is None:
            continue
        x1, y1, x2, y2 = box
        if y2 - y1 < MIN_HEIGHT_RATIO * height:
            continue
        found.append({
            "center_x": (min(x1, x2) + max(x1, x2)) / 2.0 / width,
            "height_px": max(0.0, y2 - y1),
            "label": item.get("label"),
            "box": [float(x1), float(y1), float(x2), float(y2)],
            "score": item.get("score"),
        })
    return found


def _freer_side(persons: list[dict], obstacle: dict) -> str:
    """Côté le moins encombré : effectif, proximité, décalage, gauche."""
    left = [p for p in persons if p["center_x"] < SIDE_EDGE]
    right = [p for p in persons if p["center_x"] > 1.0 - SIDE_EDGE]
    if len(left) != len(right):
        return "left" if len(left) < len(right) else "right"
    nearest_left = max([p["height_px"] for p in left], default=0.0)
    nearest_right = max([p["height_px"] for p in right], default=0.0)
    if nearest_left != nearest_right:
        return "left" if nearest_left < nearest_right else "right"
    if obstacle["center_x"] < 0.5:
        return "right"
    return "left"


def suggest_passage(objects: object, width: int, height: int) -> dict:
    """Passage conseillé depuis les objets SAM (boîtes en pixels image).

    Renvoie toujours ``{"blocked", "side", "label", "box", "score"}`` : ``side``
    vaut ``"left"``/``"right"`` quand c'est bouché, ``None`` sur voie libre
    (ainsi que ``label``/``box``/``score``, l'écho de l'obstacle). Dimensions
    invalides ou objets illisibles : voie libre — le serveur ne devine jamais
    un passage (le client garde sa décision locale en repli).
    """
    persons = _persons(objects, width, height)
    blocking = [p for p in persons if SIDE_EDGE <= p["center_x"] <= 1.0 - SIDE_EDGE]
    if not blocking:
        return {"blocked": False, "side": None, "label": None, "box": None, "score": None}
    obstacle = min(
        blocking,
        key=lambda p: (-p["height_px"], abs(p["center_x"] - 0.5), str(p["label"])),
    )
    return {
        "blocked": True,
        "side": _freer_side(persons, obstacle),
        "label": obstacle["label"],
        "box": obstacle["box"],
        "score": obstacle["score"],
    }
