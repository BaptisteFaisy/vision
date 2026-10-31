package dev.sam3vision.core.voicenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrouille l'extraction vocale d'une destination ([VoiceDestinationParser]).
 *
 * Contrat : les trois formes de la consigne (« je veux aller à X »,
 * « emmène-moi X », « direction X ») rendent X brut (casse et accents
 * conservés, ponctuation finale retirée) ; questions, phrases sans intention
 * et destinations vides rendent `null`, jamais d'exception.
 */
class VoiceDestinationParserTest {

    private fun assertDestination(transcript: String, expected: String) {
        assertEquals("« $transcript »", expected, VoiceDestinationParser.parse(transcript))
    }

    private fun assertIgnored(transcript: String) {
        assertNull("« $transcript » doit être ignoré", VoiceDestinationParser.parse(transcript))
    }

    @Test
    fun `je veux aller a X rend X`() {
        assertDestination("je veux aller à la gare de Lyon", "la gare de Lyon")
    }

    @Test
    fun `variantes de je veux aller a X rendent X`() {
        assertDestination("Je voudrais aller à Hôtel de Ville", "Hôtel de Ville")
        assertDestination("je vais aller à Paris", "Paris")
        assertDestination("J'aimerais aller à Saint-Germain-en-Laye", "Saint-Germain-en-Laye")
    }

    @Test
    fun `emmene-moi a X rend X`() {
        assertDestination("emmène-moi à la tour Eiffel", "la tour Eiffel")
        assertDestination("Emmenez-moi à l'aéroport", "l'aéroport")
    }

    @Test
    fun `emmene-moi X sans a rend X`() {
        assertDestination("emmène-moi gare de l'Est", "gare de l'Est")
        assertDestination("Emmène-moi place du Châtelet.", "place du Châtelet")
    }

    @Test
    fun `direction X rend X`() {
        assertDestination("direction gare de Lyon", "gare de Lyon")
        assertDestination("Direction 12 rue de la Paix", "12 rue de la Paix")
    }

    @Test
    fun `direction X tolere la politesse initiale et le de residuel`() {
        assertDestination("s'il te plaît, direction la gare de Lyon", "la gare de Lyon")
        assertDestination("direction de la gare de Lyon", "la gare de Lyon")
        assertDestination("direction de l'aéroport d'Orly", "l'aéroport d'Orly")
        assertDestination("direction Denfert-Rochereau !", "Denfert-Rochereau")
        assertDestination("direction de Gaulle", "de Gaulle")
    }

    @Test
    fun `les ordres existants restent reconnus`() {
        assertDestination("naviguer vers la gare de Lyon", "la gare de Lyon")
        assertDestination("conduis-moi à la gare de Lyon", "la gare de Lyon")
        assertDestination("va à la gare de Lyon", "la gare de Lyon")
    }

    @Test
    fun `les questions restent des questions`() {
        assertIgnored("où est la pharmacie la plus proche ?")
        assertIgnored("c'est loin la gare de Lyon ?")
        assertIgnored("quelle est la direction à suivre ?")
    }

    @Test
    fun `direction en milieu de phrase n est pas un ordre`() {
        assertIgnored("prends la direction opposée")
        assertIgnored("il va dans la direction de la sortie")
    }

    @Test
    fun `les destinations vides sont ignorees`() {
        assertIgnored("")
        assertIgnored("   ")
        assertIgnored("direction")
        assertIgnored("emmène-moi à")
        assertIgnored("va à")
    }

    @Test
    fun `prepositions etendues et variante amene`() {
        assertDestination("je veux aller au supermarché", "supermarché")
        assertDestination("je veux aller aux toilettes", "toilettes")
        assertDestination("je veux aller chez le médecin", "le médecin")
        assertDestination("amène-moi à la gare", "la gare")
        assertDestination("amène-moi gare de l'Est", "gare de l'Est")
        assertDestination("emmène-moi au café", "café")
    }

    @Test
    fun `prepositions seules sans suite sont ignorees`() {
        assertIgnored("emmène-moi au")
        assertIgnored("emmène-moi aux")
        assertIgnored("direction de")
        assertIgnored("direction du")
        assertIgnored("direction to")
        assertIgnored("directions to")
    }

    @Test
    fun `parseExtra ne rend que les formes hors routeur`() {
        assertEquals("gare de Lyon", VoiceDestinationParser.parseExtra("direction gare de Lyon"))
        assertEquals("gare de l'Est", VoiceDestinationParser.parseExtra("emmène-moi gare de l'Est"))
        assertNull(VoiceDestinationParser.parseExtra("je veux aller à la gare"))
        assertNull(VoiceDestinationParser.parseExtra("aller à Lyon"))
        assertNull(VoiceDestinationParser.parseExtra("bonjour"))
    }

    @Test
    fun `english orders render X`() {
        assertDestination("I want to go to Lyon station", "Lyon station")
        assertDestination("take me to the airport", "the airport")
        assertDestination("navigate to Orly airport", "Orly airport")
        assertDestination("go to the pharmacy", "the pharmacy")
        assertDestination("direction Lyon station", "Lyon station")
    }

    @Test
    fun `english questions stay questions`() {
        assertIgnored("where is the nearest pharmacy?")
        assertIgnored("how far is Lyon station?")
    }
}
