"""Parite du miroir Python avec le pipeline couleur Kotlin (joue en CI).

- Traits sur pixels synthetiques (valeurs calculees a la main).
- Les 31 ancres `feuxfr/` + `feuxfr2/` + `feuxfr3/` rendent les verdicts binaires attendus
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
CYAN_180 = 0xFF00FFFF  # teinte 180, sat 1,0
WASHED_GREEN = 0xFF99FFFF  # teinte 180, sat 0,40, valeur 1,0 (surexpose)
WASHED_GRAY = 0xFFF0F0F0  # sat 0, valeur 0,94 (pas un feu)

# Verdicts binaires attendus (cf. tests JVM). 31/10, ordre user : detection
# au plus large — disques verts et pose large admis, seuls les rouges refusent.
# 01/11, mesure : les disques au contour cyan complet restent rouges (367,
# st_c02 — tache plus large que haute, seuils intacts) ; 11 ancres lotfinal.
ANCHORS = {
    "feuxfr/fr_red_fig_548.ppm": "Red",
    "feuxfr/fr_red_dim_138.ppm": "Red",
    "feuxfr/fr_red_noise_732.ppm": "Red",
    "feuxfr/fr_green_fig_21.ppm": "Green",
    "feuxfr/fr_green_fig_302.ppm": "Green",
    "feuxfr/fr_green_fig_243.ppm": "Green",
    "feuxfr/fr_cardisc_close_183.ppm": "Green",
    "feuxfr/fr_cardisc_367.ppm": "Red",
    "feuxfr/fr_green_widepose_355.ppm": "Green",
    "feuxfr2/r1b_g01.ppm": "Green",
    "feuxfr2/r1b_g02.ppm": "Green",
    "feuxfr2/r1b_g03.ppm": "Green",
    "feuxfr2/r1b_g04.ppm": "Green",
    "feuxfr2/r1b_g05.ppm": "Green",
    "feuxfr2/st_g01.ppm": "Green",
    "feuxfr2/st_g02.ppm": "Green",
    "feuxfr2/st_r01.ppm": "Red",
    "feuxfr2/st_r02.ppm": "Red",
    "feuxfr2/st_c01.ppm": "Green",
    "feuxfr2/st_c02.ppm": "Red",
    "feuxfr3/tel_g01.ppm": "Green",
    "feuxfr3/tel_g02.ppm": "Green",
    "feuxfr3/tel_g03.ppm": "Green",
    "feuxfr3/tel_g04.ppm": "Green",
    "feuxfr3/tel_g05.ppm": "Green",
    "feuxfr3/tel_g06.ppm": "Green",
    "feuxfr3/tel_g07.ppm": "Green",
    "feuxfr3/tel_g08.ppm": "Green",
    "feuxfr3/tel_g09.ppm": "Green",
    "feuxfr3/tel_r01.ppm": "Red",
    "feuxfr3/tel_r02.ppm": "Red",
    "feuxfr3/tel_r03.ppm": "Red",
    "feuxfr3/tel_r04.ppm": "Red",
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

    def test_cyan_180_est_vert(self):
        # LED francaise : teinte 180 (cyan), exclue par l'ancien plafond 175.
        f = lf.extract([CYAN_180] * 100, 10, 10)
        self.assertAlmostEqual(f[13], 1.0)  # green_frac
        self.assertAlmostEqual(f[4], 1.0)  # hue 180 -> case 4

    def test_vert_delave_brillant_est_vert(self):
        # Lampe surexposee de jour : sat 0,40 < 0,45 mais valeur 1,0.
        f = lf.extract([WASHED_GREEN] * 100, 10, 10)
        self.assertAlmostEqual(f[13], 1.0)
        self.assertAlmostEqual(f[14], 1.0)  # compte comme allume

    def test_gris_delave_reste_eteint(self):
        # Garde du delavage : sans saturation, meme brillant, rien ne compte.
        f = lf.extract([WASHED_GRAY] * 100, 10, 10)
        self.assertAlmostEqual(f[13], 0.0)
        self.assertAlmostEqual(f[14], 0.0)

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
    def test_trente_trois_ancres(self):
        self.assertEqual(len(ANCHORS), 33)
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


class ThresholdParityTest(unittest.TestCase):
    """Frontiere verte 0,002 : miroir exact du test JVM (TrainedLightColorTest).

    01/11 : le miroir gardait le seuil appris 0,0048 alors que le Kotlin
    deploie 0,0035 puis 0,002 — la parite ne couvrait pas la bande.
    """

    def test_3px_sur_1000_vert(self):
        px = [BLACK] * 1000
        px[0] = px[1] = px[2] = GREEN
        self.assertEqual(lf.tree_classify(lf.extract(px, 25, 40)), "Green")

    def test_2px_sur_1000_rouge(self):
        px = [BLACK] * 1000
        px[0] = px[1] = GREEN
        self.assertEqual(lf.tree_classify(lf.extract(px, 25, 40)), "Red")

    def test_micro_bonhomme_bas_vert(self):
        # 48x48, barre 2x4 en bas : fraction 0,0035, dans l'ancienne bande.
        px = [BLACK] * (48 * 48)
        for y in range(44, 48):
            for x in (23, 24):
                px[y * 48 + x] = GREEN
        self.assertEqual(lf.verdict(px, 48, 48), "Green")


class LoadPpmTest(unittest.TestCase):
    def test_raster_commencant_par_du_blanc(self):
        import tempfile
        raw = b"P6\n# commentaire\n2 1\n255\n\tAB\x10\x20\x30"
        with tempfile.NamedTemporaryFile(suffix=".ppm", delete=False) as f:
            f.write(raw)
            path = f.name
        px, w, h = lf.load_ppm(path)
        Path(path).unlink()
        self.assertEqual((w, h), (2, 1))
        self.assertEqual(px, [0xFF094142, 0xFF102030])


if __name__ == "__main__":
    unittest.main()
