package dev.sam3vision.core.voicenav

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le filet anti-« il me décrit ce qu'il voit » ([NavClarify]) :
 * un ordre de navigation sans destination comprise rend `true` (l'appelant
 * demande la destination au lieu d'appeler le LLM), un ordre compris, une
 * question ou une phrase ordinaire rend `false`.
 */
class NavClarifyTest {

    private fun assertUnclear(transcript: String) {
        assertTrue("« $transcript » doit être un ordre incompris", NavClarify.isUnclearOrder(transcript))
    }

    private fun assertNotUnclear(transcript: String) {
        assertFalse("« $transcript » ne doit pas être un ordre incompris", NavClarify.isUnclearOrder(transcript))
    }

    @Test
    fun `ordres sans destination demandent la destination`() {
        assertUnclear("je veux aller")
        assertUnclear("je veux aller à")
        assertUnclear("je voudrais aller au")
        assertUnclear("je dois aller")
        assertUnclear("emmène-moi")
        assertUnclear("emmène-moi à")
        assertUnclear("amène-moi au")
        assertUnclear("naviguer")
        assertUnclear("naviguer vers")
        assertUnclear("conduis-moi")
        assertUnclear("guide-moi")
        assertUnclear("itinéraire vers")
        assertUnclear("va à")
        assertUnclear("aller")
        assertUnclear("direction")
        assertUnclear("direction de")
        assertUnclear("je dois aller où ?")
    }

    @Test
    fun `ordres compris ne sont pas incompris`() {
        assertNotUnclear("je veux aller à la gare")
        assertNotUnclear("je veux aller au supermarché")
        assertNotUnclear("je veux aller aux toilettes")
        assertNotUnclear("amène-moi à la gare")
        assertNotUnclear("emmène-moi gare de l'Est")
        assertNotUnclear("direction gare de Lyon")
        assertNotUnclear("va à la pharmacie")
        assertNotUnclear("comment aller à la gare ?")
    }

    @Test
    fun `questions et phrases ordinaires restent au LLM`() {
        assertNotUnclear("bonjour")
        assertNotUnclear("où est la gare ?")
        assertNotUnclear("où aller ?")
        assertNotUnclear("c'est loin la gare ?")
        assertNotUnclear("quelle est la direction à suivre ?")
        assertNotUnclear("prends la direction opposée")
        assertNotUnclear("il va aller pleuvoir")
        assertNotUnclear("je vais bien")
        assertNotUnclear("décris-moi ce que tu vois")
        assertNotUnclear("")
        assertNotUnclear("   ")
    }

    @Test
    fun `expressions figees restent des questions`() {
        assertNotUnclear("je veux aller en savoir plus")
        assertNotUnclear("je veux aller dans le détail")
    }

    @Test
    fun `la demande est dans la langue de la voix`() {
        assertEquals(
            "Je n'ai pas compris où tu veux aller. Dis par exemple : je veux aller à la gare.",
            NavClarifyText.ask(SpeechLanguage.Fr),
        )
        assertEquals(
            "I didn't catch where you want to go. Say for example: I want to go to the train station.",
            NavClarifyText.ask(SpeechLanguage.En),
        )
    }
}
