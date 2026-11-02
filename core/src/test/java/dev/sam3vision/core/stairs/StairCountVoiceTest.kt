package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voix du compteur de marches : le compte est dit à la détection puis à
 * chaque changement **lissé** (médiane de 3 ticks, 900 ms — le compteur
 * Canny est photométriquement volatil), au plus toutes les 1 s — et le
 * même compte se répète toutes les 1 s tant que l'escalier reste vu (dans
 * l'escalier, le compte reste utile, l'esquive parle au milieu), jamais
 * en boucle serrée.
 */
class StairCountVoiceTest {

    @Test
    fun `premiere detection dit le compte`() {
        assertEquals("5 marches.", StairCountVoice().onTick(true, 5, 1_000L)?.phrase)
    }

    @Test
    fun `meme compte se repete apres 1 s, pas avant`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L)?.phrase)
        assertNull(voice.onTick(true, 5, 1_100L)?.phrase)
        assertNull(voice.onTick(true, 5, 1_999L)?.phrase)
        assertEquals("5 marches.", voice.onTick(true, 5, 2_000L)?.phrase)
        assertNull(voice.onTick(true, 5, 2_100L)?.phrase)
        assertEquals("5 marches.", voice.onTick(true, 5, 3_000L)?.phrase)
    }

    @Test
    fun `compte change demande une mediane de 3 ticks`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L)?.phrase)
        assertNull(voice.onTick(true, 6, 1_300L)?.phrase)
        assertNull(voice.onTick(true, 6, 1_600L)?.phrase)
        assertNull(voice.onTick(true, 6, 1_900L)?.phrase)
        assertEquals("6 marches.", voice.onTick(true, 6, 2_000L)?.phrase)
    }

    @Test
    fun `oscillation du compteur ne fait pas begayer`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L)?.phrase)
        // Une marche qui entre et sort du cadre toutes les 300 ms : le
        // candidat 6 ne tient jamais 600 ms, rien ne se dit.
        assertNull(voice.onTick(true, 6, 1_300L)?.phrase)
        assertNull(voice.onTick(true, 5, 1_600L)?.phrase)
        assertNull(voice.onTick(true, 6, 1_900L)?.phrase)
        // Le compte stable reste 5 : seule la répétition d'1 s parle.
        assertEquals("5 marches.", voice.onTick(true, 5, 2_000L)?.phrase)
    }

    @Test
    fun `montee reelle dit le nouveau compte apres stabilite`() {
        val voice = StairCountVoice()
        assertEquals("8 marches.", voice.onTick(true, 8, 1_000L)?.phrase)
        assertNull(voice.onTick(true, 7, 1_300L)?.phrase)
        assertNull(voice.onTick(true, 7, 1_600L)?.phrase)
        assertNull(voice.onTick(true, 7, 1_900L)?.phrase)
        assertEquals("7 marches.", voice.onTick(true, 7, 2_000L)?.phrase)
    }

    @Test
    fun `escalier perdu puis revu redit le compte apres 1 s`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L)?.phrase)
        assertNull(voice.onTick(false, 0, 1_100L))
        assertNull(voice.onTick(true, 5, 1_400L)?.phrase)
        assertEquals("5 marches.", voice.onTick(true, 5, 2_000L)?.phrase)
    }

    @Test
    fun `escalier perdu 2 s oublie le compte, la rencontre suivante parle`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L)?.phrase)
        // Trou court (compte périmé) : le lissage garde sa fenêtre.
        assertNull(voice.onTick(false, 0, 1_100L)?.phrase)
        // Perdu depuis plus de 1,5 s : la mémoire est vidée.
        assertNull(voice.onTick(false, 0, 3_000L)?.phrase)
        assertEquals("3 marches.", voice.onTick(true, 3, 3_100L)?.phrase)
    }

    @Test
    fun `compte zero = non compte, silence`() {
        val voice = StairCountVoice()
        assertNull(voice.onTick(true, 0, 1_000L)?.phrase)
        assertEquals("4 marches.", voice.onTick(true, 4, 1_100L)?.phrase)
    }

    @Test
    fun `reset oublie compte et instant`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L)?.phrase)
        voice.reset()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_100L)?.phrase)
    }

    @Test
    fun `chaines en clips en, vides hors pack`() {
        val en = StairCountVoice().onTick(true, 5, 1_000L, SpeechLanguage.En)
        assertEquals("5 steps.", en?.phrase)
        assertEquals(listOf("c_5", "c_steps"), en?.clips)
        val one = StairCountVoice().onTick(true, 1, 1_000L, SpeechLanguage.En)
        assertEquals(listOf("c_1", "c_step"), one?.clips)
        val fr = StairCountVoice().onTick(true, 5, 1_000L, SpeechLanguage.Fr)
        assertEquals("5 marches.", fr?.phrase)
        assertEquals(emptyList<String>(), fr?.clips)
    }

    @Test
    fun `phrase singulier et pluriel fr-en`() {
        assertEquals("1 marche.", StairText.stepCount(1, SpeechLanguage.Fr))
        assertEquals("5 marches.", StairText.stepCount(5, SpeechLanguage.Fr))
        assertEquals("1 step.", StairText.stepCount(1, SpeechLanguage.En))
        assertEquals("5 steps.", StairText.stepCount(5, SpeechLanguage.En))
    }
}
