package dev.sam3vision.core.keyframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banc de simulation de la lane L13 : 8 scénarios × 4 politiques, à horloge virtuelle.
 *
 * **Tous les chiffres de ce banc sont calculés, aucun n'est mesuré** : ni téléphone, ni GPU, ni
 * réseau. Le modèle d'erreur de reprojection est synthétique (voir [KeyframeScene]) ; c'est u11
 * qui doit fournir l'erreur réelle pour recaler `errorThreshold`.
 *
 * L'invariant qui compte : `violations == 0` **partout** (budget d'une seconde, un seul keyframe
 * en vol, plafond d'âge demandé quand il est dû).
 */
class KeyframeSimTest {

    private fun tallies(label: String): List<SimTally> {
        val scenario = scenarios().first { it.label == label }
        return policies().map { runScenario(scenario, it) }
    }

    private fun List<SimTally>.by(policy: String): SimTally = first { it.policy == policy }

    private fun assertCleanAndShow(label: String, tallies: List<SimTally>) {
        tallies.forEach {
            assertEquals("$label/${it.policy} : décisions non saines", 0, it.violations)
            println(it.row())
        }
    }

    @Test
    fun `trepied - le scheduler ne demande pas plus que la cadence fixe`() {
        val tallies = tallies("trepied")
        assertCleanAndShow("trepied", tallies)
        assertEquals(tallies.by("fixe-500ms").requests, tallies.by("scheduler").requests)
        assertTrue(
            "la veille statique doit demander moins",
            tallies.by("scheduler+veille").requests < tallies.by("scheduler").requests,
        )
        assertEquals(0f, tallies.by("scheduler").meanError, 0f)
    }

    @Test
    fun `mouvement lent - le scheduler demande plus souvent que la cadence fixe`() {
        val tallies = tallies("mouvement_lent")
        assertCleanAndShow("mouvement_lent", tallies)
        assertTrue(
            "la dérive doit faire réagir le scheduler",
            tallies.by("scheduler").requests > tallies.by("fixe-500ms").requests,
        )
    }

    @Test
    fun `mouvement rapide - l erreur maximale reste sous celle d une cadence fixe`() {
        val tallies = tallies("mouvement_rapide")
        assertCleanAndShow("mouvement_rapide", tallies)
        assertTrue(
            "erreur max ${tallies.by("scheduler").maxError} vs ${tallies.by("fixe-500ms").maxError}",
            tallies.by("scheduler").maxError < tallies.by("fixe-500ms").maxError,
        )
    }

    @Test
    fun `coupure de scene - recalage immediat, la cadence fixe ne voit rien`() {
        val tallies = tallies("coupure_scene")
        assertCleanAndShow("coupure_scene", tallies)
        assertTrue("le scheduler doit voir la coupure", tallies.by("scheduler").sceneTriggers > 0)
        assertEquals("une cadence aveugle ne la voit pas", 0, tallies.by("fixe-500ms").sceneTriggers)
        val schedulerMs = tallies.by("scheduler").recalageMs
        assertTrue("recalage annoncé : $schedulerMs ms", schedulerMs in 0L..250L)
        assertTrue(
            "recalage $schedulerMs ms vs ${tallies.by("fixe-500ms").recalageMs} ms (cadence fixe)",
            schedulerMs < tallies.by("fixe-500ms").recalageMs,
        )
    }

    @Test
    fun `masque perdu - LOST demande tout de suite, la cadence fixe reste aveugle`() {
        val tallies = tallies("masque_perdu")
        assertCleanAndShow("masque_perdu", tallies)
        assertTrue("LOST doit être déclenché", tallies.by("scheduler").lostTriggers > 0)
        assertEquals(0, tallies.by("fixe-500ms").lostTriggers)
    }

    @Test
    fun `panne de 2 s - pas de rafale et reprise apres la panne`() {
        val tallies = tallies("panne_2s")
        assertCleanAndShow("panne_2s", tallies)
        val scheduler = tallies.by("scheduler")
        assertEquals("une panne se voit dans les réponses, pas dans les délais", 0, scheduler.timeouts)
        assertTrue("reprise attendue après la panne", scheduler.successes > 0)
        assertTrue(
            "moins de requêtes que la cadence rapide aveugle",
            scheduler.requests < tallies.by("fixe-200ms").requests,
        )
    }

    @Test
    fun `sam muet - une demande perdue puis le repli, pas de boucle`() {
        val tallies = tallies("sam_muet")
        assertCleanAndShow("sam_muet", tallies)
        val scheduler = tallies.by("scheduler")
        assertEquals(2, scheduler.requests)
        assertEquals(1, scheduler.timeouts)
        assertEquals(0, scheduler.successes)
    }

    @Test
    fun `telephone vers A100 - le reseau domine, toutes les politiques se valent`() {
        val tallies = tallies("telephone_a100")
        assertCleanAndShow("telephone_a100", tallies)
        val scheduler = tallies.by("scheduler")
        assertEquals(
            "à 1 200 ms d'aller-retour, le scheduler ne peut pas faire mieux qu'une cadence fixe",
            tallies.by("fixe-500ms").requests,
            scheduler.requests,
        )
        assertTrue("l'erreur reste haute : c'est physique", scheduler.meanError > 0.5f)
    }

    @Test
    fun `le tableau de simulation est ecrit dans core build`() {
        val tallies = scenarios().flatMap { scenario -> policies().map { runScenario(scenario, it) } }
        assertEquals(scenarios().size * policies().size, tallies.size)
        assertEquals("une seule violation gâche le banc", 0, tallies.sumOf { it.violations })
        val file = writeSimTable(simTable(tallies))
        val labels = scenarios().map { it.label }
        val rows = file.readText().lines().count { line -> labels.any { line.startsWith(it) } }
        println("tableau écrit : ${file.absolutePath} — $rows lignes de résultats, 0 violation")
        assertTrue(file.isFile)
        assertEquals(tallies.size, rows)
    }
}