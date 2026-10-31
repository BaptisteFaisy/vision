package dev.sam3vision.core.nav

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La destination GPS en cours, mise en mots pour le contexte LLM. */
class GpsDestinationTextTest {

    @Test
    fun `ligne francaise nomme la destination`() {
        assertEquals(
            "Destination GPS en cours : « la gare ».",
            GpsDestinationText.line("la gare", SpeechLanguage.Fr),
        )
    }

    @Test
    fun `ligne anglaise nomme la destination`() {
        assertEquals(
            "Active GPS destination: \"la gare\".",
            GpsDestinationText.line("la gare", SpeechLanguage.En),
        )
    }

    @Test
    fun `destination blanche ou absente — pas de ligne`() {
        assertNull(GpsDestinationText.line(null, SpeechLanguage.Fr))
        assertNull(GpsDestinationText.line("   ", SpeechLanguage.En))
    }

    @Test
    fun `destination seule — la ligne est tout le contexte`() {
        assertEquals(
            "Active GPS destination: \"la gare\".",
            GpsDestinationText.combine(null, "la gare", SpeechLanguage.En),
        )
    }

    @Test
    fun `journal et destination — les deux blocs, separes`() {
        assertEquals(
            "journal\n\nDestination GPS en cours : « la gare ».",
            GpsDestinationText.combine("journal", "la gare", SpeechLanguage.Fr),
        )
    }

    @Test
    fun `sans destination — le contexte passe tel quel`() {
        assertEquals("journal", GpsDestinationText.combine("journal", null, SpeechLanguage.Fr))
        assertNull(GpsDestinationText.combine(null, "  ", SpeechLanguage.En))
    }
}
