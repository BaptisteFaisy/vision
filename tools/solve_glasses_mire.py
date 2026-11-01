#!/usr/bin/env python3
"""Calibre la caméra des lunettes sur damier imprimé (protocole § 6).

Chaîne : `gen-mire` (damier SVG à imprimer à l'échelle) → N photos du damier
via le flux HIGH 720×1280 → `solve` (coins + `calibrateCamera`) → bloc
`mire v1` à coller dans Réglages → Distances, et JSON archivable.

Conventions (voir `core/.../distance/Distortion.kt`) : le modèle embarqué ne
porte que k1/k2 radiaux, norme = demi-diagonale de l'image. OpenCV calibre en
norme focale : `solve` convertit (pixels carrés exigés, sinon refus explicite).

Usage :
  python3 tools/solve_glasses_mire.py gen-mire --corners 9x6 --square-mm 25 --out mire.svg
  uv run --with opencv-python-headless --with numpy tools/solve_glasses_mire.py solve \\
      --photos OUT/high/ --corners 9x6 --square-mm 25 --out OUT/mire.json
  python3 tools/solve_glasses_mire.py --selftest   # stdlib ; bout-en-bout si cv2 présent
"""

import argparse
import json
import math
import sys
import time
from pathlib import Path

REF_W, REF_H = 720, 1280  # flux HIGH des lunettes, portrait
MIN_PHOTOS = 3            # en dessous, la résolution est sous-contrainte
SQUARE_TOL = 0.02         # |fx-fy|/fx max : la conversion suppose des pixels carrés


def gen_mire_svg(nx, ny, square_mm, margin_mm=10.0):
    """Damier SVG (nx,ny) coins intérieurs, cases de square_mm, unités mm.

    Stdlib pur (impression à l'échelle exacte, aucun pilote). Les coins
    intérieurs sont les seuls que compte `--corners` : le damier porte
    (nx+1)×(ny+1) cases.
    """
    if nx < 3 or ny < 3 or square_mm <= 0 or margin_mm < 0:
        raise ValueError("damier invalide : coins >= 3x3, case > 0, marge >= 0")
    cols, rows = nx + 1, ny + 1
    w, h = cols * square_mm + 2 * margin_mm, rows * square_mm + 2 * margin_mm
    rects = []
    for r in range(rows):
        for c in range(cols):
            if (r + c) % 2 == 0:
                continue
            x, y = margin_mm + c * square_mm, margin_mm + r * square_mm
            rects.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{square_mm:.2f}" '
                         f'height="{square_mm:.2f}" fill="black"/>')
    legend = (f"{nx}x{ny} coins interieurs, case {square_mm:g} mm — "
              f"imprimer a 100 %, verifier la case au reglet")
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.2f}mm" '
        f'height="{h:.2f}mm" viewBox="0 0 {w:.2f} {h:.2f}">\n'
        f'<rect x="0" y="0" width="{w:.2f}" height="{h:.2f}" fill="white"/>\n'
        + "\n".join(rects) +
        f'\n<text x="{margin_mm:.2f}" y="{h - 2:.2f}" font-size="4">{legend}</text>\n</svg>\n'
    )


def focal_to_fov_deg(focal_px, size_px):
    """Champ en degrés d'une focale px sur un axe de size_px."""
    return 2.0 * math.degrees(math.atan(size_px / 2.0 / focal_px))


def opencv_to_distortion(k1_cv, k2_cv, fx, fy, w, h):
    """Coeffs OpenCV (norme focale) → convention Distortion (demi-diagonale).

    OpenCV : r en unités focales ; Distortion : r en demi-diagonales.
    r_cv = r_dist · norme/f, d'où k1·(norme/f)² et k2·(norme/f)⁴.
    Refuse (ValueError) si les pixels ne sont pas carrés : la conversion
    n'aurait alors pas de focale unique.
    """
    if abs(fx - fy) / fx > SQUARE_TOL:
        raise ValueError(f"pixels non carres : fx={fx:.1f} fy={fy:.1f} — rephotographier")
    norm = math.hypot(w / 2.0, h / 2.0)
    ratio = norm / fx
    return k1_cv * ratio * ratio, k2_cv * ratio ** 4


def format_block(result):
    """Bloc `mire v1` à coller dans l'app (voir `parseMireBlock`)."""
    lines = ["mire v1"]
    for key in ("hFovDeg", "vFovDeg", "cx", "cy", "hasDistortion", "k1", "k2", "atMs"):
        lines.append(f"{key}={result[key]}")
    return "\n".join(lines) + "\n"


