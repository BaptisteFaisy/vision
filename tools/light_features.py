"""Miroir Python du pipeline couleur feu (entrainement + parite).

Reproduit a l'identique : `CrossingFeatures.extract`, `TrainedLightColor`
(tableaux courants), `PedLightGate` (porte + R1b) et le verdict binaire.
Sources : `core/.../crossing/{CrossingFeatures,TrainedLightColor,PedLightGate,PedLightColor}.kt`.

La parite est verrouillee par `tools/test_train_light.py` (31 ancres
`feuxfr/` + `feuxfr2/`, joue en CI) : tout ecart Kotlin/Python casse le test.
Le trainer (`tools/train_light.py`) s'appuie dessus pour evaluer un
arbre candidat avant de l'embarquer.

Stdlib uniquement (pas de numpy/sklearn/PIL ici).
"""

from __future__ import annotations

import csv
import math
from pathlib import Path

# --- Seuils (copies de PedLightGate.kt / PedLightColor.kt / TrainedLightColor.kt) ---
RED_HUE_DEG = 15.0
GREEN_HUE_MIN, GREEN_HUE_MAX = 80.0, 195.0
MIN_SAT, MIN_VAL = 0.45, 0.35
WASHOUT_MIN_SAT, WASHOUT_MIN_VAL = 0.15, 0.90
GREEN_MIN_FRACTION = 0.002  # 31/10, ordre user : suit PedLightGate.kt (feux lointains)
RED_MAX_FRACTION = 0.02
ELONGATION_MIN = 1.0  # 31/10, ordre user : suit PedLightGate.kt (détection au plus large)
BBOX_MIN_PX = 3
GREEN_BOTTOM_MIN = 0.35
R1B_BOTTOM_FLOOR = 0.20
CLOUD_ELONGATION_MIN = 1.0  # 31/10, ordre user : suit PedLightGate.kt (détection au plus large)
TOP3_MAX = 0.38
EDGE_MAX = 0.72
MIN_PURITY = 0.80
GREEN_MIN_PURITY = 0.50
GREEN_FRAC_INDEX = 13

FEATURE = [13, -2, -2]
THRESHOLD = [0.004812047118321061, -2.0, -2.0]
LEFT = [1, -1, -1]
RIGHT = [2, -1, -1]
LEAF_CLASS = [0, 0, 1]
LEAF_PURITY = [0.0, 1.0, 1.0]


def _rgb(px: int) -> tuple:
    return ((px >> 16) & 0xFF, (px >> 8) & 0xFF, px & 0xFF)


def hue_deg(px: int) -> float:
    r, g, b = [c / 255.0 for c in _rgb(px)]
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    if d <= 0:
        return 0.0
    if mx == r:
        h = 60.0 * (((g - b) / d) % 6.0)
    elif mx == g:
        h = 60.0 * ((b - r) / d + 2.0)
    else:
        h = 60.0 * ((r - g) / d + 4.0)
    return h + 360.0 if h < 0 else h


def saturation(px: int) -> float:
    r, g, b = [c / 255.0 for c in _rgb(px)]
    mx = max(r, g, b)
    return 0.0 if mx <= 0 else (mx - min(r, g, b)) / mx


def value(px: int) -> float:
    return max(_rgb(px)) / 255.0


def _is_red(h: float) -> bool:
    return h <= RED_HUE_DEG or h >= 360.0 - RED_HUE_DEG


def _is_green(h: float) -> bool:
    return GREEN_HUE_MIN <= h <= GREEN_HUE_MAX


def _lit(px: int) -> bool:
    """Miroir de PedLightColor.isLit : franc ou delave brillant."""
    v = value(px)
    if v < MIN_VAL:
        return False
    s = saturation(px)
    if s >= MIN_SAT:
        return True
    return s >= WASHOUT_MIN_SAT and v >= WASHOUT_MIN_VAL


