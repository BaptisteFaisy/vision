package dev.sam3vision.app.speech

import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux pilote : enregistre les appels, échec et parole programmables. */
private class FakeDriver(
    var fail: Boolean = false,
    var throwOnSpeak: Boolean = false,
    var throwOnSpeaking: Boolean = false,
) : TtsDriver {
    data class Call(val text: String, val flush: Boolean, val locale: Locale, val rate: Float, val pitch: Float = 1f)

    val calls = mutableListOf<Call>()
    var stops = 0
    var shutdowns = 0
    var speakingValue = false

    override val speaking: Boolean
        get() = if (throwOnSpeaking) throw IllegalStateException("pilote en panne") else speakingValue

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
        if (throwOnSpeak) throw IllegalStateException("pilote en panne")
        calls += Call(text, flush, locale, rate, pitch)
        return !fail
    }

    override fun stop() {
        stops += 1
    }

    override fun shutdown() {
        shutdowns += 1
    }
}

/** File sans chevauchement, coupe urgente, cycle de vie du moteur TTS système. */
class SystemTtsEngineTest {

    @Test
    fun `phrase normale mise en file`() {
        val driver = FakeDriver()
        val engine = SystemTtsEngine(driver)

        assertTrue(engine.speak("À gauche.", urgent = false, Locale.FRENCH, 1f))

        assertEquals(listOf(FakeDriver.Call("À gauche.", false, Locale.FRENCH, 1f)), driver.calls)
    }

    @Test
    fun `urgente coupe la phrase en cours`() {
        val driver = FakeDriver()
        val engine = SystemTtsEngine(driver)

        engine.speak("Tout droit.", urgent = false, Locale.FRENCH, 1f)
        engine.speak("Recalcul en cours.", urgent = true, Locale.FRENCH, 1f)

        assertEquals(2, driver.calls.size)
        assertFalse(driver.calls[0].flush)
        assertTrue(driver.calls[1].flush)
    }

    @Test
    fun `blanc refuse sans toucher le pilote`() {
        val driver = FakeDriver()
        val engine = SystemTtsEngine(driver)

        assertFalse(engine.speak("   ", urgent = false, Locale.FRENCH, 1f))

        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `texte rogne et debit borne comme la voix ElevenLabs`() {
        val driver = FakeDriver()
        val engine = SystemTtsEngine(driver)

        engine.speak("  À gauche. ", urgent = false, Locale.FRENCH, 9.9f)
        engine.speak("À droite.", urgent = false, Locale.FRENCH, 0.1f)

        assertEquals("À gauche.", driver.calls[0].text)
        assertEquals(2.0f, driver.calls[0].rate, 0f)
        assertEquals(0.5f, driver.calls[1].rate, 0f)
    }

    @Test
    fun `pilote qui jette devient un refus`() {
        val engine = SystemTtsEngine(FakeDriver(throwOnSpeak = true))

        assertFalse(engine.speak("À gauche.", urgent = false, Locale.FRENCH, 1f))
    }

    @Test
    fun `pilote en echec remonte faux`() {
        val engine = SystemTtsEngine(FakeDriver(fail = true))

        assertFalse(engine.speak("À gauche.", urgent = false, Locale.FRENCH, 1f))
    }

    @Test
    fun `parole en cours remontee, echec vaut silence`() {
        val talking = FakeDriver().apply { speakingValue = true }
        assertTrue(SystemTtsEngine(talking).speaking)
        assertFalse(SystemTtsEngine(FakeDriver()).speaking)
        assertFalse(SystemTtsEngine(FakeDriver(throwOnSpeaking = true)).speaking)
    }

    @Test
    fun `fermeture idempotente puis refus silencieux`() {
        val driver = FakeDriver().apply { speakingValue = true }
        val engine = SystemTtsEngine(driver)

        engine.close()
        engine.close()
        engine.stop()

        assertEquals(0, driver.stops)
        assertEquals(1, driver.shutdowns)
        assertFalse(engine.speak("À gauche.", urgent = true, Locale.FRENCH, 1f))
        assertFalse(engine.speaking)
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `tampon rejoue a l init dans l ordre puis vide`() {
        val buffer = TtsInitBuffer()
        buffer.add("Un.", flush = false, Locale.FRENCH, 1f)
        buffer.add("Deux.", flush = false, Locale.FRENCH, 1f)

        val replayed = buffer.drain()

        assertEquals(listOf("Un.", "Deux."), replayed.map { it.text })
        assertEquals(0, buffer.size)
        assertTrue(buffer.drain().isEmpty())

        buffer.add("Trois.", flush = false, Locale.FRENCH, 1f)
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun `urgente vide le tampon, debordement jette les vieilles`() {
        val buffer = TtsInitBuffer(max = 2)
        buffer.add("Un.", flush = false, Locale.FRENCH, 1f)
        buffer.add("Deux.", flush = false, Locale.FRENCH, 1f)
        buffer.add("Trois.", flush = false, Locale.FRENCH, 1f)

        assertEquals(listOf("Deux.", "Trois."), buffer.drain().map { it.text })

        buffer.add("Quatre.", flush = false, Locale.FRENCH, 1f)
        buffer.add("Stop.", flush = true, Locale.FRENCH, 1f)

        val replayed = buffer.drain()
        assertEquals(listOf("Stop."), replayed.map { it.text })
        assertTrue(replayed.single().flush)
    }

    @Test
    fun `langue de la voix vers locale du moteur`() {
        assertEquals(Locale.FRENCH, SystemTtsEngine.localeOf(SpeechLanguage.Fr))
        assertEquals(Locale.ENGLISH, SystemTtsEngine.localeOf(SpeechLanguage.En))
    }

    @Test
    fun `pitch transmis au pilote, normal par defaut`() {
        val driver = FakeDriver()
        val engine = SystemTtsEngine(driver)

        engine.speak("Bordure à 0,5 mètre.", urgent = true, Locale.FRENCH, 1f, SystemTtsEngine.SAFETY_PITCH)
        engine.speak("À gauche.", urgent = false, Locale.FRENCH, 1f)

        assertEquals(SystemTtsEngine.SAFETY_PITCH, driver.calls[0].pitch, 0f)
        assertEquals(1f, driver.calls[1].pitch, 0f)
    }

    @Test
    fun `pitch borne 0_5_2`() {
        assertEquals(0.5f, SystemTtsEngine.clampPitch(0f), 0f)
        assertEquals(2f, SystemTtsEngine.clampPitch(9.9f), 0f)
        assertEquals(0.75f, SystemTtsEngine.clampPitch(SystemTtsEngine.SAFETY_PITCH), 0f)
    }
}
