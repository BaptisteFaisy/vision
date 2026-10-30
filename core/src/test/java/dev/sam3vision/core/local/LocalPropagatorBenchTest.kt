package dev.sam3vision.core.local

import dev.sam3vision.core.live.FrameBench
import dev.sam3vision.core.mask.MaskFixtures
import dev.sam3vision.core.mask.MaskMotion
import java.util.function.LongSupplier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banc JVM de la chaîne de propagation (M4) : décision du moteur + warp CPU à 512×206
 * (grille du direct), [FRAMES] frames après [WARMUP] passes de chauffe — le même
 * protocole que le banc du warp seul (`MaskPropagatorBenchTest`), qui garde la mesure
 * p50/p95 ; ici c'est la **chaîne branchée** qui est mesurée, via le [FrameBench]
 * partagé (µs/op + octets/op, sans réécrire de boucle de mesure).
 *
 * Le banc **mesure** et imprime ; il n'affirme que la cible de la mission (< 8 ms par
 * frame en moyenne) et l'absence de réallocation de grille (un tampon de 105 472 octets
 * réalloué par frame se verrait aussitôt). Chiffres JVM x86, **pas un téléphone**.
 */
class LocalPropagatorBenchTest {

    private val luma = ByteArray(LumaPlane.SIZE) { 64 }

    /** Estimateur fixe : le même mouvement que le banc du warp, confiance haute. */
    private val stub = MotionSource { _, _ ->
        MaskMotion(dx = 1.5, dy = -0.75, scale = 1.01, confidence = 0.95)
    }

    @Test
    fun `decision et warp tiennent sous 8 ms par frame`() {
        val bench = FrameBench(warmup = WARMUP, iterations = FRAMES)
        bench.measure("tick moteur seul", tickProbe())
        bench.measure("chaine moteur + warp 512x206", chainProbe())
        println(bench.report("M4 — propagation locale : décision + warp CPU"))
        for (sample in bench.all()) {
            assertTrue("${sample.name} : ${sample.microsPerOp} µs/frame", sample.microsPerOp < CIBLE_US)
            if (sample.bytesPerOp >= 0L) {
                assertTrue("${sample.name} : ${sample.bytesPerOp} o/frame", sample.bytesPerOp <= MAX_BYTES_PER_FRAME)
            }
        }
    }

    /** Sonde tick : l'horloge avance de 33 ms par passe, sinon le moteur refuse (`TOO_EARLY`). */
    private fun tickProbe(): LongSupplier {
        val engine = LocalOverlayEngine()
        engine.setMotion(stub)
        engine.onKeyframe(luma)
        var now = 0L
        return LongSupplier {
            now += TICK_MS
            if (engine.tick(now, luma, KEY_AGE_MS, KEY_CONFIDENCE).paint) 1L else 0L
        }
    }

    /** Sonde chaîne : le keyframe est reposé avant sa péremption, sinon le banc mesure un refus. */
    private fun chainProbe(): LongSupplier {
        val chain = LocalPropagator()
        chain.setMotion(stub)
        val src = MaskFixtures.disc(WIDTH, HEIGHT, (WIDTH - 1) / 2.0, (HEIGHT - 1) / 2.0, 140.0, 90.0)
        chain.onKeyframe(src, luma, KEY_CONFIDENCE, KEY_AT)
        var now = KEY_AT
        var keyedAt = KEY_AT
        return LongSupplier {
            now += TICK_MS
            if (now - keyedAt >= REKEY_MS) {
                chain.onKeyframe(src, luma, KEY_CONFIDENCE, now)
                keyedAt = now
            }
            val out = chain.propagate(now, luma)
            (out?.pixels?.get(0)?.toLong() ?: -1L) + chain.lastTick.intervalMs
        }
    }

    private companion object {
        /** Passes de chauffe (JIT, caches) avant les passes chronométrées. */
        const val WARMUP = 100

        /** Passes chronométrées : 300 frames, comme le banc du warp. */
        const val FRAMES = 300

        /** Grille du direct : 512×206. */
        const val WIDTH = 512
        const val HEIGHT = 206

        /** Pas de la sonde : 33 ms (30 ips), la cadence cible du calque. */
        const val TICK_MS = 33L

        /** Âge du keyframe vu par la sonde tick : frais, jamais périmé. */
        const val KEY_AGE_MS = 100L

        /** Confiance du keyframe des sondes : haute, jamais refusée. */
        const val KEY_CONFIDENCE = 0.9f

        /** Instant du premier keyframe de la sonde chaîne : non nul (0 = aucun keyframe). */
        const val KEY_AT = 1_000L

        /** Le keyframe est reposé tous les 330 ms : frais, sans payer un décodage. */
        const val REKEY_MS = 330L

        /** Cible M4 : la propagation d'une frame tient sous 8 ms, en moyenne sur 300 frames. */
        const val CIBLE_US = 8_000.0

        /** Tick + mouvement remis à l'échelle : un tampon réalloué = 105 472 o, vu aussitôt. */
        const val MAX_BYTES_PER_FRAME = 1_024L
    }
}
