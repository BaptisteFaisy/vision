package dev.sam3vision.core.live

import java.util.function.LongSupplier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coût de l'instrumentation de latence, mesuré en JVM : la trace ne doit pas fausser ce
 * qu'elle mesure — **≤ 2 µs/frame** et **≤ 70 µs/résumé** (budget de la campagne u07).
 *
 * Mesure **meilleur de [ATTEMPTS] essais**, et non une passe unique : sur un runner
 * partagé (la CI fait tourner 658 tests dans la même JVM), une pause GC tombée dans la
 * fenêtre mesurée gonfle la moyenne d'une passe — elle ne doit pas décider d'un budget.
 * Le tableau imprimé garde les essais bruts, l'assertion retient le meilleur.
 *
 * Relevé r630 antérieur (hors de cet arbre) pour comparaison : 1–2 µs/frame, 39–70 µs/résumé.
 */
class LatencyBenchTest {

    private val bench = FrameBench(warmup = WARMUP, iterations = ITERATIONS)

    /** Instantanés de référence : les sept marques du banc, en nanosecondes. */
    private val marksNs = longArrayOf(
        24_500_000L, 33_300_000L, 218_000_000L,
        644_200_000L, 665_700_000L, 667_400_000L, 684_800_000L,
    )

    private val stagesUs = longArrayOf(24_500L, 8_800L, 184_700L, 426_200L, 21_500L, 1_700L, 17_400L)

    @Test
    fun `cout d instrumentation borne`() {
        val trace = LatencyTrace()
        val profile = LatencyProfile()
        val frame = LatencyFrame.of(stagesUs, SERVER_US)

        val traceUs = measureBest("LatencyTrace : start + 7 marques + instantané") {
            trace.start(0L)
            var i = 0
            while (i < marksNs.size) {
                trace.mark(LatencyStage.entries[i], marksNs[i])
                i++
            }
            trace.snapshot(SERVER_US).perceivedUs
        }

        val summaryUs = measureBest("LatencyProfile : record + résumé") {
            profile.record(frame)
            profile.summary().perceivedP50Us
        }

        bench.measure("LatencySummary : ligne de journal") { profile.summary().line().length.toLong() }

        println(bench.report("COÛT INSTRUMENTATION LATENCE (JVM, µs/op — meilleur de $ATTEMPTS essais)"))

        assertUnder("LatencyTrace : start + 7 marques + instantané", traceUs, TRACE_US)
        assertUnder("LatencyProfile : record + résumé", summaryUs, SUMMARY_US)
    }

    /** Coût unitaire de [op] : le meilleur des [ATTEMPTS] passes (pauses de l'hôte écartées). */
    private fun measureBest(label: String, op: LongSupplier): Double {
        var best = Double.MAX_VALUE
        var attempt = 1
        while (attempt <= ATTEMPTS) {
            best = minOf(best, bench.measure("$label — essai $attempt", op).microsPerOp)
            attempt++
        }
        return best
    }

    /** Échoue si [micros] dépasse [limitUs] (ou si rien n'a été mesuré). */
    private fun assertUnder(label: String, micros: Double, limitUs: Double) {
        assertTrue("« $label » non mesuré", micros != Double.MAX_VALUE)
        assertTrue(
            "« $label » : %.2f µs/op, budget %.2f µs/op".format(micros, limitUs),
            micros < limitUs,
        )
    }

    private companion object {
        /** Durée serveur d'une frame du banc (`elapsed_ms` = 300 ms). */
        const val SERVER_US = 300_000L

        /** Budget d'un enregistrement de frame : l'instrumentation doit rester invisible. */
        const val TRACE_US = 2.0

        /** Budget d'un résumé de statut : sept étages en p50/p95, une fois par seconde. */
        const val SUMMARY_US = 70.0

        /** Passes de chauffe par essai (compilation JIT, caches). */
        const val WARMUP = 100

        /** Passes chronométrées par essai : au-delà des 200 exigés par la mesure. */
        const val ITERATIONS = 400

        /** Essais par opération ; le meilleur parle du code, pas de l'hôte. */
        const val ATTEMPTS = 5
    }
}