package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La voix en anglais : mêmes secteurs, mêmes seuils, mots anglais.
 * Le français reste la référence (les tests de [PositionTextTest] le verrouillent).
 */
class PositionTextEnglishTest {

    private fun placed(label: String, x: Float, y: Float = 0.5f, meters: Float? = null) =
        PlacedLabel(label, x, y, meters)

    @Test
    fun `secteurs horizontaux en anglais`() {
        assertEquals("on the left", PositionText.horizontal(0.1f, SpeechLanguage.En))
        assertEquals("in the middle", PositionText.horizontal(0.5f, SpeechLanguage.En))
        assertEquals("on the right", PositionText.horizontal(0.9f, SpeechLanguage.En))
    }

    @Test
    fun `position complete en anglais`() {
        assertEquals("in the top left", PositionText.positionLabel(0.1f, 0.1f, SpeechLanguage.En))
        assertEquals("in the middle", PositionText.positionLabel(0.5f, 0.5f, SpeechLanguage.En))
        assertEquals("in the bottom right", PositionText.positionLabel(0.9f, 0.9f, SpeechLanguage.En))
        assertEquals("on the right", PositionText.positionLabel(0.9f, 0.5f, SpeechLanguage.En))
        assertEquals("at the top", PositionText.positionLabel(0.5f, 0.1f, SpeechLanguage.En))
        assertEquals("at the bottom", PositionText.positionLabel(0.5f, 0.9f, SpeechLanguage.En))
    }

    @Test
    fun `repere vertical nul dans la bande centrale`() {
        assertNull(PositionText.vertical(0.5f, SpeechLanguage.En))
        assertEquals("top", PositionText.vertical(0.1f, SpeechLanguage.En))
        assertEquals("bottom", PositionText.vertical(0.9f, SpeechLanguage.En))
    }

    @Test
    fun `phrase en anglais, de gauche a droite`() {
        val phrase = PositionText.phrase(
            listOf(placed("chair", 0.9f), placed("person", 0.1f), placed("book", 0.5f)),
            language = SpeechLanguage.En,
        )

        assertEquals("Person on the left, book in the middle, chair on the right.", phrase)
    }

    @Test
    fun `phrase avec distance en anglais`() {
        val phrase = PositionText.phrase(
            listOf(placed("person", 0.1f, meters = 1.44f), placed("dog", 0.8f, meters = 3.2f)),
            language = SpeechLanguage.En,
        )

        assertEquals("Person on the left, 1.4 meters away; dog on the right, 3.2 meters away.", phrase)
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

        assertEquals("Person on the left, chair on the left, and 2 other objects.", phrase)
    }

    @Test
    fun `le francais ne change pas`() {
        val phrase = PositionText.phrase(listOf(placed("personne", 0.1f, meters = 1.44f)))
        assertEquals("Personne à gauche, à 1,4 mètre.", phrase)
    }
}