def solve_photos(paths, nx, ny, square_mm):
    """Résout les intrinsèques depuis des photos HIGH (720×1280) du damier.

    Importe cv2/numpy à la demande (lourds, via uv) : sans eux, message clair
    et exit 2. Flags : k3..k6 fixés à 0, tangentielle nulle — le modèle
    embarqué ne porte que k1/k2 radiaux.
    """
    try:
        import cv2
        import numpy as np
    except ImportError:
        print("error: opencv/numpy absents — relancer via :\n"
              "  uv run --with opencv-python-headless --with numpy "
              "tools/solve_glasses_mire.py solve ...", file=sys.stderr)
        sys.exit(2)
    pattern = np.zeros((nx * ny, 3), np.float32)
    pattern[:, :2] = np.mgrid[0:nx, 0:ny].T.reshape(-1, 2) * square_mm
    boards = detect_boards(paths, nx, ny)
    if len(boards) < MIN_PHOTOS:
        print(f"error: {len(boards)} damier(s) detecte(s), {MIN_PHOTOS} minimum requis",
              file=sys.stderr)
        sys.exit(1)
    obj = [pattern] * len(boards)
    img = [corners for _, corners in boards]
    (h, w), _ = boards[0]
    flags = (cv2.CALIB_FIX_K3 + cv2.CALIB_FIX_K4 + cv2.CALIB_FIX_K5 +
             cv2.CALIB_FIX_K6 + cv2.CALIB_ZERO_TANGENT_DIST)
    rms, camera, dist, _, _ = cv2.calibrateCamera(obj, img, (w, h), None, None, flags=flags)
    fx, fy = float(camera[0, 0]), float(camera[1, 1])
    cx, cy = float(camera[0, 2]) / w, float(camera[1, 2]) / h
    k1, k2 = opencv_to_distortion(float(dist[0, 0]), float(dist[0, 1]), fx, fy, w, h)
    return {
        "hFovDeg": round(focal_to_fov_deg(fx, w), 3),
        "vFovDeg": round(focal_to_fov_deg(fy, h), 3),
        "cx": round(cx, 4), "cy": round(cy, 4),
        "hasDistortion": True, "k1": round(k1, 4), "k2": round(k2, 4),
        "atMs": int(time.time() * 1000),
        "fxPx": round(fx, 1), "fyPx": round(fy, 1), "refW": w, "refH": h,
        "rmsPx": round(float(rms), 3), "views": len(obj),
    }


def detect_boards(paths, nx, ny):
    """Coins affinés de chaque damier détecté (cv2 déjà importé)."""
    import cv2
    boards = []
    for path in sorted(paths):
        frame = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if frame is None:
            print(f"warn: illisible, ignoree : {path}", file=sys.stderr)
            continue
        ok, corners = cv2.findChessboardCorners(frame, (nx, ny))
        if not ok:
            print(f"warn: damier absent, ignoree : {path}", file=sys.stderr)
            continue
        cv2.cornerSubPix(frame, corners, (11, 11), (-1, -1),
                         (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001))
        boards.append((frame.shape, corners))
    return boards


def cmd_solve(args):
    photos = [p for p in sorted(Path(args.photos).iterdir())
              if p.suffix.lower() in (".jpg", ".jpeg", ".png")]
    if not photos:
        print(f"error: aucune photo dans {args.photos}", file=sys.stderr)
        sys.exit(1)
    nx, ny = parse_corners(args.corners)
    result = solve_photos(photos, nx, ny, args.square_mm)
    report = dict(result)
    report["block"] = format_block(result)
    Path(args.out).write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(f"vues={result['views']} rms={result['rmsPx']} px "
          f"f={result['fxPx']}/{result['fyPx']} px sur {result['refW']}x{result['refH']}")
    print("--- bloc a coller dans Reglages → Distances ---")
    print(format_block(result), end="")


def cmd_gen_mire(args):
    nx, ny = parse_corners(args.corners)
    svg = gen_mire_svg(nx, ny, args.square_mm)
    Path(args.out).write_text(svg)
    print(f"mire {nx}x{ny} coins, case {args.square_mm:g} mm → {args.out} "
          f"(imprimer a 100 %, verifier au reglet)")


def parse_corners(text):
    try:
        nx, ny = (int(v) for v in text.lower().split("x"))
    except ValueError:
        print(f"error: --corners invalide (attendu NXxNY) : {text}", file=sys.stderr)
        sys.exit(2)
    if nx < 3 or ny < 3:
        print("error: damier >= 3x3 coins", file=sys.stderr)
        sys.exit(2)
    return nx, ny


