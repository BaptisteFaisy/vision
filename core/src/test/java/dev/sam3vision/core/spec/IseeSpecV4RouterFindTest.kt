package dev.sam3vision.core.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v4 — routeur dictée et recherche d'objet dedans (find).
 *
 * Contrat pur : ordres dictée (tout le reste = question LLM), 10 cibles
 * find dedans, résultats de recherche en clips anglais. La mission
 * routeur-dictée implémente ce découpage en prod ; la mission find-indoor
 * porte les 10 cibles et leurs clips.
 */
class IseeSpecV4RouterFindTest {

    private sealed interface Order {
        data object Outdoor : Order
        data object Indoor : Order
        data object CrossingOn : Order
        data object CrossingOff : Order
        data class Find(val target: String) : Order
        data object FindOff : Order
        data class GpsGo(val destination: String) : Order
        data object GpsOff : Order
        data object GlassesOn : Order
        data object GlassesOff : Order
        data class LlmQuestion(val text: String) : Order
    }

    /** Routeur v4 : ordres anglais exacts, reste = question LLM. */
    private fun route(raw: String): Order {
        val text = raw.trim().lowercase()
        when (text) {
            "outdoor mode", "mode outdoor" -> return Order.Outdoor
            "indoor mode", "mode indoor" -> return Order.Indoor
            "crossing on" -> return Order.CrossingOn
            "crossing off" -> return Order.CrossingOff
            "find off" -> return Order.FindOff
            "gps off" -> return Order.GpsOff
            "glasses on" -> return Order.GlassesOn
            "glasses off" -> return Order.GlassesOff
        }
        if (text.startsWith("find my ")) {
            val target = text.removePrefix("find my ").trim()
            if (target.isNotEmpty() && target in FIND_TARGETS_EN) return Order.Find(target)
            return Order.LlmQuestion(raw)
        }
        for (prefix in listOf("gps i want to go to ", "i want to go to ")) {
            if (text.startsWith(prefix)) {
                val destination = text.removePrefix(prefix).trim()
                if (destination.isNotEmpty()) return Order.GpsGo(destination)
                return Order.LlmQuestion(raw)
            }
        }
        return Order.LlmQuestion(raw)
    }

    @Test
    fun `routeur = outdoor et indoor`() {
        assertEquals(Order.Outdoor, route("outdoor mode"))
        assertEquals(Order.Indoor, route("indoor mode"))
    }

    @Test
    fun `routeur = crossing on et off`() {
        assertEquals(Order.CrossingOn, route("crossing on"))
        assertEquals(Order.CrossingOff, route("crossing off"))
    }

    @Test
    fun `routeur = find my X et find off`() {
        assertEquals(Order.Find("keys"), route("find my keys"))
        assertEquals(Order.Find("phone"), route("Find my phone"))
        assertEquals(Order.FindOff, route("find off"))
    }

    @Test
    fun `routeur = GPS go et off`() {
        assertEquals(Order.GpsGo("gare de lyon"), route("I want to go to gare de Lyon"))
        assertEquals(Order.GpsGo("gare de lyon"), route("GPS I want to go to gare de Lyon"))
        assertEquals(Order.GpsOff, route("GPS off"))
    }

    @Test
    fun `routeur = glasses on et off`() {
        assertEquals(Order.GlassesOn, route("glasses on"))
        assertEquals(Order.GlassesOff, route("glasses off"))
    }

    @Test
    fun `routeur = tout le reste question LLM`() {
        assertTrue(route("quelle heure est-il ?") is Order.LlmQuestion)
        assertTrue(route("décris-moi cette photo") is Order.LlmQuestion)
        assertTrue(route("il fait beau aujourd'hui") is Order.LlmQuestion)
        assertTrue(route("où est la gare ?") is Order.LlmQuestion)
        assertTrue(route("emmène-moi à la gare") is Order.LlmQuestion)
    }

    @Test
    fun `find = 10 cibles exactes`() {
        assertEquals(
            setOf(
                "keys", "medicine box", "glasses", "glasses case", "phone",
                "shoes", "door", "walls", "person", "socks",
            ),
            FIND_TARGETS_EN,
        )
        assertEquals(
            setOf(
                "clés", "boîte de médicaments", "lunettes", "boîtier de lunettes",
                "téléphone", "chaussures", "porte", "murs", "humain", "chaussettes",
            ),
            FIND_TARGETS_FR,
        )
    }

    @Test
    fun `find = resultats en clips anglais`() {
        val result = findResult(targetEn = "keys", clock = 2)

        assertEquals("find_keys", result.clipId)
        assertTrue(result.text.matches(Regex("[A-Za-z0-9 .,']+")))
        assertEquals("Keys found at 2 o'clock.", result.text)
    }

    @Test
    fun `find = hors-liste question LLM`() {
        assertTrue(route("find my spaceship") is Order.LlmQuestion)
        assertNull(findClipOrNull("spaceship"))
    }

    @Test
    fun `dedans = objet cherche d'abord, porte et murs derniers`() {
        val ranks = mapOf("target" to 0, "user" to 1, "human" to 2, "chat" to 3, "door-walls" to 4)

        assertEquals(0, ranks.getValue("target"))
        assertEquals(4, ranks.getValue("door-walls"))
        assertTrue(ranks.getValue("target") < ranks.getValue("door-walls"))
    }

    private data class FindResult(val text: String, val clipId: String)

    private fun findResult(targetEn: String, clock: Int): FindResult {
        val label = targetEn.replaceFirstChar { it.uppercase() }
        val clipId = "find_" + targetEn.replace(" ", "_")
        return FindResult("$label found at $clock o'clock.", clipId)
    }

    private fun findClipOrNull(targetEn: String): String? =
        if (targetEn in FIND_TARGETS_EN) "find_" + targetEn.replace(" ", "_") else null

    companion object {
        val FIND_TARGETS_EN = setOf(
            "keys", "medicine box", "glasses", "glasses case", "phone",
            "shoes", "door", "walls", "person", "socks",
        )
        val FIND_TARGETS_FR = setOf(
            "clés", "boîte de médicaments", "lunettes", "boîtier de lunettes",
            "téléphone", "chaussures", "porte", "murs", "humain", "chaussettes",
        )
    }
}
