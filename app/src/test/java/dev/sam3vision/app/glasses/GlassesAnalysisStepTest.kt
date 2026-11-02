package dev.sam3vision.app.glasses

import dev.sam3vision.core.model.Analysis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maillon frame → analyse → voix : chaque passage journalise son E2E (`analyse`,
 * `say`, `total`) sur l'horloge injectée — le chemin lunettes → SAM → voix a enfin
 * son mètre, sans lui le budget E2E ne se prouve pas.
 */
class GlassesAnalysisStepTest {

    private fun frame() = JpegFrame(
        base64 = "QQ==",
        width = 320,
        height = 240,
        capturedAtMs = 1_000L,
    )

    @Test
    fun `dit journalise analyse, say et total`() = runBlocking {
        var now = 1_000L
        val journal = mutableListOf<String>()
        val step = GlassesAnalysisStep(
            analyze = { now += 800L; Result.success(Analysis("une chaise", emptyList(), emptyList())) },
            say = { now += 5L; null },
            clockMs = { now },
        )

        step.onFrame(frame()) { journal += it }

        assertTrue(
            "ligne E2E attendue : $journal",
            journal.contains("glasses-e2e analyse 800 ms · say 5 ms · total 805 ms"),
        )
    }

    @Test
    fun `muet journalise analyse seule, say absent`() = runBlocking {
        var now = 2_000L
        val journal = mutableListOf<String>()
        val step = GlassesAnalysisStep(
            analyze = { now += 300L; Result.success(Analysis("   ", emptyList(), emptyList())) },
            clockMs = { now },
        )

        step.onFrame(frame()) { journal += it }

        assertTrue(
            "ligne E2E attendue : $journal",
            journal.contains("glasses-e2e analyse 300 ms · say — · total 300 ms"),
        )
    }

    @Test
    fun `ligne e2e au format recette`() {
        assertEquals(
            "glasses-e2e analyse 812 ms · say 3 ms · total 815 ms",
            glassesE2eLine(812L, 3L, 815L),
        )
    }
}
