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
    data class Call(val text: String, val flush: Boolean, val locale: Locale, val rate: Float, val pitch: Float = 1f)

    val calls = mutableListOf<Call>()
    var stops = 0
    var shutdowns = 0
    var speakingValue = false
    override val speaking: Boolean get() = speakingValue

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
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

/** Hooks de guidage : politique puis moteur, langue et débit des réglages. */
class VoiceNavSpeakerTest {

    private var now = 0L

    /** Le user parle-t-il ? Muté par les tests de priorité. */
    private var userSpeaking = false

    private fun speaker(
        driver: FakeSpeakerDriver = FakeSpeakerDriver(),
        settings: Settings = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr),
    ) = VoiceNavSpeaker(
        engine = SystemTtsEngine(driver),
        settings = { settings },
        clock = { now },
        userSpeaking = { userSpeaking },
    )

    @Test
    fun `virage parle francais en file`() {
        val driver = FakeSpeakerDriver()

        val phrase = speaker(driver).onTurn(SteeringCap.Left, 100f)

        assertEquals("Continuez dans cette direction.", phrase)
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
    fun `virage proche reparle apres le delai`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        now += 5_000L

        assertEquals("Tournez à gauche.", voice.onTurn(SteeringCap.Left, 20f))
        assertEquals(2, driver.calls.size)
    }

    @Test
    fun `virage proche dit l'ordre sec`() {
        val driver = FakeSpeakerDriver()

        val phrase = speaker(driver).onTurn(SteeringCap.Left, 20f, "rue de Rivoli")

        assertEquals("Tournez à gauche, rue de Rivoli.", phrase)
        assertEquals(1, driver.calls.size)
    }

    @Test
    fun `voix anglaise parle anglais`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver, Settings.Default.copy(speechLanguage = SpeechLanguage.En))

        val phrase = voice.onTurn(SteeringCap.Right, 100f, "Main Street")

        assertEquals("Continue in this direction, Main Street.", phrase)
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
    fun `user parle - les non urgings se taisent`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        userSpeaking = true

        assertNull(voice.onTurn(SteeringCap.Left, 100f))
        assertNull(voice.onLiteral("Au giratoire, prenez la deuxième sortie."))
        assertNull(voice.onBackOnRoute())
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `user parle - les urgences coupent et passent quand meme`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        userSpeaking = true
        voice.onTurn(SteeringCap.Left, 100f)

        // Hors-trace : urgence, la voix IA a le droit de couvrir le user.
        // (La consigne gardée sous le user n'a jamais atteint le moteur.)
        assertEquals("Vous vous êtes écarté de l'itinéraire. Recalcul en cours.", voice.onOffRoute())
        assertEquals(1, driver.calls.size)
        assertTrue(driver.calls[0].flush)

        // Recalcul : urgence aussi.
        assertEquals("Nouvel itinéraire calculé.", voice.onRerouted())
        assertEquals(2, driver.calls.size)

        // Arrivée : urgence.
        assertEquals("Vous êtes arrivé à la gare de Lyon.", voice.onArrival("la gare de Lyon"))
        assertEquals(3, driver.calls.size)
    }

    @Test
    fun `user se tait - la voix IA reprend`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        userSpeaking = true
        voice.onTurn(SteeringCap.Left, 100f)
        assertTrue(driver.calls.isEmpty())

        userSpeaking = false

        // La consigne retenue par la porte repart telle quelle : le silence
        // n'a pas marqué la phrase comme dite.
        assertEquals("Continuez dans cette direction.", voice.onTurn(SteeringCap.Left, 100f))
        assertEquals(1, driver.calls.size)
    }

    @Test
    fun `user parle - le danger obstacle passe quand meme`() {
        // La sonde obstacle est déjà urgent par nature (coupe, flush).
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        userSpeaking = true

        assertEquals("Va à gauche, 2 mètres.", voice.onHazard("Va à gauche, 2 mètres."))
        assertEquals(1, driver.calls.size)
        assertTrue(driver.calls[0].flush)
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
    fun `danger parle grave, consigne parle normal`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        voice.onOffRoute()
        voice.onHazard("Gauche.")

        assertEquals(1f, driver.calls[0].pitch, 0f)
        assertEquals(SystemTtsEngine.SAFETY_PITCH, driver.calls[1].pitch, 0f)
        assertEquals(SystemTtsEngine.SAFETY_PITCH, driver.calls[2].pitch, 0f)
    }

    @Test
    fun `douce admise en parole part en file, dite au silence`() {
        val driver = FakeSpeakerDriver().apply { speakingValue = true }
        val voice = speaker(driver)

        assertNull(voice.onTurn(SteeringCap.Left, 100f))
        assertTrue(driver.calls.isEmpty())

        driver.speakingValue = false
        // Le créneau est pris par la file : l'événement courant se réessaiera.
        assertNull(voice.onTurn(SteeringCap.Right, 100f))
        assertEquals(1, driver.calls.size)
        assertEquals("Continuez dans cette direction.", driver.calls.single().text)

        now += 5_000L
        assertEquals("Continuez dans cette direction.", voice.onTurn(SteeringCap.Right, 100f))
        assertEquals(2, driver.calls.size)
    }

    @Test
    fun `file garde la plus fraiche seule`() {
        val driver = FakeSpeakerDriver().apply { speakingValue = true }
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)
        voice.onLiteral("Au giratoire, prenez la deuxième sortie.")

        driver.speakingValue = false
        voice.onTurn(SteeringCap.Right, 100f)

        assertEquals(1, driver.calls.size)
        assertEquals("Au giratoire, prenez la deuxième sortie.", driver.calls.single().text)
    }

    @Test
    fun `file perimee jetee sans parler`() {
        assertEquals(5_000L, VoiceNavSpeaker.PENDING_TTL_MS)
        val driver = FakeSpeakerDriver().apply { speakingValue = true }
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        driver.speakingValue = false
        now += 6_000L
        assertEquals("Continuez dans cette direction.", voice.onTurn(SteeringCap.Right, 100f))

        assertEquals(1, driver.calls.size)
    }

    @Test
    fun `danger vide la file et coupe`() {
        val driver = FakeSpeakerDriver().apply { speakingValue = true }
        val voice = speaker(driver)
        voice.onTurn(SteeringCap.Left, 100f)

        val phrase = voice.onOffRoute()

        assertEquals("Vous vous êtes écarté de l'itinéraire. Recalcul en cours.", phrase)
        assertEquals(1, driver.calls.size)
        assertTrue(driver.calls.single().flush)

        driver.speakingValue = false
        voice.onTurn(SteeringCap.Right, 100f)

        assertEquals(1, driver.calls.size)
    }

    @Test
    fun `refus moteur non marque, reemission reparle`() {
        val driver = FakeSpeakerDriver(fail = true)
        val voice = speaker(driver)

        assertNull(voice.onTurn(SteeringCap.Left, 100f))

        driver.fail = false
        assertEquals("Continuez dans cette direction.", voice.onTurn(SteeringCap.Left, 100f))
        // Deux tentatives tracées (refusée puis réussie) : « non marqué » vise la
        // mémoire anti-répétition (la réémission reparle), pas le journal pilote.
        assertEquals(2, driver.calls.size)
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
