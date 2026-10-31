package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests artisanaux du parseur d'intention de navigation (JUnit seul, pur JVM,
 * indépendants du réseau) : [NavigationIntentParser.parse] rend
 * [NavigationIntent] (destination rognée, casse d'origine préservée) ou `null`
 * (pas d'intention — ni erreur, ni exception).
 */
class NavigationIntentTest {

    private fun destinationOf(text: String): String? =
        NavigationIntentParser.parse(text)?.destination

    @Test
    fun `je veux aller a X`() {
        assertEquals("la gare", destinationOf("je veux aller à la gare"))
    }

    @Test
    fun `je voudrais et je vais aller a X`() {
        assertEquals("la gare", destinationOf("je voudrais aller à la gare"))
        assertEquals("Lyon", destinationOf("je vais aller à Lyon"))
    }

    @Test
    fun `j'aimerais aller a X`() {
        assertEquals("la plage", destinationOf("j'aimerais aller à la plage"))
    }

    @Test
    fun `emmene-moi a X`() {
        assertEquals("la Tour Eiffel", destinationOf("Emmène-moi à la Tour Eiffel"))
        assertEquals("la pharmacie", destinationOf("emmène-moi à la pharmacie"))
        assertEquals("la plage", destinationOf("emmene-moi a la plage"))
    }

    @Test
    fun `naviguer vers X`() {
        assertEquals("Paris", destinationOf("naviguer vers Paris"))
        assertEquals("la gare", destinationOf("naviguer vers la gare"))
    }

    @Test
    fun `aller a X`() {
        assertEquals("Lyon", destinationOf("aller à Lyon"))
        assertEquals("la poste", destinationOf("aller a la poste"))
    }

    @Test
    fun `conduis-moi a X`() {
        assertEquals("l'aéroport", destinationOf("conduis-moi à l'aéroport"))
    }

    @Test
    fun `itineraire vers X avec et sans accent`() {
        assertEquals("le musée", destinationOf("itinéraire vers le musée"))
        assertEquals("la tour Eiffel", destinationOf("itineraire vers la tour Eiffel"))
    }

    @Test
    fun `guide-moi jusqu'a ou vers X`() {
        assertEquals("la sortie", destinationOf("guide-moi jusqu'à la sortie"))
        assertEquals("l'hôpital", destinationOf("guide-moi jusqu'à l'hôpital"))
        assertEquals("Nice", destinationOf("guide-moi vers Nice"))
    }

    @Test
    fun `va a X en debut de phrase`() {
        assertEquals("la pharmacie", destinationOf("va à la pharmacie"))
    }

    @Test
    fun `insensible a la casse et aux accents`() {
        assertEquals("LA GARE", destinationOf("EMMENE-MOI A LA GARE"))
        assertEquals("Marseille", destinationOf("JE VEUX ALLER A Marseille"))
        assertEquals("la gare", destinationOf("NAVIGUER VERS la gare"))
        assertEquals("Paris", destinationOf("Aller À Paris"))
    }

    @Test
    fun `destination brute garde casse accents et ponctuation interne`() {
        assertEquals("la gare Saint-Lazare", destinationOf("Emmène-moi à la gare Saint-Lazare."))
        assertEquals("l'hôpital Saint-Louis", destinationOf("aller à l'hôpital Saint-Louis !"))
        assertEquals("la gare de Lyon", destinationOf("naviguer vers la gare de Lyon"))
        assertEquals("Saint-Germain-en-Laye", destinationOf("aller à Saint-Germain-en-Laye"))
    }

    @Test
    fun `separateurs espace trait-dunion et imperatif pluriel`() {
        assertEquals("Nice", destinationOf("emmène moi à Nice"))
        assertEquals("Toulouse", destinationOf("Emmenez-moi à Toulouse"))
        assertEquals("Bordeaux", destinationOf("conduisez-moi à Bordeaux"))
    }

    @Test
    fun `tolerer une formule de politesse devant`() {
        assertEquals("la gare", destinationOf("s'il te plaît, emmène-moi à la gare"))
    }

    @Test
    fun `les espaces superflus sont rognes`() {
        assertEquals("la gare", destinationOf("  naviguer vers   la gare   "))
    }

    @Test
    fun `un texte sans intention retourne null`() {
        assertNull(destinationOf("bonjour"))
        assertNull(destinationOf("je veux manger"))
        assertNull(destinationOf("quelle heure est-il ?"))
        assertNull(destinationOf("il fait beau aujourd'hui"))
        assertNull(destinationOf("raconte-moi une histoire"))
    }

    @Test
    fun `ou est X reste une question pour le LLM`() {
        assertNull(destinationOf("où est la gare ?"))
        assertNull(destinationOf("ou est la gare ?"))
    }

    @Test
    fun `va a en milieu de phrase n'est pas un ordre`() {
        assertNull(destinationOf("il va à Paris demain"))
    }

    @Test
    fun `un texte vide ou blanc retourne null`() {
        assertNull(destinationOf(""))
        assertNull(destinationOf("   "))
    }

    @Test
    fun `une destination vide retourne null`() {
        assertNull(destinationOf("naviguer vers"))
        assertNull(destinationOf("naviguer vers   "))
        assertNull(destinationOf("aller à"))
        assertNull(destinationOf("aller à   "))
        assertNull(destinationOf("emmène-moi"))
        assertNull(destinationOf("emmène-moi à   "))
    }

    @Test
    fun `naviguer sans declencheur complet retourne null`() {
        assertNull(destinationOf("j'aime naviguer"))
        assertNull(destinationOf("nous naviguons vers le large"))
    }

    @Test
    fun `articles contractes au et aux`() {
        assertEquals("supermarché", destinationOf("je veux aller au supermarché"))
        assertEquals("toilettes", destinationOf("je veux aller aux toilettes"))
        assertEquals("marché", destinationOf("je voudrais aller au marché"))
        assertEquals("resto", destinationOf("aller au resto"))
        assertEquals("urgences", destinationOf("aller aux urgences"))
        assertEquals("bout de la rue", destinationOf("va au bout de la rue"))
        assertEquals("café", destinationOf("emmène-moi au café"))
        assertEquals("toilettes", destinationOf("conduis-moi aux toilettes"))
        assertEquals("MARCHE", destinationOf("JE VEUX ALLER AU MARCHE"))
    }

    @Test
    fun `prepositions en dans vers chez jusqua`() {
        assertEquals("ville", destinationOf("je vais aller en ville"))
        assertEquals("centre-ville", destinationOf("aller en centre-ville"))
        assertEquals("le médecin", destinationOf("je veux aller chez le médecin"))
        assertEquals("le magasin", destinationOf("je veux aller dans le magasin"))
        assertEquals("la sortie", destinationOf("je veux aller vers la sortie"))
        assertEquals("la gare", destinationOf("je veux aller jusqu'à la gare"))
        assertEquals("bout de la rue", destinationOf("je veux aller jusqu'au bout de la rue"))
        assertEquals("bout", destinationOf("guide-moi jusqu'au bout"))
        assertEquals("ville", destinationOf("va en ville"))
        assertEquals("cinéma", destinationOf("j'aimerais aller au cinéma"))
    }

    @Test
    fun `variante STT amene-moi`() {
        assertEquals("la gare", destinationOf("amène-moi à la gare"))
        assertEquals("la plage", destinationOf("amene-moi a la plage"))
        assertEquals("Halles", destinationOf("Amenez-moi aux Halles"))
    }

    @Test
    fun `expressions figees ne sont pas des destinations`() {
        assertNull(destinationOf("je veux aller en savoir plus"))
        assertNull(destinationOf("je veux aller dans le détail"))
        assertNull(destinationOf("je veux aller dans les détails"))
        assertNull(destinationOf("va en arrière"))
        assertNull(destinationOf("va en avant"))
        assertNull(destinationOf("va en haut"))
    }

    @Test
    fun `english orders render X`() {
        assertEquals("the station", destinationOf("I want to go to the station"))
        assertEquals("Lyon station", destinationOf("I wanna go to Lyon station"))
        assertEquals("Paris", destinationOf("I would like to go to Paris"))
        assertEquals("Nice", destinationOf("I'd like to go to Nice"))
        assertEquals("the Eiffel Tower", destinationOf("take me to the Eiffel Tower"))
        assertEquals("the airport", destinationOf("bring me to the airport"))
        assertEquals("Eiffel Tower", destinationOf("take me Eiffel Tower"))
        assertEquals("Orly airport", destinationOf("navigate to Orly airport"))
        assertEquals("the exit", destinationOf("guide me to the exit"))
        assertEquals("the Louvre", destinationOf("I'm going to the Louvre"))
        assertEquals("the station", destinationOf("let's go to the station"))
        assertEquals("the pharmacy", destinationOf("go to the pharmacy"))
        assertEquals("Eiffel Tower", destinationOf("go Eiffel Tower"))
        assertEquals("the station", destinationOf("directions to the station"))
    }

    @Test
    fun `english non places are refused`() {
        assertNull(destinationOf("go fast"))
        assertNull(destinationOf("take me back"))
        assertNull(destinationOf("go"))
        assertNull(destinationOf("I want to go"))
    }
}
