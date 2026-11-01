package dev.sam3vision.core.live

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identité des personnes d'une frame à l'autre (audit foule du 01/11, patch P0).
 *
 * Ce que ces tests verrouillent : un id stable sur une approche, une fermeture
 * qui a le bon signe et la bonne valeur, un temps de contact qui décroît quand
 * on se rapproche, et surtout **pas d'échange d'identité** quand deux personnes
 * passent au même `centerX` — c'est la distance qui les départage.
 */
class PersonTrackTest {

    private fun label(x: Float, meters: Float? = null) = PlacedLabel(
        label = "personne",
        centerX = x,
        centerY = 0.5f,
        meters = meters,
    )

    @Test
    fun `une personne qui avance garde son id et sa fermeture`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.5f, 4.0f))).single()
        val b = tracker.onFrame(650L, listOf(label(0.5f, 3.0f))).single()
        val c = tracker.onFrame(1_300L, listOf(label(0.5f, 2.0f))).single()
        val d = tracker.onFrame(1_950L, listOf(label(0.5f, 1.0f))).single()

        assertEquals(a.trackId, b.trackId)
        assertEquals(a.trackId, c.trackId)
        assertEquals(a.trackId, d.trackId)
        // 1 m fermé en 650 ms = 1,54 m/s : la valeur attendue, au lissage près.
        assertEquals(1.5f, c.closingMps!!, 0.3f)
        assertTrue(b.ttcS!! > c.ttcS!!)
        assertTrue(c.ttcS!! > d.ttcS!!)
    }

    @Test
    fun `une personne qui s eloigne n a pas de temps de contact`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.5f, 2.0f))).single()
        val b = tracker.onFrame(650L, listOf(label(0.5f, 2.5f))).single()
        val c = tracker.onFrame(1_300L, listOf(label(0.5f, 3.0f))).single()

        assertEquals(a.trackId, c.trackId)
        assertTrue("fermeture négative = il s'éloigne", c.closingMps!! < -0.5f)
        assertNull("on ne prévoit pas un contact qui s'éloigne", c.ttcS)
        assertNull(b.ttcS)
    }

    @Test
    fun `deux personnes au meme centre mais distances opposees ne s echangent pas leurs ids`() {
        val tracker = PersonTracker()
        val first = tracker.onFrame(0L, listOf(label(0.5f, 4.0f), label(0.5f, 2.0f)))
        val second = tracker.onFrame(650L, listOf(label(0.5f, 3.3f), label(0.5f, 2.7f)))

        assertNotEquals(first[0].trackId, first[1].trackId)
        // Le croisement : même cadran, l'une arrive, l'autre part — la distance tranche.
        assertEquals(first[0].trackId, second[0].trackId)
        assertEquals(first[1].trackId, second[1].trackId)
        assertTrue("celle qui arrive se rapproche", second[0].closingMps!! > 0f)
        assertTrue("celle qui part s'éloigne", second[1].closingMps!! < 0f)
        assertNull(second[1].ttcS)
    }

    @Test
    fun `une personne qui disparait deux frames revient avec le meme id`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.4f, 3.0f))).single()
        val b = tracker.onFrame(400L, listOf(label(0.4f, 2.9f))).single()
        assertEquals(emptyList<TrackedLabel>(), tracker.onFrame(800L, emptyList()))
        assertEquals(emptyList<TrackedLabel>(), tracker.onFrame(1_200L, emptyList()))
        val back = tracker.onFrame(1_600L, listOf(label(0.4f, 2.8f))).single()

        assertEquals(a.trackId, b.trackId)
        assertEquals("1,2 s d'absence < 1,5 s : c'est la même personne", a.trackId, back.trackId)
        assertEquals("5 frames depuis la première vue, trou compris", 5, back.ageFrames)
    }

    @Test
    fun `un trou plus long que la veille donne un nouvel id`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.4f, 3.0f))).single()
        val b = tracker.onFrame(400L, listOf(label(0.4f, 2.9f))).single()
        val late = tracker.onFrame(2_400L, listOf(label(0.4f, 2.8f))).single()

        assertEquals(a.trackId, b.trackId)
        assertNotEquals("2 s d'absence : quelqu'un d'autre", a.trackId, late.trackId)
        assertEquals(1, late.ageFrames)
    }

    @Test
    fun `reset oublie les pistes et ne reutilise jamais les ids`() {
        val tracker = PersonTracker()
        val before = tracker.onFrame(0L, listOf(label(0.5f, 2.0f))).single()
        tracker.reset()
        val after = tracker.onFrame(1_300L, listOf(label(0.5f, 2.0f))).single()

        assertNotEquals("un id d'avant séance ne désigne personne après", before.trackId, after.trackId)
        assertEquals("identité neuve : pas d'historique", 1, after.ageFrames)
        assertNull("et pas de vitesse héritée", after.closingMps)
    }

    @Test
    fun `trois personnes dont une sans distance restent suivies et distinctes`() {
        val tracker = PersonTracker()
        val first = tracker.onFrame(0L, listOf(label(0.2f, 2.0f), label(0.5f, 3.0f), label(0.85f)))
        val second = tracker.onFrame(650L, listOf(label(0.2f, 1.9f), label(0.5f, 2.9f), label(0.85f)))

        assertEquals(3, second.size)
        assertEquals(first[2].trackId, second[2].trackId)
        assertEquals(first[0].trackId, second[0].trackId)
        assertEquals(first[1].trackId, second[1].trackId)
        val ids = second.map { it.trackId }.distinct()
        assertEquals("trois personnes, trois identités", 3, ids.size)
        // Sans métrique, l'identité tient au seul centerX : elle existe, la vitesse non.
        assertNull(second[2].meters)
        assertNull(second[2].closingMps)
        assertNull(second[2].ttcS)
        assertNotNull(second[0].closingMps)
    }

    @Test
    fun `jamais deux labels sur la meme piste dans une frame`() {
        val tracker = PersonTracker()
        val same = tracker.onFrame(0L, listOf(label(0.5f, 2.0f), label(0.5f, 2.0f)))

        assertEquals(2, same.size)
        assertNotEquals("le second label ouvre sa propre piste", same[0].trackId, same[1].trackId)
        val next = tracker.onFrame(650L, listOf(label(0.5f, 1.9f), label(0.5f, 2.1f)))
        assertEquals("toujours deux pistes, jamais une pour deux", 2, next.map { it.trackId }.distinct().size)
    }

    @Test
    fun `le label rendu est une copie identifiee sans rien perdre`() {
        val tracker = PersonTracker()
        val raw = PlacedLabel("personne", 0.4f, 0.6f, 3.0f, x0 = 0.3f, x1 = 0.5f)
        val tracked = tracker.onFrame(0L, listOf(raw)).single()

        assertEquals("personne", tracked.label.label)
        assertEquals(0.4f, tracked.label.centerX, 0f)
        assertEquals(0.6f, tracked.label.centerY, 0f)
        assertEquals(3.0f, tracked.label.meters!!, 0f)
        assertEquals(0.3f, tracked.label.x0!!, 0f)
        assertEquals(0.5f, tracked.label.x1!!, 0f)
        assertEquals(tracked.trackId, tracked.label.trackId)
        assertEquals(tracked.closingMps, tracked.label.closingMps)
        assertEquals(3.0f, tracked.meters!!, 0f)
    }

    @Test
    fun `la fermeture reste inconnue avant deux mesures`() {
        val tracker = PersonTracker()
        val first = tracker.onFrame(0L, listOf(label(0.5f, 2.0f))).single()
        val second = tracker.onFrame(650L, listOf(label(0.5f, 1.8f))).single()

        assertNull("un point ne fait pas une pente", first.closingMps)
        assertNull(first.label.closingMps)
        assertNull(first.ttcS)
        assertNotNull(second.closingMps)
        assertEquals(1, first.ageFrames)
        assertEquals(2, second.ageFrames)
    }

    @Test
    fun `une mesure immobile ne declare pas d approche`() {
        val tracker = PersonTracker()
        tracker.onFrame(0L, listOf(label(0.5f, 2.0f)))
        val still = tracker.onFrame(650L, listOf(label(0.5f, 2.02f))).single()

        // 3 cm en 650 ms : sous le bruit de mesure, ce n'est pas un mouvement.
        assertEquals(0f, still.closingMps!!, 0f)
        assertNull("immobile : pas de contact prévisible", still.ttcS)
    }

    @Test
    fun `un label trop ecarte lateralement n herite pas de la piste`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.5f, 2.0f))).single()
        val far = tracker.onFrame(650L, listOf(label(0.9f, 2.0f))).single()

        assertNotEquals("0,4 de centerX : hors de la porte de 0,14", a.trackId, far.trackId)
    }

    @Test
    fun `une distance trop differente n herite pas de la piste`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.5f, 4.0f))).single()
        val jump = tracker.onFrame(650L, listOf(label(0.5f, 2.0f))).single()

        assertNotEquals("2 m d'écart en une frame : ce n'est pas la même personne", a.trackId, jump.trackId)
    }

    @Test
    fun `les labels sont rendus dans l ordre d entree avec la bonne identite`() {
        val tracker = PersonTracker()
        val first = tracker.onFrame(0L, listOf(label(0.1f, 4.0f), label(0.5f, 3.0f), label(0.9f, 2.0f)))
        val shuffled = tracker.onFrame(650L, listOf(label(0.9f, 1.9f), label(0.1f, 3.9f), label(0.5f, 2.9f)))

        assertEquals(listOf(0.9f, 0.1f, 0.5f), shuffled.map { it.label.centerX })
        assertEquals(first[2].trackId, shuffled[0].trackId)
        assertEquals(first[0].trackId, shuffled[1].trackId)
        assertEquals(first[1].trackId, shuffled[2].trackId)
    }

    @Test
    fun `une frame vide ne rend rien et ne tue pas les pistes`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.6f, 2.5f))).single()
        assertEquals(emptyList<TrackedLabel>(), tracker.onFrame(650L, emptyList()))
        val back = tracker.onFrame(1_300L, listOf(label(0.6f, 2.4f))).single()

        assertEquals(a.trackId, back.trackId)
        assertEquals(3, back.ageFrames)
    }

    @Test
    fun `une frame sans mesure garde la piste et sa derniere distance`() {
        val tracker = PersonTracker()
        val a = tracker.onFrame(0L, listOf(label(0.5f, 3.0f))).single()
        val blind = tracker.onFrame(650L, listOf(label(0.5f))).single()
        val c = tracker.onFrame(1_300L, listOf(label(0.5f, 2.0f))).single()

        assertEquals(a.trackId, blind.trackId)
        assertEquals(a.trackId, c.trackId)
        assertNull(blind.meters)
        assertNull("une seule mesure datée : rien à conclure", blind.closingMps)
        assertTrue("3 m → 2 m en 1,3 s : ça se rapproche", c.closingMps!! > 0.5f)
        assertEquals(2.0f, c.meters!!, 0f)
        assertEquals(2.0f / c.closingMps!!, c.ttcS!!, 0.05f)
    }

    @Test
    fun `des frames concurrentes ne plantent pas le suivi`() {
        // Non-régression : `LiveProbe.dispatch` lance un `runFrame` par frame
        // sans garde anti-recouvrement, et le `PersonTracker` est unique par
        // séance — deux `onFrame` entrelacés tuaient l'app (`IndexOutOfBounds`
        // dans `sweep`, constaté sur émulateur le 01/11).
        val tracker = PersonTracker()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = List(8) { t ->
            Thread {
                start.await()
                try {
                    repeat(300) { i ->
                        tracker.onFrame(
                            (t * 300L + i) * 40L,
                            listOf(label(0.25f + t * 0.03f, 4.0f), label(0.75f, 6.0f)),
                        )
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }
        assertTrue("plantage concurrent : ${errors.peek()}", errors.isEmpty())
    }
}
