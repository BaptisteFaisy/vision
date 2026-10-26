package dev.sam3vision.core.distance

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Distance prononcée en anglais : point décimal, singulier à 1, pluriel sinon. */
class DistanceTextEnglishTest {

    @Test
    fun `centimetres en deca d'un metre`() {
        assertEquals("85 centimeters", DistanceText.spoken(0.85f, SpeechLanguage.En))
        assertEquals("1 centimeter", DistanceText.spoken(0.01f, SpeechLanguage.En))
    }

    @Test
    fun `metres avec une decimale jusqu'a dix metres`() {
        assertEquals("1.4 meters", DistanceText.spoken(1.44f, SpeechLanguage.En))
        assertEquals("3.3 meters", DistanceText.spoken(3.288f, SpeechLanguage.En))
    }

    @Test
    fun `un seul metre reste au singulier`() {
        assertEquals("1 meter", DistanceText.spoken(1.04f, SpeechLanguage.En))
    }

    @Test
    fun `metres entiers au-dela de dix metres`() {
        assertEquals("12 meters", DistanceText.spoken(12.4f, SpeechLanguage.En))
        assertEquals("23 meters", DistanceText.spoken(23.4f, SpeechLanguage.En))
    }

    @Test
    fun `distance inconnue`() {
        assertEquals("distance unknown", DistanceText.spoken(0f, SpeechLanguage.En))
        assertEquals("distance unknown", DistanceText.spoken(-1f, SpeechLanguage.En))
    }

    @Test
    fun `le francais ne change pas`() {
        assertEquals("85 centimètres", DistanceText.spoken(0.85f))
        assertEquals("1,4 mètre", DistanceText.spoken(1.44f))
        assertEquals("distance inconnue", DistanceText.spoken(0f))
    }
}
