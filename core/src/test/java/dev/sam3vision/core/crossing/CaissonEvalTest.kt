package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Éval JVM détection→verdict sur caissons type SAM + rappel petits objets
 * versionné (`feuxevel/RECALL-v2.json`).
 *
 * Les 6 cas `green-h*` balayent la taille du caisson (64 → 8 px de haut) :
 * rappel piné 3/6 depuis la v2 (31/10, ordre user : détection au plus
 * large, élancement 1,0 — h32/h24/h16 admis, h64 refusé par l'arbre,
 * h12/h08 trop petits). Les cas `real-*` prouvent que la chaîne rend du
 * vert sur de vrais crops.
 * Joue dans `:core:testDebugUnitTest` (CI existante, aucun workflow ajouté).
 */
class CaissonEvalTest {

    @Test
    fun `la chaine detection-verdict suit le dore v2`() {
        val golden = loadGolden(javaClass.classLoader)
        assertEquals("version du doré", 2, golden.version)
        val cases = evalCases(javaClass.classLoader)
        assertEquals("cas dorés", golden.verdicts.keys.sorted(), cases.map { it.id }.sorted())
        for (c in cases) {
            assertEquals("${c.id} : verdict", golden.verdicts[c.id], runChain(c).name)
        }
    }

    @Test
    fun `le rappel petits objets vaut le dore v2`() {
        val cases = evalCases(javaClass.classLoader)
        val verdicts = cases.associate { it.id to runChain(it) }
        val golden = loadGolden(javaClass.classLoader)
        assertEquals(
            "clés du rappel",
            setOf("synthetic_green", "synthetic_green_grand", "synthetic_green_petit", "false_green"),
            golden.recall.keys,
        )
        val greens = cases.map { it.id }.filter { it.startsWith("green-") }
        assertEquals("rappel verts", golden.recall["synthetic_green"], recallOf(greens, verdicts))
        assertEquals("rappel grands", golden.recall["synthetic_green_grand"], recallOf(greens.filter { heightOf(it) > 24 }, verdicts))
        assertEquals("rappel petits", golden.recall["synthetic_green_petit"], recallOf(greens.filter { heightOf(it) <= 24 }, verdicts))
        val reds = cases.filter { it.truth == CrossingState.Red }.map { it.id }
        assertEquals("faux verts", golden.recall["false_green"], recallOf(reds, verdicts))
    }

    @Test
    fun `aucun vert sur les cas non verts`() {
        for (c in evalCases(javaClass.classLoader)) {
            if (c.truth == CrossingState.Red) {
                assertEquals("${c.id} : jamais vert", CrossingState.Red, runChain(c))
            }
        }
    }

    @Test
    fun `la detection choisit le caisson, pas l'eclat ni la voiture`() {
        for (c in evalCases(javaClass.classLoader)) {
            val norm = detectBox(c.rect)
            assertNotNull("${c.id} : boîte", norm)
            val want = c.rect.map { it / CAISSON_FRAME.toFloat() }
            for (i in 0..3) assertEquals("${c.id} [$i]", want[i], norm!![i], 1e-6f)
        }
    }

    /** Hauteur du caisson lue dans l'id (`green-h24` → 24). */
    private fun heightOf(id: String): Int = id.substringAfter("green-h").toInt()
}
