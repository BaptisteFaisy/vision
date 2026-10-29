package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Formes orales ajoutées après une dictée réelle : « aller gare Montparnasse »
 * sans le « à » partait au LLM, qui répondait par une description de la pièce.
 * Ces tests verrouillent la reconnaissance des destinations sans préposition
 * et le refus des compléments qui ne sont pas des lieux.
 */
class NavigationIntentSansPrepositionTest {

    private fun destinationOf(text: String): String? =
        NavigationIntentParser.parse(text)?.destination

    @Test
    fun `je veux aller X sans preposition`() {
        assertEquals("gare Montparnasse", destinationOf("je veux aller gare Montparnasse"))
        assertEquals("gare de Lyon", destinationOf("je voudrais aller gare de Lyon"))
        assertEquals("la pharmacie", destinationOf("je vais aller la pharmacie"))
    }

    @Test
    fun `aller X nu en debut de phrase`() {
        assertEquals("gare Montparnasse", destinationOf("aller gare Montparnasse"))
        assertEquals("gare Montparnasse", destinationOf("va gare Montparnasse"))
        assertEquals("tour Eiffel", destinationOf("  aller   tour Eiffel  "))
        assertNull(destinationOf("ça va"))
    }

    @Test
    fun `aller au et aux perdent la preposition contractee`() {
        assertEquals("marché", destinationOf("je veux aller au marché"))
        assertEquals("Halles", destinationOf("aller aux Halles"))
    }

    @Test
    fun `un complement d'action n'est pas un lieu`() {
        assertNull(destinationOf("je veux aller vite"))
        assertNull(destinationOf("aller chercher le pain"))
        assertNull(destinationOf("aller voir"))
        assertNull(destinationOf("je veux aller mieux"))
        assertNull(destinationOf("aller tout droit"))
    }

    @Test
    fun `les formes avec a restent inchangees`() {
        assertEquals("la gare", destinationOf("je veux aller à la gare"))
        assertEquals("Lyon", destinationOf("aller à Lyon"))
    }

    @Test
    fun `aller en milieu de phrase n'est pas un ordre`() {
        assertNull(destinationOf("il va aller vite demain"))
        // Les formes sans « à » sont ancrées au début : « aller gare X » en
        // milieu de phrase (« il va aller gare X ») n'est pas un ordre.
        assertNull(destinationOf("il va aller gare Montparnasse"))
    }
}
