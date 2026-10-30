package dev.sam3vision.core.mask

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banc du décodage client : la cible du direct est une frame à ~500 ms, donc décoder huit
 * masques 256 × 103 doit rester de l'ordre de la milliseconde — très en dessous du budget.
 * Les chiffres sont **mesurés** (chronomètre JVM, machine de CI) et imprimés ; la borne
 * assertée est volontairement large, pour ne pas devenir un test qui clignote.
 *
 * Pas de mesure téléphone ici : le coût réel de l'app dépend d'Android et se lit sur
 * l'appareil, pas dans une JVM de CI — la valeur locale (poste / CI) est **non mesurée**
 * tant que la sortie du banc n'est pas relevée.
 */
class MaskBenchTest {

    /** Grille du codec : 256 px de côté pour une frame de 512, soit 256 × 103. */
    private val width = 256
    private val height = 103

    private val rle = Rle.b64(Rle.encode(Shapes.disk(width, height, 40)))

    private val poly = listOf(listOf(20, 20, 200, 20, 200, 80, 20, 80))

    @Test
    fun `decoder huit masques rle de la grille reduite tient dans le budget d une frame`() {
        val objectCount = 8
        val measured = timeBest { repeat(objectCount) { decodeRleMask(rle, width, height) } }
        val perObjectMs = measured / objectCount
        println(
            "RLE %d×%d : %.3f ms pour %d objets, %.3f ms/objet, %.1f ms pour une frame de 8 objets".format(
                width, height, measured, objectCount, perObjectMs, perObjectMs * objectCount,
            ),
        )

        assertTrue("le décodage RLE doit rester sous 2 ms par objet", perObjectMs < 2.0)
        assertNotNull(decodeRleMask(rle, width, height))
        // Le banc doit être répétable : la même mesure deux fois ne diverge pas d'un facteur 4.
        val second = timeBest { repeat(objectCount) { decodeRleMask(rle, width, height) } } / objectCount
        assertTrue("mesure stable ($perObjectMs puis $second ms/objet)", second < perObjectMs * 4 + 1)
    }

    @Test
    fun `la rasterisation poly de la meme grille tient aussi dans le budget`() {
        val perPolyMs = timeBest { decodePolyMask(poly, width, height) }
        println("poly %d×%d : %.3f ms/objet".format(width, height, perPolyMs))

        assertTrue("la rasterisation even-odd doit rester sous 5 ms par objet", perPolyMs < 5.0)
        assertNotNull(decodePolyMask(poly, width, height))
    }

    @Test
    fun `l agrandissement de la grille reduite a la frame tient dans le budget`() {
        val grid = GrayMask(width, height, Shapes.disk(width, height, 40).map { it.toByte() }.toByteArray())
        val perUpscaleMs = timeBest { upscaleNearest(grid, width * 2, height * 2) }
        println("upscale %d×%d → %d×%d : %.3f ms/objet".format(width, height, width * 2, height * 2, perUpscaleMs))

        assertTrue("l'agrandissement tables d'axe doit rester sous 5 ms par objet", perUpscaleMs < 5.0)
        assertNotNull(upscaleNearest(grid, width * 2, height * 2))
    }

    /** Meilleur temps de [rounds] tours, en millisecondes : le premier tour paie la JVM. */
    private fun timeBest(rounds: Int = ROUNDS, block: () -> Unit): Double {
        var best = Double.MAX_VALUE
        repeat(rounds) {
            val start = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - start) / 1_000_000.0)
        }
        return best
    }

    private companion object {
        const val ROUNDS = 20
    }
}