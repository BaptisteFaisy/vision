package dev.sam3vision.app.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte anti-bruit ([GlassesSpeechGate]) : le silence et les fantômes ne
 * partent jamais au LLM. PCM 16 kHz mono 16 bits synthétique — aucun Android.
 */
class GlassesSpeechGateTest {

    @Test
    fun `le silence pur n a pas de parole`() {
        assertFalse(GlassesSpeechGate.hasSpeech(ByteArray(32_000)))
        assertFalse(GlassesSpeechGate.hasSpeech(ByteArray(0)))
    }

    @Test
    fun `quelques clics ne font pas une parole`() {
        val pcm = ByteArray(32_000)
        repeat(10) { k ->
            pcm[k * 2] = 0
            pcm[k * 2 + 1] = 8 // 2048, fort mais 10 échantillons < 800 requis
        }
        assertFalse(GlassesSpeechGate.hasSpeech(pcm))
    }

    @Test
    fun `une seconde de voix est detectee`() {
        val pcm = ByteArray(32_000) { if (it % 2 == 1) 3.toByte() else 0.toByte() } // 768 constant
        assertTrue(GlassesSpeechGate.hasSpeech(pcm))
    }

    @Test
    fun `le vide et les scories sont jetes`() {
        assertTrue(GlassesSpeechGate.isJunkTranscript(""))
        assertTrue(GlassesSpeechGate.isJunkTranscript("   "))
        assertTrue(GlassesSpeechGate.isJunkTranscript("..."))
        assertTrue(GlassesSpeechGate.isJunkTranscript("a"))
        assertTrue(GlassesSpeechGate.isJunkTranscript("Merci d'avoir regardé cette vidéo !"))
        assertTrue(GlassesSpeechGate.isJunkTranscript("Sous-titres réalisés par la communauté d'Amara.org"))
        assertTrue(GlassesSpeechGate.isJunkTranscript("Thanks for watching."))
    }

    @Test
    fun `les vraies questions partent, meme courtes`() {
        assertFalse(GlassesSpeechGate.isJunkTranscript("où sont les toilettes"))
        assertFalse(GlassesSpeechGate.isJunkTranscript("stop"))
        assertFalse(GlassesSpeechGate.isJunkTranscript("oui"))
        assertFalse(GlassesSpeechGate.isJunkTranscript("merci"))
    }
}
