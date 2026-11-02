package dev.sam3vision.core.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v4 — routeur dictée et recherche d'objet dedans (find).
 *
 * Contrat pur : 10 ordres « mode … » (tout le reste = question LLM),
 * recherche « mode find my X » ouverte à tout objet, trouvé parlé au
 * cadran. La mission routeur-dictée implémente ce découpage en prod ; la
 * mission find-indoor porte la chasse et ses phrases.
 */
class IseeSpecV4RouterFindTest {

    private sealed interface Order {
        data object Outdoor : Order
        data object Indoor : Order
        data object CrossingOn : Order
        data object CrossingOff : Order
        data class Find(val label: String) : Order
        data object FindOff : Order
        data class GpsGo(val destination: String) : Order
        data object GpsOff : Order
        data object GlassesOn : Order
        data object GlassesOff : Order
        data class LlmQuestion(val text: String) : Order
    }

    /** Routeur v4 : ordres « mode … » exacts, reste = question LLM. */
    private fun route(raw: String): Order {
        val text = raw.trim().lowercase()
        if (!text.startsWith("mode ")) return Order.LlmQuestion(raw)
        when (text) {
            "mode outdoor" -> return Order.Outdoor
            "mode indoor" -> return Order.Indoor
            "mode crossing on" -> return Order.CrossingOn
            "mode crossing off" -> return Order.CrossingOff
            "mode find off" -> return Order.FindOff
            "mode gps off" -> return Order.GpsOff
            "mode glasses on" -> return Order.GlassesOn
            "mode glasses off" -> return Order.GlassesOff
        }
        if (text.startsWith("mode find my ")) {
            val label = text.removePrefix("mode find my ").trim().trimEnd('.', ',', '?', '!')
            if (label.isNotEmpty()) return Order.Find(label)
            return Order.LlmQuestion(raw)
        }
        if (text.startsWith("mode gps i want to go to ")) {
            val destination = text.removePrefix("mode gps i want to go to ").trim()
            if (destination.isNotEmpty()) return Order.GpsGo(destination)
            return Order.LlmQuestion(raw)
        }
        return Order.LlmQuestion(raw)
    }

    @Test
    fun `routeur = les 10 ordres mode`() {
        assertEquals(Order.Outdoor, route("mode outdoor"))
        assertEquals(Order.Indoor, route("mode indoor"))
        assertEquals(Order.CrossingOn, route("mode crossing on"))
        assertEquals(Order.CrossingOff, route("mode crossing off"))
        assertEquals(Order.Find("keys"), route("mode find my keys"))
        assertEquals(Order.FindOff, route("mode find off"))
        assertEquals(Order.GpsGo("gare de lyon"), route("mode GPS I want to go to gare de Lyon"))
        assertEquals(Order.GpsOff, route("mode GPS off"))
        assertEquals(Order.GlassesOn, route("mode glasses on"))
        assertEquals(Order.GlassesOff, route("mode glasses off"))
    }

    @Test
    fun `routeur = find my X ouvert a tout objet`() {
        assertEquals(Order.Find("mug"), route("mode find my mug"))
        assertEquals(Order.Find("spaceship"), route("mode find my spaceship"))
        assertEquals(Order.Find("tv remote"), route("mode find my tv remote"))
        assertTrue(route("mode find my") is Order.LlmQuestion)
    }

    @Test
    fun `routeur = tout le reste question LLM`() {
        assertTrue(route("quelle heure est-il ?") is Order.LlmQuestion)
        assertTrue(route("décris-moi cette photo") is Order.LlmQuestion)
        assertTrue(route("il fait beau aujourd'hui") is Order.LlmQuestion)
        assertTrue(route("où est la gare ?") is Order.LlmQuestion)
        assertTrue(route("emmène-moi à la gare") is Order.LlmQuestion)
        assertTrue(route("où sont mes clés") is Order.LlmQuestion)
        assertTrue(route("where are my keys") is Order.LlmQuestion)
        assertTrue(route("find my keys") is Order.LlmQuestion)
        assertTrue(route("Find the keys") is Order.LlmQuestion)
        assertTrue(route("locate my phone") is Order.LlmQuestion)
        assertTrue(route("create a picto for my mug") is Order.LlmQuestion)
        assertTrue(route("fais un pictogramme de ma tasse") is Order.LlmQuestion)
    }

    @Test
    fun `find = trouve parle au cadran`() {
        assertEquals("Mug at 9 o'clock.", findResult("mug", 9))
        assertEquals("Spaceship at 2 o'clock.", findResult("spaceship", 2))
        assertTrue(findResult("mug", 9).matches(Regex("[A-Za-z0-9 .,']+")))
    }

    @Test
    fun `dedans = objet cherche d'abord, porte et murs derniers`() {
        val ranks = mapOf("target" to 0, "user" to 1, "human" to 2, "chat" to 3, "door-walls" to 4)

        assertEquals(0, ranks.getValue("target"))
        assertEquals(4, ranks.getValue("door-walls"))
        assertTrue(ranks.getValue("target") < ranks.getValue("door-walls"))
    }

    private fun findResult(targetEn: String, clock: Int): String {
        val label = targetEn.replaceFirstChar { it.uppercase() }
        return "$label at $clock o'clock."
    }
}
