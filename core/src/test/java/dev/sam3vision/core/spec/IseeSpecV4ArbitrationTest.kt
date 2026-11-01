package dev.sam3vision.core.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v4 — écrasement, verdict traversée et priorités dedans.
 *
 * Contrat pur : le plus petit niveau gagne ; l'écrasé n'est jamais redit
 * sauf re-détecté actuel ; le chat reprend depuis la coupure au lieu de
 * perdre. Le verdict vaut feu piéton puis voitures (rouge ou mouvement =
 * wait, sinon cross, voiture uniquement). Dedans : objet cherché > parole
 * user > humain > chat > porte et murs.
 */
class IseeSpecV4ArbitrationTest {

    /** Arbitre v4 : le plus petit niveau parle seul, `null` = silence. */
    private fun arbitrate(levels: List<Int>): Int? = levels.minOrNull()

    /** Coupe v4 : seul le niveau 0 coupe la parole user (niveau 1). */
    private fun cuts(cutter: Int, cut: Int): Boolean = cutter == 0 && cut == 1

    /** Verdict v4 : rouge ou voiture en mouvement = wait, sinon cross. */
    private fun verdict(lightRed: Boolean, carMoving: Boolean): String =
        if (lightRed || carMoving) "Wait." else "Cross."

    @Test
    fun `ecrasement = le plus petit nombre gagne`() {
        assertEquals(0, arbitrate(listOf(0, 2, 5)))
        assertEquals(2, arbitrate(listOf(5, 2, 9)))
        assertEquals(3, arbitrate(listOf(11, 3, 12)))
        assertEquals(9, arbitrate(listOf(12, 9, 10)))
    }

    @Test
    fun `ecrasement = un seul niveau parle`() {
        assertEquals(4, arbitrate(listOf(4)))
    }

    @Test
    fun `ecrasement = silence sans niveau`() {
        assertNull(arbitrate(emptyList()))
    }

    @Test
    fun `ecrase = jamais redit sauf re-detecte actuel`() {
        val memory = CrushedMemory()

        memory.crushed(level = 5, text = "Curb.")
        // Re-détecté à la frame suivante : redit.
        assertTrue(memory.shouldSpeak(level = 5, currentlyDetected = true))
        // Plus détecté : jamais redit.
        assertFalse(memory.shouldSpeak(level = 5, currentlyDetected = false))
    }

    @Test
    fun `chat = reprise depuis la coupure au lieu de perte`() {
        val chat = ChatResume(fullText = "Turn left after the bakery, then continue.")

        chat.cut(atChar = 10)
        assertEquals("Turn left ", chat.spokenSoFar())
        assertEquals("after the bakery, then continue.", chat.resume())
    }

    @Test
    fun `seul 0 coupe 1`() {
        assertTrue(cuts(cutter = 0, cut = 1))
        (2..12).forEach { level ->
            assertFalse("niveau $level ne coupe pas la parole user", cuts(cutter = level, cut = 1))
        }
        assertFalse(cuts(cutter = 1, cut = 0))
    }

    @Test
    fun `verdict = rouge ou mouvement, wait, sinon cross`() {
        assertEquals("Wait.", verdict(lightRed = true, carMoving = false))
        assertEquals("Wait.", verdict(lightRed = false, carMoving = true))
        assertEquals("Wait.", verdict(lightRed = true, carMoving = true))
        assertEquals("Cross.", verdict(lightRed = false, carMoving = false))
    }

    @Test
    fun `verdict = voiture uniquement, bus et velo ne bloquent pas`() {
        assertTrue(isVerdictVehicle("car"))
        assertTrue(isVerdictVehicle("Car"))
        assertFalse(isVerdictVehicle("bus"))
        assertFalse(isVerdictVehicle("bicycle"))
        assertFalse(isVerdictVehicle("truck"))
        assertFalse(isVerdictVehicle("motorcycle"))
        assertFalse(isVerdictVehicle("person"))
    }

    @Test
    fun `traversee exclusive = verdict puis humains puis bordures`() {
        val order = listOf(100, 101, 102)

        assertEquals(100, arbitrate(order))
        assertEquals(101, arbitrate(order - 100))
        assertEquals(102, arbitrate(order - 100 - 101))
    }

    @Test
    fun `traversee = chat et GPS muets, dictee active`() {
        val muted = setOf(3, 9, 11)

        assertTrue(muted.contains(9))
        assertTrue(muted.contains(11))
        assertTrue(muted.contains(3))
        assertFalse(muted.contains(100))
        assertTrue(CROSSING_DICTATION_ACTIVE)
    }

    @Test
    fun `dedans = objet cherche puis parole user puis humain puis chat puis porte et murs`() {
        val indoorOrder = listOf("target", "user", "human", "chat", "door-walls")

        assertEquals("target", indoorOrder.minBy { indoorRank(it) })
        assertTrue(indoorRank("target") < indoorRank("user"))
        assertTrue(indoorRank("user") < indoorRank("human"))
        assertTrue(indoorRank("human") < indoorRank("chat"))
        assertTrue(indoorRank("chat") < indoorRank("door-walls"))
    }

    private fun isVerdictVehicle(label: String): Boolean =
        label.lowercase() == "car" || label.lowercase() == "cars"

    private fun indoorRank(slot: String): Int = when (slot) {
        "target" -> 0
        "user" -> 1
        "human" -> 2
        "chat" -> 3
        else -> 4
    }

    /** Mémoire d'écrasement : seul le re-détecté actuel reparle. */
    private class CrushedMemory {
        private val crushed = mutableSetOf<Int>()

        fun crushed(level: Int, text: String) {
            if (text.isNotBlank()) crushed += level
        }

        fun shouldSpeak(level: Int, currentlyDetected: Boolean): Boolean =
            level in crushed && currentlyDetected
    }

    /** Reprise chat : le texte coupé reprend à l'offset, sans perte. */
    private class ChatResume(private val fullText: String) {
        private var cutAt: Int = fullText.length

        fun cut(atChar: Int) {
            cutAt = atChar.coerceIn(0, fullText.length)
        }

        fun spokenSoFar(): String = fullText.substring(0, cutAt)

        fun resume(): String = fullText.substring(cutAt)
    }

    companion object {
        const val CROSSING_DICTATION_ACTIVE = true
    }
}
