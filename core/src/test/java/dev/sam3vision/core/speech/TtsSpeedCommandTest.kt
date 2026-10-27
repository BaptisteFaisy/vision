package dev.sam3vision.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsSpeedCommandTest {

    @Test
    fun `vitesse explicite avec virgule`() {
        assertEquals(1.5f, TtsSpeedCommand.parse("vitesse 1,5", 1.9f)!!, 0.001f)
    }

    @Test
    fun `vitesse explicite avec point et phrase complete`() {
        assertEquals(1.2f, TtsSpeedCommand.parse("mets la vitesse à 1.2", 1.9f)!!, 0.001f)
    }

    @Test
    fun `speed explicite en anglais`() {
        assertEquals(2.0f, TtsSpeedCommand.parse("speed 2", 1.0f)!!, 0.001f)
    }

    @Test
    fun `explicite hors bornes borne`() {
        assertEquals(2.0f, TtsSpeedCommand.parse("vitesse 3", 1.9f)!!, 0.001f)
        assertEquals(0.5f, TtsSpeedCommand.parse("vitesse 0", 1.9f)!!, 0.001f)
    }

    @Test
    fun `parle plus vite monte d un cran`() {
        assertEquals(2.0f, TtsSpeedCommand.parse("parle plus vite", 1.9f)!!, 0.001f)
        assertEquals(1.4f, TtsSpeedCommand.parse("plus vite", 1.2f)!!, 0.001f)
    }

    @Test
    fun `moins vite et ralentis descendent d un cran`() {
        assertEquals(1.7f, TtsSpeedCommand.parse("parle moins vite", 1.9f)!!, 0.001f)
        assertEquals(1.7f, TtsSpeedCommand.parse("ralentis", 1.9f)!!, 0.001f)
        assertEquals(0.5f, TtsSpeedCommand.parse("ralentis", 0.5f)!!, 0.001f)
    }

    @Test
    fun `faster et slower anglais`() {
        assertEquals(1.4f, TtsSpeedCommand.parse("faster", 1.2f)!!, 0.001f)
        assertEquals(1.0f, TtsSpeedCommand.parse("slow down", 1.2f)!!, 0.001f)
    }

    @Test
    fun `vitesse normale revient a un`() {
        assertEquals(1.0f, TtsSpeedCommand.parse("vitesse normale", 1.9f)!!, 0.001f)
        assertEquals(1.0f, TtsSpeedCommand.parse("normal speed", 1.9f)!!, 0.001f)
    }

    @Test
    fun `decris vite ne change pas la voix`() {
        assertNull(TtsSpeedCommand.parse("décris vite la pièce", 1.9f))
        assertNull(TtsSpeedCommand.parse("c'est lent", 1.9f))
    }

    @Test
    fun `une question ordinaire ne change pas la voix`() {
        assertNull(TtsSpeedCommand.parse("décris-moi la pièce", 1.9f))
        assertNull(TtsSpeedCommand.parse("quelle est la vitesse ?", 1.9f))
        assertNull(TtsSpeedCommand.parse("", 1.9f))
        assertNull(TtsSpeedCommand.parse("   ", 1.9f))
    }

    @Test
    fun `confirmation dite dans la langue de la voix`() {
        assertEquals("Vitesse 1,5", TtsSpeedCommand.spoken(1.5f, SpeechLanguage.Fr))
        assertEquals("Speed 1.5", TtsSpeedCommand.spoken(1.5f, SpeechLanguage.En))
        assertEquals("Speed 2", TtsSpeedCommand.spoken(2.0f, SpeechLanguage.En))
    }
}
