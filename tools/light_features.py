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
# 01/11 : tache plus large que haute = disque de face, sauf si elle est ajourée.
# Un disque allumé remplit 0,75-0,88 de sa boîte, un bonhomme large 0,09-0,16.
WIDE_FILL_MAX = 0.35
# 02/11 (W5) : une tache verte doit faire >= 5 % du plus grand cote du crop.
# En FRACTION et non en pixels : le corpus est a 48 px, la prod a 96 px ;
# a 96 px les faux verts sont des bandes de 1-4 px, les vrais verts >= 7 px.
# 02/11 (W12 pt1) : 0,05 -> 0,04, borne haute compatible avec les 33 ancres
# (`feuxfr3/tel_g02` : composante verte de 2 px sur un crop de 48 = 0,0417).
# La largeur mesuree est celle de la plus LARGE composante 8-connexe, pas
# l'etendue X de tous les pixels verts (deux bandes de 1 px opposees
# cumulaient 86 px d'etendue). Voir PedLightGate.kt et fixa/RAPPORT.md.
GREEN_MIN_WIDTH_FRACTION = 0.04
# 02/11 (W12 pt1) : la largeur mesuree est celle de la plus LARGE composante
# 8-connexe du masque vert, pas l'etendue X de tous les pixels verts (deux
# bandes de 1 px opposees cumulaient 86 px d'etendue). Voir PedLightGate.kt.
# Le modele de couleur de la PORTE est la projection signee (pas la teinte) :
# `_scan` et le nuage doivent lire la meme chose que `PedLightGate.scan`.
GREEN_PROJECTION_MIN = 0.30  # (G - R) / max(R,G,B) >= 0,30 (PedLightColor)
RED_PROJECTION_MAX = -0.50
BBOX_MIN_PX = 3
GREEN_BOTTOM_MIN = 0.35
R1B_BOTTOM_FLOOR = 0.15  # 01/11, ordre user : suit PedLightGate.kt (jitter boite)
CLOUD_ELONGATION_MIN = 1.0  # 31/10, ordre user : suit PedLightGate.kt (détection au plus large)
TOP3_MAX = 0.38
EDGE_MAX = 0.75  # 01/11, ordre user : suit PedLightGate.kt (jitter boite)
MIN_PURITY = 0.80
GREEN_MIN_PURITY = 0.50
GREEN_FRAC_INDEX = 13

FEATURE = [13, -2, -2]
# 01/11 : suit TrainedLightColor.kt (0,002, plancher partage avec la porte) —
# l'ancienne valeur apprise 0,0048 derivait du deploie sans casser la parite.
THRESHOLD = [0.002, -2.0, -2.0]
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


def projection(px: int) -> float:
    """Projection signee (G - R) / max(R,G,B) : +1 vert, 0 bleu/blanc, -1 rouge."""
    r, g, b = _rgb(px)
    mx = max(r, g, b)
    return 0.0 if mx <= 0 else (g - r) / float(mx)


def _is_red_proj(px: int) -> bool:
    """Cote rouge de la porte — meme seuil que PedLightColor.RED_PROJECTION_MAX."""
    return projection(px) <= RED_PROJECTION_MAX


def _is_green_proj(px: int) -> bool:
    """Cote vert de la porte — meme seuil que PedLightColor.GREEN_PROJECTION_MIN."""
    return projection(px) >= GREEN_PROJECTION_MIN


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
        proj = projection(px)
        if proj <= RED_PROJECTION_MAX:
            red += 1
        elif proj >= GREEN_PROJECTION_MIN:
            green += 1
            x, y = i % w, i // w
            if y >= half:
                green_bot += 1
            minx, maxx = min(minx, x), max(maxx, x)
            miny, maxy = min(miny, y), max(maxy, y)
    return {"red": red, "green": green, "green_bot": green_bot,
            "minx": minx, "maxx": maxx, "miny": miny, "maxy": maxy}


def _green_mask(argb: list) -> list:
    """Masque vert de la porte : mêmes pixels que `_scan` (lit + projection)."""
    return [_lit(px) and _is_green_proj(px) for px in argb]


def widest_component(mask: list, w: int, h: int) -> int:
    """Etendue X de la plus large composante 8-connexe du masque (iteratif).

    Miroir de `PedLightGate.widestComponent` : ce que mesure le plancher de
    largeur. Deux bandes de 1 px opposees ont chacune une largeur de 1, alors
    que leur boite englobante mesure la distance qui les separe.
    """
    seen = bytearray(len(mask))
    best = 0
    for start in range(len(mask)):
        if not mask[start] or seen[start]:
            continue
        seen[start] = 1
        stack = [start]
        minx = maxx = start % w
        while stack:
            i = stack.pop()
            x, y = i % w, i // w
            minx, maxx = min(minx, x), max(maxx, x)
            for ny in (y - 1, y, y + 1):
                if ny < 0 or ny >= h:
                    continue
                for nx in (x - 1, x, x + 1):
                    if nx < 0 or nx >= w:
                        continue
                    j = ny * w + nx
                    if mask[j] and not seen[j]:
                        seen[j] = 1
                        stack.append(j)
        best = max(best, maxx - minx + 1)
    return best


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
    if float(bh) < bw * ELONGATION_MIN and s["green"] > bw * bh * WIDE_FILL_MAX:
        return False
    # W12 pt1 : la largeur exigee est celle d'UNE tache, pas l'etendue de toutes.
    if widest_component(_green_mask(argb), w, h) < GREEN_MIN_WIDTH_FRACTION * max(w, h):
        return False
    bottom = s["green_bot"] / s["green"]
    if bottom >= GREEN_BOTTOM_MIN:
        return True
    if bottom <= R1B_BOTTOM_FLOOR:
        return False
    xs, ys = [], []
    for i, px in enumerate(argb):
        if _lit(px) and _is_green_proj(px):
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
    """PPM P6 -> (pixels ARGB int, w, h).

    En-tete parse a l'octet (commentaires `#` sautes, un seul blanc mange
    apres maxval) : `bytes.split` mangeait les octets blancs en debut de
    raster et cassait les fichiers concernes (vu 01/11 sur raw_crops).
    """
    raw = Path(path).read_bytes()
    assert raw[:2] == b"P6", path
    toks: list = []
    i, n = 2, len(raw)
    while len(toks) < 3 and i < n:
        while i < n and raw[i:i + 1].isspace():
            i += 1
        if raw[i:i + 1] == b"#":
            while i < n and raw[i:i + 1] != b"\n":
                i += 1
            continue
        j = i
        while j < n and not raw[j:j + 1].isspace():
            j += 1
        toks.append(raw[i:j])
        i = j
    w, h, mx = int(toks[0]), int(toks[1]), int(toks[2])
    assert mx == 255, path
    data = raw[i + 1:i + 1 + w * h * 3]
    assert len(data) == w * h * 3, path
    px = [0xFF000000 | (data[k] << 16) | (data[k + 1] << 8) | data[k + 2]
          for k in range(0, len(data), 3)]
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
