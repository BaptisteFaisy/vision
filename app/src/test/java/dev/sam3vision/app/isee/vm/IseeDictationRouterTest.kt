package dev.sam3vision.app.isee.vm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routeur de dictée (spec v4 : ordres « mode … » uniquement) : les 10 ordres
 * appellent leur effet, une fois ; tout le reste rend `false` sans rien
 * appeler — l'appelant envoie le texte au LLM comme question.
 */
class IseeDictationRouterTest {

    private val calls = mutableListOf<String>()

    private val actions = object : DictationActions {
        override suspend fun onOutdoor() { calls += "outdoor" }
        override suspend fun onIndoor() { calls += "indoor" }
        override suspend fun onCrossing(active: Boolean) { calls += if (active) "crossing-on" else "crossing-off" }
        override suspend fun onFind(label: String) { calls += "find:$label" }
        override suspend fun onFindOff() { calls += "find-off" }
        override suspend fun onGpsGo(destination: String) { calls += "gps:$destination" }
        override suspend fun onGpsOff() { calls += "gps-off" }
        override suspend fun onGlasses(on: Boolean) { calls += if (on) "glasses-on" else "glasses-off" }
    }

    private val router = IseeDictationRouter(actions)

    @Test
    fun `les 10 ordres sont consommes une fois chacun`() = runTest {
        val cases = listOf(
            "mode outdoor" to "outdoor",
            "mode indoor" to "indoor",
            "mode crossing on" to "crossing-on",
            "mode crossing off" to "crossing-off",
            "mode find my keys" to "find:keys",
            "mode find my mug" to "find:mug",
            "mode find off" to "find-off",
            "mode GPS I want to go to gare Montparnasse" to "gps:gare Montparnasse",
            "mode GPS off" to "gps-off",
            "mode glasses on" to "glasses-on",
            "mode glasses off" to "glasses-off",
        )
        cases.forEach { (text, want) ->
            calls.clear()
            assertTrue(text, router.run(text))
            assertEquals(listOf(want), calls)
        }
    }

    @Test
    fun `le routeur est tolerant comme le parseur`() = runTest {
        assertTrue(router.run("  Mode   OUTDOOR "))
        assertEquals(listOf("outdoor"), calls)
    }

    @Test
    fun `les chasses sans mode partent au llm`() = runTest {
        val chases = listOf(
            "où sont mes clés",
            "où est mon téléphone",
            "où étaient mes clés",
            "où étaient-elles",
            "where are my keys",
            "Where is my phone?",
            "find my keys",
            "Find the keys",
            "find my shoes please",
            "locate my phone",
        )
        chases.forEach { text ->
            calls.clear()
            assertFalse(text, router.run(text))
            assertTrue(text, calls.isEmpty())
        }
    }

    @Test
    fun `le pronom part au llm`() = runTest {
        calls.clear()
        assertFalse(router.run("où étaient-elles"))
        assertTrue(calls.isEmpty())
        calls.clear()
        assertFalse(router.run("où étaient-elles"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `les questions partent au llm sans effet`() = runTest {
        val questions = listOf(
            "décris-moi la pièce",
            "quelle heure est-il ?",
            "",
            "   ",
            "traverser",
            "crossing mode on",
            "maison",
            "va à gauche",
            "je veux aller à la gare",
            "désactive le GPS",
            "mode",
            "mode self destruct",
            "mode outdoor please",
            "mode find my",
            "mode GPS I want to go to",
            "s'il te plaît mode outdoor",
            "où est la gare",
            "where is the exit",
            "find the elephant",
        )
        questions.forEach { text ->
            calls.clear()
            assertFalse(text, router.run(text))
            assertTrue(text, calls.isEmpty())
        }
    }
}
