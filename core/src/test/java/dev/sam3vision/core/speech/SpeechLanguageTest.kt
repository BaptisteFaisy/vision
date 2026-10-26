package dev.sam3vision.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** Langue de la voix : défaut anglais, lecture tolérante des valeurs persistées. */
class SpeechLanguageTest {

    @Test
    fun `la voix parle anglais par defaut`() {
        assertEquals(SpeechLanguage.En, SpeechLanguage.DEFAULT)
        assertEquals("en", SpeechLanguage.En.code)
        assertEquals("fr", SpeechLanguage.Fr.code)
    }

    @Test
    fun `une valeur persistee inconnue retombe sur le defaut`() {
        assertEquals(SpeechLanguage.En, SpeechLanguage.fromName(null))
        assertEquals(SpeechLanguage.En, SpeechLanguage.fromName(""))
        assertEquals(SpeechLanguage.En, SpeechLanguage.fromName("klingon"))
    }

    @Test
    fun `une valeur persistee connue est relue`() {
        assertEquals(SpeechLanguage.Fr, SpeechLanguage.fromName("Fr"))
        assertEquals(SpeechLanguage.Fr, SpeechLanguage.fromName("fr"))
        assertEquals(SpeechLanguage.En, SpeechLanguage.fromName("EN"))
    }

    @Test
    fun `le code iso separe de la valeur persistee`() {
        assertEquals(SpeechLanguage.En, SpeechLanguage.fromCode("en"))
        assertEquals(SpeechLanguage.Fr, SpeechLanguage.fromCode("fr"))
        assertEquals(SpeechLanguage.En, SpeechLanguage.fromCode("xx"))
    }

    @Test
    fun `le libelle des reglages tient sur une ligne`() {
        SpeechLanguage.entries.forEach { language ->
            assertEquals(true, language.label.length in 10..40)
        }
    }
}
