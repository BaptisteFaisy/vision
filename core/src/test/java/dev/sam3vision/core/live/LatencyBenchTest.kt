package dev.sam3vision.core.live

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coût de l'instrumentation de latence, mesuré en JVM : la trace ne doit pas fausser ce
 * qu'elle mesure — **≤ 2 µs/frame** et **≤ 70 µs/résumé** (budget de la campagne u07).
 *
 * Le banc imprime son tableau (visible dans le rapport JUnit) et n'échoue que sur ces deux
 * bornes, celles du contrat. Relevé r630 du 24/10 pour comparaison : 1–2 µs/frame,
 * 39–70 µs/résumé.
 */
class LatencyBenchTest {

    private val bench = FrameBench()

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

        bench.measure("LatencyTrace : start + 7 marques + instantané") {
            trace.start(0L)
            var i = 0
            while (i < marksNs.size) {
                trace.mark(LatencyStage.entries[i], marksNs[i])
                i++
            }
            trace.snapshot(SERVER_US).perceivedUs
        }

        bench.measure("LatencyProfile : record + résumé") {
            profile.record(frame)
            profile.summary().perceivedP50Us
        }

        bench.measure("LatencySummary : ligne de journal") {
            profile.summary().line().length.toLong()
        }

        println(bench.report("COÛT INSTRUMENTATION LATENCE (JVM, µs/op)"))

        assertUnder("LatencyTrace : start + 7 marques + instantané", TRACE_US)
        assertUnder("LatencyProfile : record + résumé", SUMMARY_US)
    }

    /** Échoue si [name] dépasse [limitUs] (ou n'a pas été mesuré). */
    private fun assertUnder(name: String, limitUs: Double) {
        val micros = bench.micros(name)
        assertTrue("« $name » non mesuré", micros >= 0.0)
        assertTrue(
            "« $name » : %.2f µs/op, budget %.2f µs/op".format(micros, limitUs),
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
    }
}