def selftest():
    """Autotest stdlib + bout-en-bout cv2 si présent (PARTIEL sinon, dit)."""
    fails = []

    def check(name, cond):
        print(("ok   " if cond else "FAIL ") + name)
        if not cond:
            fails.append(name)

    k1, k2 = opencv_to_distortion(-0.2, 0.05, 500.0, 500.0, 720, 1280)
    check("conversion k1", abs(k1 - -0.4314) < 0.001)
    check("conversion k2", abs(k2 - 0.2326) < 0.001)
    try:
        opencv_to_distortion(0.0, 0.0, 500.0, 400.0, 720, 1280)
        check("pixels non carres refuses", False)
    except ValueError:
        check("pixels non carres refuses", True)
    check("fov 500px/720px", abs(focal_to_fov_deg(500.0, 720.0) - 71.51) < 0.01)
    svg = gen_mire_svg(9, 6, 25.0)
    check("svg cases noires", svg.count('fill="black"') == 35)
    check("svg dimensions mm", 'width="270.00mm"' in svg and 'height="195.00mm"' in svg)
    block = format_block({"hFovDeg": 60.61, "vFovDeg": 92.19, "cx": 0.5, "cy": 0.5,
                          "hasDistortion": True, "k1": -0.1, "k2": 0.02, "atMs": 1})
    check("bloc entete", block.startswith("mire v1\n"))
    try:
        import cv2  # noqa: F401
        import numpy  # noqa: F401
    except ImportError:
        print("PARTIEL : cv2 absent, bout-en-bout non testé "
              "(uv run --with opencv-python-headless --with numpy ... --selftest)")
    else:
        check("bout-en-bout synthetique", synthetic_roundtrip())
    if fails:
        print(f"SELFTEST KO : {fails}", file=sys.stderr)
        sys.exit(1)
    print("SELFTEST OK")


def synthetic_roundtrip():
    """5 vues parfaites d'un damier connu : calibrateCamera doit les retrouver."""
    import cv2
    import numpy as np
    fx, fy, cx, cy, k1, k2 = 620.0, 620.0, 360.0, 640.0, -0.15, 0.03
    nx, ny, sq = 9, 6, 25.0
    pattern = np.zeros((nx * ny, 3), np.float32)
    pattern[:, :2] = np.mgrid[0:nx, 0:ny].T.reshape(-1, 2) * sq
    camera = np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]], np.float64)
    dist = np.array([k1, k2, 0, 0, 0], np.float64)
    obj, img = [], []
    rng = np.random.default_rng(7)
    for _ in range(5):
        rvec = (rng.random(3) - 0.5) * 0.6
        tvec = np.array([(rng.random() - 0.5) * 200, (rng.random() - 0.5) * 200,
                         600 + rng.random() * 400])
        pts, _ = cv2.projectPoints(pattern, rvec, tvec, camera, dist)
        obj.append(pattern)
        img.append(pts.astype(np.float32))
    flags = (cv2.CALIB_FIX_K3 + cv2.CALIB_FIX_K4 + cv2.CALIB_FIX_K5 +
             cv2.CALIB_FIX_K6 + cv2.CALIB_ZERO_TANGENT_DIST)
    rms, cam, dst, _, _ = cv2.calibrateCamera(obj, img, (REF_W, REF_H), None, None, flags=flags)
    return (rms < 1e-3 and abs(cam[0, 0] - fx) < 0.5 and abs(cam[1, 1] - fy) < 0.5 and
            abs(cam[0, 2] - cx) < 0.5 and abs(cam[1, 2] - cy) < 0.5 and
            abs(dst[0, 0] - k1) < 1e-3 and abs(dst[0, 1] - k2) < 1e-3)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true", help="autotest (stdlib + cv2 si présent)")
    sub = parser.add_subparsers(dest="cmd")
    gen = sub.add_parser("gen-mire", help="damier SVG à imprimer")
    gen.add_argument("--corners", required=True, help="coins intérieurs, ex. 9x6")
    gen.add_argument("--square-mm", type=float, required=True, help="côté d'une case en mm")
    gen.add_argument("--out", required=True, help="fichier .svg")
    solve = sub.add_parser("solve", help="résout les intrinsèques depuis des photos HIGH")
    solve.add_argument("--photos", required=True, help="dossier de JPG/PNG 720x1280")
    solve.add_argument("--corners", required=True, help="coins intérieurs, ex. 9x6")
    solve.add_argument("--square-mm", type=float, required=True, help="côté d'une case en mm")
    solve.add_argument("--out", required=True, help="fichier .json (rapport + bloc)")
    args = parser.parse_args(argv)
    if args.selftest:
        selftest()
    elif args.cmd == "gen-mire":
        cmd_gen_mire(args)
    elif args.cmd == "solve":
        cmd_solve(args)
    else:
        parser.print_help()
        sys.exit(2)


if __name__ == "__main__":
    main()
