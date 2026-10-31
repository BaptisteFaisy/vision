package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bascule mains-libres ([GlassesTalkPolicy]) : l'équivalent aux lunettes du
 * « tap maintenu » du téléphone — premier appui long = le micro s'ouvre,
 * second appui long = la question part, sinon envoi auto à 20 s.
 */
class GlassesTalkPolicyTest {

    @Test
    fun `au repos, l appui long ouvre l ecoute quand le micro est pret`() {
        assertEquals(TalkPress.STARTED, GlassesTalkPolicy.press(GlassesTalkPhase.IDLE, micReady = true))
    }

    @Test
    fun `au repos sans micro, l appui long rend le repli`() {
        assertEquals(TalkPress.MIC_UNAVAILABLE, GlassesTalkPolicy.press(GlassesTalkPhase.IDLE, micReady = false))
    }

    @Test
    fun `en ecoute, l appui long envoie`() {
        assertEquals(TalkPress.SENDING, GlassesTalkPolicy.press(GlassesTalkPhase.LISTENING, micReady = true))
    }

    @Test
    fun `pendant l envoi, l appui est ignore`() {
        assertEquals(TalkPress.IGNORED, GlassesTalkPolicy.press(GlassesTalkPhase.SENDING, micReady = true))
    }

    @Test
    fun `le delai n envoie que si l ecoute est en cours`() {
        assertTrue(GlassesTalkPolicy.timeout(GlassesTalkPhase.LISTENING))
        assertFalse(GlassesTalkPolicy.timeout(GlassesTalkPhase.IDLE))
        assertFalse(GlassesTalkPolicy.timeout(GlassesTalkPhase.SENDING))
    }

    @Test
    fun `le plafond d ecoute vaut 20 secondes`() {
        assertEquals(20_000L, GlassesTalkPolicy.MAX_LISTEN_MS)
    }

    @Test
    fun `a la session, l ecoute auto n ouvre que micro pret et repos`() {
        assertTrue(GlassesTalkPolicy.autoListen(GlassesTalkPhase.IDLE, micReady = true))
        assertFalse(GlassesTalkPolicy.autoListen(GlassesTalkPhase.IDLE, micReady = false))
    }

    @Test
    fun `l ecoute auto n envoie jamais seule`() {
        assertFalse(GlassesTalkPolicy.autoListen(GlassesTalkPhase.LISTENING, micReady = true))
        assertFalse(GlassesTalkPolicy.autoListen(GlassesTalkPhase.SENDING, micReady = true))
    }
}
