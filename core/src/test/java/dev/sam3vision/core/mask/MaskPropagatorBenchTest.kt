package dev.sam3vision.core.mask

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banc du warp ([MaskPropagator]) : coût par frame à 512×206 (grille du direct) en
 * bilinéaire et au plus proche, sur la grille de masque réduite 256×103 (u04), et
 * comparatif du chemin **sans allocation** ([warpInto]) contre le chemin allouant
 * ([warp], qui paie 105 472 octets — 512×206 — par frame).
 *
 * Le banc **mesure** et imprime ; il n'affirme que la cible de la mission (< 8 ms par
 * frame à 512×206, p50 0,45 ms mesuré sur le r630), le banc ci-dessus la confirme avec
 * 25× de marge sur ce poste.
 */
class MaskPropagatorBenchTest {

    @Test
    fun `banc de propagation du masque`() {
        val width = 512
        val height = 206
        val src = MaskFixtures.disc(width, height, (width - 1) / 2.0, (height - 1) / 2.0, 140.0, 90.0)
        val propagator = MaskPropagator()
        val dst = GrayMask(width, height, ByteArray(src.size))
        val motion = MaskMotion(dx = 1.5, dy = -0.75, scale = 1.01)

        val tampon = bench { propagator.warpInto(src, dst, motion) }
        val proche = bench { propagator.warpInto(src, dst, motion, WarpFilter.NEAREST) }
        val petit = MaskFixtures.disc(256, 103, 127.5, 51.0, 70.0, 45.0)
        val petitDst = GrayMask(256, 103, ByteArray(petit.size))
        val reduit = bench { propagator.warpInto(petit, petitDst, MaskMotion(dx = 0.75, dy = 0.25)) }
        val allouant = bench { propagator.warp(src, motion)!!.pixels[0] }

        println("u11 bench - propagation de masque, n=$FRAMES frames, warmup=$WARMUP")
        ligne("BILINEAR ${width}x$height tampon", tampon)
        ligne("NEAREST  ${width}x$height tampon", proche)
        ligne("BILINEAR 256x103 tampon", reduit)
        ligne("BILINEAR ${width}x$height allouant", allouant)
        println(
            "  qualite  IoU demi-pixel=${pct(MaskFixtures.iouDemiPixel(propagator, src))}" +
                "  IoU chaine(10)/direct=${pct(MaskFixtures.iouChaine(propagator, src))}",
        )

        assertTrue("p50 bilinéaire tampon = ${ms(tampon[0])} ms", tampon[0] < CIBLE_MS)
    }

    /** p50, p95 et max d'un bloc, en ms, sur [FRAMES] frames après [WARMUP] frames. */
    private fun bench(block: () -> Unit): DoubleArray {
        repeat(WARMUP) { block() }
        val tirs = DoubleArray(FRAMES)
        for (i in tirs.indices) {
            val debut = System.nanoTime()
            block()
            tirs[i] = (System.nanoTime() - debut) / 1_000_000.0
        }
        tirs.sort()
        return doubleArrayOf(tirs[FRAMES / 2], tirs[FRAMES * 95 / 100], tirs[FRAMES - 1])
    }

    private fun ligne(nom: String, temps: DoubleArray) {
        println("  ${nom.padEnd(28)}  p50=${ms(temps[0])} ms  p95=${ms(temps[1])} ms  max=${ms(temps[2])} ms")
    }

    /** Millisecondes au centième, virgule française : le format des rapports. */
    private fun ms(valeur: Double): String = String.format(Locale.FRANCE, "%.2f", valeur)

    /** Fraction à quatre décimales, virgule française : le format des rapports. */
    private fun pct(valeur: Double): String = String.format(Locale.FRANCE, "%.4f", valeur)

    private companion object {
        const val WARMUP = 100
        const val FRAMES = 300

        /** Cible de la mission : le warp d'une frame doit tenir sous 8 ms. */
        const val CIBLE_MS = 8.0
    }
}