package dev.sam3vision.core.motion

import dev.sam3vision.core.live.FrameBench
import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.LongSupplier

/**
 * Banc JVM du bloc-matching : coût et allocation **par frame** sur les deux grilles du direct
 * — le plan de luminance (256×103) et la grille masque (512×206), la plus chère.
 *
 * Les chiffres sont imprimés dans le rapport de test (`core/build/test-results/`) et repris
 * dans la PR, étiquetés « mesuré » : JVM x86, **pas un téléphone** (sur ART, compter 3 à 5×
 * plus lent — attendu, non mesuré). Le banc réutilise `FrameBench` de `:core/live` (chauffe,
 * 250 passes, compteur d'octets du thread) et sonde l'allocation : une grille réallouée par
 * frame se verrait immédiatement.
 *
 * Garde-fous volontairement larges (le vrai chiffre est celui du rapport, pas la borne) :
 * une frame à 30 fps dure 33 ms, le banc refuse donc au-delà de [MAX_US_PER_FRAME].
 */
class MotionBenchTest {

    private val source = BlockMotionSource()

    @Test
    fun `cout et allocation par frame sur les deux grilles`() {
        val bench = FrameBench()
        bench.measure("bloc-matching 256x103 (plan du direct)", probe(MotionScenes.scene()))
        bench.measure("bloc-matching 512x206 (grille masque)", probe(MotionScenes.doubled(MotionScenes.scene())))
        println(bench.report("L13 A-motion — bloc-matching sur grille réduite"))
        for (sample in bench.all()) {
            assertTrue("${sample.name} : ${sample.microsPerOp} µs/frame", sample.microsPerOp < MAX_US_PER_FRAME)
            if (sample.bytesPerOp >= 0L) {
                assertTrue("${sample.name} : ${sample.bytesPerOp} o/frame", sample.bytesPerOp <= MAX_BYTES_PER_FRAME)
            }
        }
    }

    /** Sonde : deux plans du même contenu, l'un décalé de 6 px (3 cellules de grille fine). */
    private fun probe(scene: GrayMask): LongSupplier {
        val moved = MotionScenes.shifted(scene, MOTION_PX, 2)
        return LongSupplier {
            val sample = source.estimateSince(scene, moved)
            if (sample == null) -1L else (sample.dx * 100f + sample.dy).toLong()
        }
    }

    private companion object {
        /** Déplacement du banc : 6 px, un multiple du pas de la grille fine (2). */
        const val MOTION_PX = 6

        /** Garde-fou : une frame à 30 fps dure 33 ms, le banc refuse au-delà de 20 ms. */
        const val MAX_US_PER_FRAME = 20_000.0

        /** L'échantillon rendu (16 o) et le plancher du banc (~7 o) ; une grille réallouée = 26 Ko. */
        const val MAX_BYTES_PER_FRAME = 512L
    }
}
