package dev.sam3vision.core.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v4 — antirépétition ([AntiRepeat]) et bus voix ([VoiceBus]).
 *
 * Contrat pur : clés stables par niveau et objet, durées de la table v4.
 * Le passage piéton et le GPS sont événementiels (pas de durée fixe) ; le
 * chat n'a aucune antirépétition ; le verdict repart immédiatement quand
 * il change. La prod implémente `object AntiRepeat { fun allow(key:
 * String, nowMs: Long): Boolean }` et `fun VoiceBus.post(level: Int, text:
 * String, clipId: String? = null)` avec ces mêmes clés et durées.
 */
class IseeSpecV4AntiRepeatTest {

    @Test
    fun `voitures = 4s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("car-moving", 0L))
        assertFalse(gate.allow("car-moving", 3_999L))
        assertTrue(gate.allow("car-moving", 4_000L))
    }

    @Test
    fun `humains = 2s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("human-soft", 0L))
        assertFalse(gate.allow("human-soft", 1_999L))
        assertTrue(gate.allow("human-soft", 2_000L))
    }

    @Test
    fun `humain imminent = immediat`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("human-imminent", 0L))
        assertTrue(gate.allow("human-imminent", 1L))
        assertTrue(gate.allow("human-imminent", 100L))
    }

    @Test
    fun `grillages = 4s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("fence", 0L))
        assertFalse(gate.allow("fence", 3_999L))
        assertTrue(gate.allow("fence", 4_000L))
    }

    @Test
    fun `bordures = 2s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("curb", 0L))
        assertFalse(gate.allow("curb", 1_999L))
        assertTrue(gate.allow("curb", 2_000L))
    }

    @Test
    fun `escaliers = 2s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("stairs", 0L))
        assertFalse(gate.allow("stairs", 1_999L))
        assertTrue(gate.allow("stairs", 2_000L))
    }

    @Test
    fun `poteaux et divers = 2s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("pole", 0L))
        assertFalse(gate.allow("pole", 1_999L))
        assertTrue(gate.allow("pole", 2_000L))
    }

    @Test
    fun `trottoirs = 10s`() {
        val gate = SpecAntiRepeat()

        assertTrue(gate.allow("sidewalk", 0L))
        assertFalse(gate.allow("sidewalk", 9_999L))
        assertTrue(gate.allow("sidewalk", 10_000L))
    }

    @Test
    fun `verdict = 3s avec changement immediat`() {
        val gate = SpecVerdictRepeat()

        assertTrue(gate.allow("Wait.", 0L))
        assertFalse("même verdict : tenu 3s", gate.allow("Wait.", 2_999L))
        assertTrue("verdict changé : immédiat", gate.allow("Cross.", 2_999L))
        assertTrue(gate.allow("Cross.", 6_000L))
    }

    @Test
    fun `passage et GPS = evenementiels, chat = aucune`() {
        assertEquals(
            setOf("crosswalk-announce", "crosswalk-align", "crosswalk-arrival"),
            CROSSWALK_KEYS,
        )
        assertTrue("rappel GPS : au silence de 15s, pas de durée AntiRepeat", "gps-reminder" !in TIMED_KEYS)
        assertTrue("chat : aucune antirépétition", TIMED_KEYS.none { it.startsWith("chat") })
    }

    @Test
    fun `cles stables = table de la spec`() {
        assertEquals(
            setOf(
                "car-moving", "human-soft", "human-imminent", "fence", "curb",
                "stairs", "pole", "sidewalk", "verdict",
            ),
            TIMED_KEYS,
        )
    }

    @Test
    fun `VoiceBus post = niveau texte et clip optionnel`() {
        val bus = SpecVoiceBus()

        bus.post(2, "Person at 9 o'clock, 4 meters away.")
        bus.post(6, "Stairs in 4 meters, 6 steps.", clipId = "stairs_4m_6steps")

        assertEquals(2, bus.calls[0].level)
        assertEquals("Person at 9 o'clock, 4 meters away.", bus.calls[0].text)
        assertNull(bus.calls[0].clipId)
        assertEquals("stairs_4m_6steps", bus.calls[1].clipId)
    }

    /** Doublure de contrat : mêmes clés et durées que la prod cible. */
    private class SpecAntiRepeat {
        private val lastByKey = mutableMapOf<String, Long>()
        private val durations = mapOf(
            "car-moving" to 4_000L,
            "human-soft" to 2_000L,
            "human-imminent" to 0L,
            "fence" to 4_000L,
            "curb" to 2_000L,
            "stairs" to 2_000L,
            "pole" to 2_000L,
            "sidewalk" to 10_000L,
            "verdict" to 3_000L,
        )

        fun allow(key: String, nowMs: Long): Boolean {
            val window = durations[key] ?: return true
            val last = lastByKey[key]
            if (last != null && nowMs - last < window) return false
            lastByKey[key] = nowMs
            return true
        }
    }

    /** Verdict : même texte tenu 3 s, texte changé immédiat. */
    private class SpecVerdictRepeat {
        private var lastText: String? = null
        private var lastAtMs: Long = 0L

        fun allow(text: String, nowMs: Long): Boolean {
            val last = lastText
            if (last != null && last == text && nowMs - lastAtMs < 3_000L) return false
            lastText = text
            lastAtMs = nowMs
            return true
        }
    }

    /** Doublure de signature : `fun post(level: Int, text: String, clipId: String? = null)`. */
    private class SpecVoiceBus {
        data class Call(val level: Int, val text: String, val clipId: String?)

        val calls = mutableListOf<Call>()

        fun post(level: Int, text: String, clipId: String? = null) {
            calls += Call(level, text, clipId)
        }
    }

    companion object {
        val TIMED_KEYS = setOf(
            "car-moving", "human-soft", "human-imminent", "fence", "curb",
            "stairs", "pole", "sidewalk", "verdict",
        )
        val CROSSWALK_KEYS = setOf("crosswalk-announce", "crosswalk-align", "crosswalk-arrival")
    }
}