def extract(argb: list, w: int, h: int) -> list:
    """17 traits (voir FEATURE_NAMES Kotlin), ordre identique."""
    if w <= 0 or h <= 0 or len(argb) != w * h:
        return [0.0] * 17
    bins = [0.0] * 8
    red = green = red_top = red_bot = green_top = green_bot = sat = 0
    vsum = ssum = 0.0
    half = h // 2
    for i, px in enumerate(argb):
        hue, s, v = hue_deg(px), saturation(px), value(px)
        vsum += v
        ssum += s
        if not _lit(px):
            continue
        sat += 1
        bins[min(7, int(hue / 45.0))] += 1.0
        top = (i // w) < half
        if _is_red(hue):
            red += 1
            if top:
                red_top += 1
            else:
                red_bot += 1
        elif _is_green(hue):
            green += 1
            if top:
                green_top += 1
            else:
                green_bot += 1
    n = float(len(argb))
    den = max(1.0, float(sat))
    feats = [b / den for b in bins]
    feats += [red_top / n, red_bot / n, green_top / n, green_bot / n,
              red / n, green / n, sat / n, vsum / n, ssum / n]
    return feats


def tree_classify(feats: list, feature=FEATURE, threshold=THRESHOLD,
                  left=LEFT, right=RIGHT, leaf_class=LEAF_CLASS,
                  leaf_purity=LEAF_PURITY) -> str:
    """Arbre + planchers de purete + garde fraction verte (binaire)."""
    node = 0
    while left[node] >= 0:
        node = left[node] if feats[feature[node]] <= threshold[node] else right[node]
    leaf_green = leaf_class[node] == 1
    floor = GREEN_MIN_PURITY if leaf_green else MIN_PURITY
    if leaf_purity[node] < floor:
        return "Red"
    if leaf_green and feats[GREEN_FRAC_INDEX] < GREEN_MIN_FRACTION:
        return "Red"
    return "Green" if leaf_green else "Red"


def _scan(argb: list, w: int, h: int) -> dict:
    red = green = green_bot = 0
    minx, maxx, miny, maxy = w, -1, h, -1
    half = h // 2
    for i, px in enumerate(argb):
        if not _lit(px):
            continue
        hue = hue_deg(px)
        if _is_red(hue):
            red += 1
        elif _is_green(hue):
            green += 1
            x, y = i % w, i // w
            if y >= half:
                green_bot += 1
            minx, maxx = min(minx, x), max(maxx, x)
            miny, maxy = min(miny, y), max(maxy, y)
    return {"red": red, "green": green, "green_bot": green_bot,
            "minx": minx, "maxx": maxx, "miny": miny, "maxy": maxy}


def _cloud_elongation(xs: list, ys: list) -> float:
    n = float(len(xs))
    mx, my = sum(xs) / n, sum(ys) / n
    xstd = math.sqrt(sum((x - mx) ** 2 for x in xs) / n)
    ystd = math.sqrt(sum((y - my) ** 2 for y in ys) / n)
    return ystd / (xstd + 1e-9)


def _top_third(ys: list) -> float:
    lo, hi = min(ys), max(ys)
    prof = [0] * (hi - lo + 1)
    for y in ys:
        prof[y - lo] += 1
    third = max(1, len(prof) // 3)
    return sum(prof[:third]) / len(ys)


def _edge_fraction(xs: list, ys: list, w: int, h: int) -> float:
    grid = [False] * (w * h)
    for k in range(len(xs)):
        grid[ys[k] * w + xs[k]] = True
    lo, hi = min(ys), max(ys)
    edge = cnt = 0
    for y in range(lo, hi + 1):
        for x in range(w):
            if not grid[y * w + x]:
                continue
            cnt += 1
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if nx not in range(w) or ny not in range(h) or not grid[ny * w + nx]:
                    edge += 1
                    break
    return edge / cnt


def accept_green(argb: list, w: int, h: int) -> bool:
    if w <= 0 or h <= 0 or len(argb) != w * h:
        return False
    s = _scan(argb, w, h)
    total = float(len(argb))
    if s["green"] / total < GREEN_MIN_FRACTION:
        return False
    if s["red"] / total >= RED_MAX_FRACTION:
        return False
    bw, bh = s["maxx"] - s["minx"] + 1, s["maxy"] - s["miny"] + 1
    if bh < BBOX_MIN_PX:
        return False
    if float(bh) < bw * ELONGATION_MIN:
        return False
    bottom = s["green_bot"] / s["green"]
    if bottom >= GREEN_BOTTOM_MIN:
        return True
    if bottom <= R1B_BOTTOM_FLOOR:
        return False
    xs, ys = [], []
    for i, px in enumerate(argb):
        if _lit(px) and _is_green(hue_deg(px)):
            xs.append(i % w)
            ys.append(i // w)
    if not xs:
        return False
    if _cloud_elongation(xs, ys) < CLOUD_ELONGATION_MIN:
        return False
    if _top_third(ys) > TOP3_MAX:
        return False
    return _edge_fraction(xs, ys, w, h) <= EDGE_MAX


def verdict(argb: list, w: int, h: int, tree=None) -> str:
    """Verdict binaire : vert de l'arbre + porte, sinon rouge."""
    raw = tree_classify(extract(argb, w, h)) if tree is None else tree(extract(argb, w, h))
    if raw != "Green":
        return "Red"
    return "Green" if accept_green(argb, w, h) else "Red"


def load_ppm(path: str | Path) -> tuple:
    """PPM P6 -> (pixels ARGB int, w, h)."""
    raw = Path(path).read_bytes()
    parts = raw.split(None, 4)
    assert parts[0] == b"P6", path
    w, h, mx = int(parts[1]), int(parts[2]), int(parts[3])
    assert mx == 255, path
    data = parts[4][:w * h * 3]
    assert len(data) == w * h * 3, path
    px = [0xFF000000 | (data[i] << 16) | (data[i + 1] << 8) | data[i + 2]
          for i in range(0, len(data), 3)]
    return px, w, h


def load_labels(path: str | Path) -> dict:
    """labels.csv (name,label) -> {nom: label} ; label dans {R,G,BAD}."""
    out = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            name, label = row["name"].strip(), row["label"].strip().upper()
            assert label in ("R", "G", "BAD"), (name, label)
            out[name] = label
    return out
