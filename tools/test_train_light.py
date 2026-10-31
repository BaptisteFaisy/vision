"""Parite du miroir Python avec le pipeline couleur Kotlin (joue en CI).

- Traits sur pixels synthetiques (valeurs calculees a la main).
- Les 20 ancres `feuxfr/` + `feuxfr2/` rendent les verdicts binaires attendus
  (recopies des tests JVM `PedLightGateTest` / `PedLightGateLimitsTest`) :
  tout ecart Kotlin/Python casse ici.
- Stdlib uniquement (uttile sans sklearn/PIL).
"""

import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import light_features as lf  # noqa: E402

RES = TOOLS.parent / "core/src/test/resources"

RED, GREEN, BLACK = 0xFFFF0000, 0xFF00FF00, 0xFF000000

# Verdicts binaires attendus (cf. tests JVM) : verts confirmes, tout le reste rouge.
ANCHORS = {
    "feuxfr/fr_red_fig_548.ppm": "Red",
    "feuxfr/fr_red_dim_138.ppm": "Red",
    "feuxfr/fr_red_noise_732.ppm": "Red",
    "feuxfr/fr_green_fig_21.ppm": "Green",
    "feuxfr/fr_green_fig_302.ppm": "Green",
    "feuxfr/fr_green_fig_243.ppm": "Green",
    "feuxfr/fr_cardisc_close_183.ppm": "Red",
    "feuxfr/fr_cardisc_367.ppm": "Red",
    "feuxfr/fr_green_widepose_355.ppm": "Red",
    "feuxfr2/r1b_g01.ppm": "Green",
    "feuxfr2/r1b_g02.ppm": "Green",
    "feuxfr2/r1b_g03.ppm": "Green",
    "feuxfr2/r1b_g04.ppm": "Green",
    "feuxfr2/r1b_g05.ppm": "Green",
    "feuxfr2/st_g01.ppm": "Green",
    "feuxfr2/st_g02.ppm": "Green",
    "feuxfr2/st_r01.ppm": "Red",
    "feuxfr2/st_r02.ppm": "Red",
    "feuxfr2/st_c01.ppm": "Red",
    "feuxfr2/st_c02.ppm": "Red",
}


class FeaturesTest(unittest.TestCase):
    def test_noir_traits_nuls(self):
        self.assertEqual(lf.extract([BLACK] * 100, 10, 10), [0.0] * 17)

    def test_rouge_franc(self):
        f = lf.extract([RED] * 100, 10, 10)
        self.assertAlmostEqual(f[12], 1.0)  # red_frac
        self.assertAlmostEqual(f[13], 0.0)  # green_frac
        self.assertAlmostEqual(f[0], 1.0)  # hue_bin_0 (teinte 0)
        self.assertAlmostEqual(sum(f[0:8]), 1.0)

    def test_vert_franc(self):
        f = lf.extract([GREEN] * 100, 10, 10)
        self.assertAlmostEqual(f[13], 1.0)
        self.assertAlmostEqual(f[12], 0.0)
        self.assertAlmostEqual(f[2], 1.0)  # hue 120 -> case 2

    def test_moities(self):
        px = [RED] * 50 + [GREEN] * 50  # haut rouge (lignes 0-4), bas vert
        f = lf.extract(px, 10, 10)
        self.assertAlmostEqual(f[8], 0.5)  # red_top
        self.assertAlmostEqual(f[9], 0.0)  # red_bottom
        self.assertAlmostEqual(f[10], 0.0)  # green_top
        self.assertAlmostEqual(f[11], 0.5)  # green_bottom

    def test_incoherent_traits_nuls(self):
        self.assertEqual(lf.extract([], 0, 0), [0.0] * 17)
        self.assertEqual(lf.extract([RED] * 10, 10, 10), [0.0] * 17)


class ParityTest(unittest.TestCase):
    def test_vingt_ancres(self):
        self.assertEqual(len(ANCHORS), 20)
        for name, want in sorted(ANCHORS.items()):
            px, w, h = lf.load_ppm(RES / name)
            with self.subTest(name=name):
                self.assertEqual(lf.verdict(px, w, h), want)


class Feuxfr3PatternTest(unittest.TestCase):
    """Le patron feuxfr3/ existe et reste documente (0 crop ou N ancres)."""

    def test_patron_documente(self):
        d = RES / "feuxfr3"
        self.assertTrue((d / "ATTRIBUTION.md").is_file())
        self.assertTrue((d / "README.md").is_file())
        text = (d / "ATTRIBUTION.md").read_text()
        self.assertIn("tel_g", text)
        for ppm in sorted(d.glob("*.ppm")):
            px, w, h = lf.load_ppm(ppm)
            self.assertLessEqual(max(w, h), 48, ppm.name)
            self.assertIn(ppm.name, text, ppm.name)


if __name__ == "__main__":
    unittest.main()
