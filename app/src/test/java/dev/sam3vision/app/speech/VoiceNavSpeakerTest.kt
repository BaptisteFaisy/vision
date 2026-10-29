package dev.sam3vision.app.speech

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux pilote : enregistre les appels, échec programmable. */
private class FakeSpeakerDriver(var fail: Boolean = false) : TtsDriver {
    data class Call(val text: String, val flush: Boolean, val locale: Locale, val rate: Float)

    val calls = mutableListOf<Call>()
    var stops = 0
    var shutdowns = 0
    override val speaking: Boolean get() = false

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float): Boolean {
        calls += Call(text, flush, locale, rate)
        return !fail
    }

    override fun stop() {
        stops += 1
    }

    override fun shutdown() {
        shutdowns += 1
    }
}

/** Hooks de guidage : politique puis moteur, langue et débit des réglages. */
class VoiceNavSpeakerTest {

    private var now = 0L

    private fun speaker(
        driver: FakeSpeakerDriver = FakeSpeakerDriver(),
        settings: Settings = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr),
    ) = VoiceNavSpeaker(
        engine = SystemTtsEngine(driver),
        settings = { settings },
        clock = { now },
    )

    @Test
    fun `virage parle francais en file`() {
        val driver = FakeSpeakerDriver()

        val phrase = speaker(driver).onTurn(SteeringCap.Left, 100f)

        assertEquals("Dans 100 mètres, tournez à gauche.", phrase)
        assertEquals(1, driver.calls.size)
        assertEquals(FakeSpeakerDriver.Call(phrase!!, false, Locale.FRENCH, Settings.Default.ttsSpeed), driver.calls[0])
    }

    @Test
    fun `repetition garde le silence`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        assertNull(voice.onTurn(SteeringCap.Left, 100f))
        assertEquals(1, driver.calls.size)
    }

    @Test
    fun `nouvelle distance reparle apres le delai`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        now += 5_000L

        assertEquals("Dans 50 mètres, tournez à gauche.", voice.onTurn(SteeringCap.Left, 50f))
        assertEquals(2, driver.calls.size)
    }

    @Test
    fun `voix anglaise parle anglais`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver, Settings.Default.copy(speechLanguage = SpeechLanguage.En))

        val phrase = voice.onTurn(SteeringCap.Right, 100f, "Main Street")

        assertEquals("In 100 meters, turn right, Main Street.", phrase)
        assertEquals(Locale.ENGLISH, driver.calls.single().locale)
    }

    @Test
    fun `hors trace coupe la phrase en cours`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        val phrase = voice.onOffRoute()

        assertEquals("Vous vous êtes écarté de l'itinéraire. Recalcul en cours.", phrase)
        assertEquals(2, driver.calls.size)
        assertTrue(driver.calls[1].flush)
    }

    @Test
    fun `arrivee recalcul depart`() {
        val arrivals = FakeSpeakerDriver()
        assertEquals(
            "Vous êtes arrivé à la gare de Lyon.",
            speaker(arrivals).onArrival("la gare de Lyon"),
        )
        val reroutes = FakeSpeakerDriver()
        assertEquals("Nouvel itinéraire calculé.", speaker(reroutes).onRerouted())
        assertTrue(reroutes.calls.single().flush)
        val starts = FakeSpeakerDriver()
        assertEquals(
            "Navigation vers la gare de Lyon",
            speaker(starts).onStarted("la gare de Lyon"),
        )
    }

    @Test
    fun `depart blanc silencieux`() {
        val driver = FakeSpeakerDriver()

        assertNull(speaker(driver).onStarted("   "))
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `arret parle puis oublie la session`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onStarted("la gare de Lyon")

        assertEquals("Guidage arrêté.", voice.onStopped())
        assertEquals(
            "Navigation vers la gare de Lyon",
            voice.onStarted("la gare de Lyon"),
        )
    }

    @Test
    fun `moteur refuse vaut silence`() {
        val voice = speaker(FakeSpeakerDriver(fail = true))

        assertNull(voice.onTurn(SteeringCap.Left, 100f))
    }

    @Test
    fun `reglages en panne valent silence sans lever`() {
        val driver = FakeSpeakerDriver()
        val voice = VoiceNavSpeaker(SystemTtsEngine(driver), settings = { throw IllegalStateException("panne") })

        assertNull(voice.onTurn(SteeringCap.Left, 100f))
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `danger coupe la consigne en cours`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        val phrase = voice.onHazard("Gauche.")

        assertEquals("Gauche.", phrase)
        assertEquals(2, driver.calls.size)
        assertTrue(driver.calls[1].flush)
    }

    @Test
    fun `danger blanc silencieux`() {
        val driver = FakeSpeakerDriver()

        assertNull(speaker(driver).onHazard("   "))
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `danger refuse par le moteur vaut silence`() {
        val voice = speaker(FakeSpeakerDriver(fail = true))

        assertNull(voice.onHazard("Gauche."))
    }

    @Test
    fun `arret et liberation delegues au moteur`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)

        voice.stop()
        voice.shutdown()

        assertEquals(1, driver.stops)
        assertEquals(1, driver.shutdowns)
        assertNull(voice.onTurn(SteeringCap.Left, 100f))
        assertTrue(driver.calls.isEmpty())
    }
}
