package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Table centrale d'antirépétition (SPEC v4) : chaque clé a sa durée, `allow`
 * tait la redite dans la fenêtre puis libère à l'échéance pile, les clés
 * sans délai passent toujours, les clés « 1 fois » parlent une fois par
 * séance/vue, `base:état` change immédiatement, les clés inconnues ne
 * bloquent jamais.
 */
class AntiRepeatTest {

    @Before
    fun reset() {
        AntiRepeat.reset()
    }

    @Test
    fun `table des durees = spec v4`() {
        assertEquals(4_000L, AntiRepeat.durationMs(AntiRepeat.KEY_CAR_MOVING))
        assertEquals(2_000L, AntiRepeat.durationMs(AntiRepeat.KEY_HUMAN_SOFT))
        assertEquals(0L, AntiRepeat.durationMs(AntiRepeat.KEY_HUMAN_IMMINENT))
        assertEquals(0L, AntiRepeat.durationMs(AntiRepeat.KEY_GPS_REMINDER))
        assertEquals(4_000L, AntiRepeat.durationMs(AntiRepeat.KEY_FENCE))
        assertEquals(2_000L, AntiRepeat.durationMs(AntiRepeat.KEY_CURB))
        assertEquals(2_000L, AntiRepeat.durationMs(AntiRepeat.KEY_STAIRS))
        assertEquals(500L, AntiRepeat.durationMs(AntiRepeat.KEY_STAIRS_COUNT))
        assertEquals(2_000L, AntiRepeat.durationMs(AntiRepeat.KEY_POLE))
        assertEquals(0L, AntiRepeat.durationMs(AntiRepeat.KEY_POLE_AVOID))
        assertEquals(2_000L, AntiRepeat.durationMs(AntiRepeat.KEY_MISC))
        assertEquals(AntiRepeat.ONCE_MS, AntiRepeat.durationMs(AntiRepeat.KEY_CROSSWALK_ANNOUNCE))
        assertEquals(0L, AntiRepeat.durationMs(AntiRepeat.KEY_CROSSWALK_ALIGN))
        assertEquals(AntiRepeat.ONCE_MS, AntiRepeat.durationMs(AntiRepeat.KEY_CROSSWALK_ARRIVAL))
        assertEquals(10_000L, AntiRepeat.durationMs(AntiRepeat.KEY_SIDEWALK))
        assertEquals(3_000L, AntiRepeat.durationMs(AntiRepeat.KEY_VERDICT))
    }

    @Test
    fun `cles stables = libelles du contrat`() {
        assertEquals("car-moving", AntiRepeat.KEY_CAR_MOVING)
        assertEquals("human-soft", AntiRepeat.KEY_HUMAN_SOFT)
        assertEquals("human-imminent", AntiRepeat.KEY_HUMAN_IMMINENT)
        assertEquals("gps-reminder", AntiRepeat.KEY_GPS_REMINDER)
        assertEquals("fence", AntiRepeat.KEY_FENCE)
        assertEquals("curb", AntiRepeat.KEY_CURB)
        assertEquals("stairs", AntiRepeat.KEY_STAIRS)
        assertEquals("stairs-count", AntiRepeat.KEY_STAIRS_COUNT)
        assertEquals("pole", AntiRepeat.KEY_POLE)
        assertEquals("pole-avoid", AntiRepeat.KEY_POLE_AVOID)
        assertEquals("crosswalk-announce", AntiRepeat.KEY_CROSSWALK_ANNOUNCE)
        assertEquals("crosswalk-align", AntiRepeat.KEY_CROSSWALK_ALIGN)
        assertEquals("crosswalk-arrival", AntiRepeat.KEY_CROSSWALK_ARRIVAL)
        assertEquals("sidewalk", AntiRepeat.KEY_SIDEWALK)
        assertEquals("verdict", AntiRepeat.KEY_VERDICT)
    }

    @Test
    fun `redite tue dans la fenetre, liberee a l echeance pile`() {
        val key = AntiRepeat.KEY_POLE // 2 s

        assertTrue(AntiRepeat.allow(key, 1_000L))
        assertFalse(AntiRepeat.allow(key, 1_001L))
        assertFalse(AntiRepeat.allow(key, 2_999L))
        assertTrue("échéance pile : délai écoulé", AntiRepeat.allow(key, 3_000L))
    }

    @Test
    fun `cles independantes les unes des autres`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_POLE, 1_000L))

        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_MISC, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CURB, 1_000L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_POLE, 1_500L))
    }

    @Test
    fun `durees sans delai = toujours autorise, rien memorise`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_IMMINENT, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_IMMINENT, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_GPS_REMINDER, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_GPS_REMINDER, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ALIGN, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ALIGN, 1_000L))
    }

    @Test
    fun `cles inconnues = jamais bloquantes`() {
        assertTrue(AntiRepeat.allow("porte-et-murs", 1_000L))
        assertTrue(AntiRepeat.allow("porte-et-murs", 1_000L))
        assertTrue(AntiRepeat.allow("objet-cherche", 1_000L))
        assertEquals(0L, AntiRepeat.durationMs("n-importe-quoi"))
    }

    @Test
    fun `annonce et arrivee passage = une fois jusqu-a l oubli`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, 1_000L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, 2_000L))
        assertFalse("même loin dans la séance : une fois", AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, 3_600_000L))

        AntiRepeat.forget(AntiRepeat.KEY_CROSSWALK_ANNOUNCE)
        assertTrue("nouvelle vue : reparle", AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, 3_700_000L))

        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ARRIVAL, 1_000L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ARRIVAL, 60_000L))
        AntiRepeat.reset()
        assertTrue("nouvelle séance : reparle", AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ARRIVAL, 61_000L))
    }

    @Test
    fun `verdict = 3 s, changement immediat par etat`() {
        assertTrue(AntiRepeat.allow("verdict:cross", 1_000L))
        assertFalse(AntiRepeat.allow("verdict:cross", 3_999L))
        assertTrue(AntiRepeat.allow("verdict:cross", 4_000L))

        assertTrue("état nouveau : parle aussitôt", AntiRepeat.allow("verdict:dont-cross", 4_100L))
        assertFalse(AntiRepeat.allow("verdict:dont-cross", 4_200L))
        assertFalse("l'autre état garde son tour", AntiRepeat.allow("verdict:cross", 4_200L))
    }

    @Test
    fun `reset oublie tout, forget oublie une cle`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, 1_000L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_SIDEWALK, 1_000L))

        AntiRepeat.forget(AntiRepeat.KEY_FENCE)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, 2_000L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_SIDEWALK, 2_000L))

        AntiRepeat.reset()
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_SIDEWALK, 3_000L))
    }
}
