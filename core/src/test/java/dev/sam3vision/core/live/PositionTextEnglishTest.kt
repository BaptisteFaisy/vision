package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La voix en anglais : mêmes heures, mêmes bandes, mots anglais.
 * L'anglais reste la référence ; le français est verrouillé par des appels explicites.
 */
class PositionTextEnglishTest {

    private fun placed(label: String, x: Float, y: Float = 0.5f, meters: Float? = null) =
        PlacedLabel(label, x, y, meters)

    @Test
    fun `cadran en anglais`() {
        assertEquals("at 9 o'clock", PositionText.clockLabel(0.1f, SpeechLanguage.En))
        assertEquals("at 12 o'clock", PositionText.clockLabel(0.5f, SpeechLanguage.En))
        assertEquals("at 3 o'clock", PositionText.clockLabel(0.9f, SpeechLanguage.En))
    }

    @Test
    fun `phrase en anglais, le plus en face d'abord`() {
        val phrase = PositionText.phrase(
            listOf(placed("chair", 0.9f), placed("person", 0.1f), placed("book", 0.5f)),
            language = SpeechLanguage.En,
        )

        assertEquals("Book at 12 o'clock, chair at 3 o'clock, person at 9 o'clock.", phrase)
    }

    @Test
    fun `phrase avec distance en anglais`() {
        val phrase = PositionText.phrase(
            listOf(placed("person", 0.1f, meters = 1.44f), placed("dog", 0.8f, meters = 3.2f)),
            language = SpeechLanguage.En,
        )

        assertEquals("Person 1 meter away, dog 3 meters away.", phrase)
    }

    @Test
    fun `les objets restants sont comptes en anglais`() {
        val phrase = PositionText.phrase(
            listOf(
                placed("person", 0.1f),
                placed("chair", 0.3f),
                placed("table", 0.5f),
                placed("lamp", 0.8f),
            ),
            maxObjects = 2,
            language = SpeechLanguage.En,
        )

        assertEquals("Table at 12 o'clock, chair at 11 o'clock, and 2 other objects.", phrase)
    }

    @Test
    fun `l'anglais par defaut`() {
        val phrase = PositionText.phrase(listOf(placed("personne", 0.1f, meters = 1.44f)))
        assertEquals("Personne 1 meter away.", phrase)
    }
}
