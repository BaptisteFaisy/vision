"""Solveur mire lunettes : conventions et génération (joue en CI).

Stdlib uniquement : la conversion OpenCV→Distortion, la focale→champ, le
damier SVG et le format du bloc collé. Le bout-en-bout cv2 vit dans
`solve_glasses_mire.py --selftest` (lourd, via uv, hors CI).
"""

import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import solve_glasses_mire as solver  # noqa: E402


class TestConventions(unittest.TestCase):
    def test_conversion_norme_focale_vers_demi_diagonale(self):
        k1, k2 = solver.opencv_to_distortion(-0.2, 0.05, 500.0, 500.0, 720, 1280)
        self.assertAlmostEqual(k1, -0.4314, places=4)
        self.assertAlmostEqual(k2, 0.2326, places=4)

    def test_pixels_non_carres_refuses(self):
        with self.assertRaises(ValueError):
            solver.opencv_to_distortion(0.0, 0.0, 500.0, 400.0, 720, 1280)

    def test_focale_vers_champ(self):
        self.assertAlmostEqual(solver.focal_to_fov_deg(500.0, 720.0), 71.51, places=2)
        self.assertAlmostEqual(solver.focal_to_fov_deg(500.0, 1280.0), 104.0, places=2)


class TestMireSvg(unittest.TestCase):
    def test_cases_et_dimensions(self):
        svg = solver.gen_mire_svg(9, 6, 25.0)
        self.assertEqual(svg.count('fill="black"'), 35)
        self.assertIn('width="270.00mm"', svg)
        self.assertIn('height="195.00mm"', svg)
        self.assertIn("9x6", svg)

    def test_damier_invalide_refuse(self):
        with self.assertRaises(ValueError):
            solver.gen_mire_svg(2, 6, 25.0)
        with self.assertRaises(ValueError):
            solver.gen_mire_svg(9, 6, 0.0)


class TestBloc(unittest.TestCase):
    def test_format_exact(self):
        block = solver.format_block({
            "hFovDeg": 60.61, "vFovDeg": 92.19, "cx": 0.5, "cy": 0.5,
            "hasDistortion": True, "k1": -0.1, "k2": 0.02, "atMs": 1,
            "refW": 720, "refH": 1280,
        })
        self.assertEqual(block, "mire v1\nhFovDeg=60.61\nvFovDeg=92.19\ncx=0.5\ncy=0.5\n"
                               "hasDistortion=True\nk1=-0.1\nk2=0.02\natMs=1\n"
                               "refW=720\nrefH=1280\n")


class TestCoins(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(solver.parse_corners("9x6"), (9, 6))

    def test_invalide(self):
        with self.assertRaises(SystemExit):
            solver.parse_corners("2x6")
        with self.assertRaises(SystemExit):
            solver.parse_corners("neuf")


if __name__ == "__main__":
    unittest.main()
