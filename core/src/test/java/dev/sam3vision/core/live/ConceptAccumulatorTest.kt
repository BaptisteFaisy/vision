package dev.sam3vision.core.live

import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accumulateur `concept → derniers objets` : la voix et l'overlay lisent la
 * fusion des 3 groupes, jamais un groupe seul — un obstacle suivi reste visible
 * pendant que les autres groupes tournent, un négatif frais l'efface, un concept
 * sans refresh expire à 1000 ms, la mémoire est bornée.
 */
class ConceptAccumulatorTest {

    private fun obj(label: String, score: Float = 0.9f, x1: Float = 10f) = SegObject(
        label = label, score = score, box = listOf(x1, 20f, 60f, 80f),
        maskPngB64 = "", maskEncoding = MASK_ENCODING_NONE,
    )

    private val groups = PromptRotation.split(LivePrompts.default)

    @Test
    fun `un groupe rafraichi remplace ses seuls concepts`() {
        val acc = ConceptAccumulator()
        val first = acc.update(groups[0], listOf(obj("person"), obj("car")), 0L)
        assertEquals(2, first.size)
        // Groupe 1 sans voiture : la voiture du groupe 0 survit (pas son refresh).
        val merged = acc.update(groups[1], listOf(obj("person")), 170L)
        assertTrue(merged.any { it.label == "car" })
        assertTrue(merged.any { it.label == "person" })
    }

    @Test
    fun `scenario voix  - obstacle suivi puis groupes tournants, jamais de trou`() {
        val acc = ConceptAccumulator()
        val carGroup = groups.indexOfFirst { it.contains("car") }
        val others = groups.filterIndexed { i, _ -> i != carGroup }
        // Frame 0 : la voiture est vue dans son groupe.
        var seen = acc.update(groups[carGroup], listOf(obj("person"), obj("car")), 0L)
        assertTrue(seen.any { it.label == "car" })
        // Frames 1-2 : les autres groupes tournent, la voiture reste servie.
        seen = acc.update(others[0], listOf(obj("person")), 170L)
        assertTrue("voiture servie pendant le groupe ${others[0]}", seen.any { it.label == "car" })
        seen = acc.update(others[1], listOf(obj("person")), 340L)
        assertTrue("voiture servie pendant le groupe ${others[1]}", seen.any { it.label == "car" })
    }

    @Test
    fun `negatif frais  - concept renvoye sans objet, objets effaces`() {
        val acc = ConceptAccumulator()
        acc.update(groups[0], listOf(obj("person"), obj("car")), 0L)
        val merged = acc.update(groups[0], listOf(obj("person")), 510L)
        assertTrue("voiture vraiment partie : plus servie", merged.none { it.label == "car" })
        assertTrue(merged.any { it.label == "person" })
    }

    @Test
    fun `refresh remplace la boite stale, pas d'empilement`() {
        val acc = ConceptAccumulator()
        acc.update(groups[0], listOf(obj("person", x1 = 10f)), 0L)
        val merged = acc.update(groups[0], listOf(obj("person", x1 = 90f)), 170L)
        val persons = merged.filter { it.label == "person" }
        assertEquals(1, persons.size)
        assertEquals(90f, persons[0].box[0])
    }

    @Test
    fun `expiry 1000 ms sans refresh, garde a egalite`() {
        val acc = ConceptAccumulator()
        acc.update(listOf("car"), listOf(obj("car")), 0L)
        assertEquals(1, acc.snapshot(1000L).size)
        assertTrue(acc.snapshot(1001L).isEmpty())
        assertTrue(acc.concepts(1001L).isEmpty())
    }

    @Test
    fun `un refresh repart pour 1000 ms`() {
        val acc = ConceptAccumulator()
        acc.update(listOf("car"), listOf(obj("car")), 0L)
        acc.update(listOf("car"), listOf(obj("car")), 900L)
        assertEquals(1, acc.snapshot(1900L).size)
        assertTrue(acc.snapshot(1901L).isEmpty())
    }

    @Test
    fun `label inattendu garde sous son nom, jamais jete`() {
        val acc = ConceptAccumulator()
        val merged = acc.update(listOf("person"), listOf(obj("person"), obj("zebra")), 0L)
        assertEquals(2, merged.size)
        assertTrue(acc.concepts(0L).contains("zebra"))
    }

    @Test
    fun `borne memoire  - 64 objets, meilleurs scores gardes`() {
        val acc = ConceptAccumulator()
        val many = (1..100).map { obj("person", score = it / 100f) }
        val merged = acc.update(listOf("person"), many, 0L)
        assertEquals(ConceptAccumulator.MAX_OBJECTS, merged.size)
        assertTrue("les plus faibles sautent", merged.all { it.score >= 0.37f })
        assertTrue(merged.any { it.score == 1.0f })
    }

    @Test
    fun `reset oublie tout`() {
        val acc = ConceptAccumulator()
        acc.update(groups[0], listOf(obj("person"), obj("car")), 0L)
        acc.reset()
        assertTrue(acc.snapshot(10L).isEmpty())
    }

    @Test
    fun `cycle complet  - 3 groupes, fusion exhaustive`() {
        val acc = ConceptAccumulator()
        var merged = emptyList<SegObject>()
        groups.forEachIndexed { i, group ->
            val seen = group.map { obj(it) }
            merged = acc.update(group, seen, i * 170L)
        }
        assertEquals(LivePrompts.default.toSet(), merged.map { it.label }.toSet())
    }
}
