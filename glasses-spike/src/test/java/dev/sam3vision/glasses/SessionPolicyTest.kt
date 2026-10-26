package dev.sam3vision.glasses

import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.RegistrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions du cycle lunettes, testées sur JVM : aucun `Context`, aucun
 * Robolectric, aucun stub du SDK.
 *
 * `RegistrationState` et `StreamError` sont des enums Kotlin (vérifié par
 * `javap` sur `mwdat-core` / `mwdat-camera` 1.0.1) : leur initialisation ne
 * touche pas Android, elles s'utilisent donc telles quelles. Le reste de la
 * logique décisionnelle a été sorti du code DAT vers `GlassesSessionPolicy`
 * précisément pour être atteignable ici.
 */
class SessionPolicyTest {

    // ---- Démarrage sur de vraies lunettes ------------------------------------

    @Test
    fun `tout est pret, un seul conseil qui dit combien de paires sont vues`() {
        val advice = realStartAdvice(ready())
        assertEquals(1, advice.size)
        assertTrue(advice.single().startsWith("prêt : 1 paire"))
    }

    @Test
    fun `sans Developer Mode le conseil dit ou l activer`() {
        val advice = realStartAdvice(ready().copy(devMode = false))
        assertTrue(advice.any { it.contains("Developer Mode inactif") })
    }

    @Test
    fun `aucune paire vue, le conseil dit d appairer dans Meta AI`() {
        val advice = realStartAdvice(ready().copy(devices = 0))
        assertTrue(advice.any { it.contains("appaire la paire dans l'app Meta AI") })
    }

    @Test
    fun `camera DAT non accordee, le conseil dit ou cliquer`() {
        val advice = realStartAdvice(ready().copy(cameraAllowed = false))
        assertTrue(advice.any { it.contains("Always allow") })
    }

    @Test
    fun `enregistrement a accepter, et aucune ligne pour un etat deja bon`() {
        val waiting = realStartAdvice(ready().copy(registration = RegistrationState.AVAILABLE))
        assertEquals(1, waiting.size)
        assertTrue(waiting.single().contains("enregistrement à accepter"))

        val registered = realStartAdvice(ready())
        assertTrue(registered.none { it.contains("enregistrement") })
    }

    @Test
    fun `les quatre manques se cumulent dans l ordre dev mode, enregistrement, paire, camera`() {
        val advice = realStartAdvice(
            RealStartFacts(
                devMode = false,
                registration = RegistrationState.UNAVAILABLE,
                devices = 0,
                cameraAllowed = false,
            ),
        )
        assertEquals(4, advice.size)
        assertTrue(advice[0].contains("Developer Mode"))
        assertTrue(advice[1].contains("enregistrement"))
        assertTrue(advice[2].contains("aucune lunette"))
        assertTrue(advice[3].contains("caméra non accordée"))
    }

    private fun ready() = RealStartFacts(
        devMode = true,
        registration = RegistrationState.REGISTERED,
        devices = 1,
        cameraAllowed = true,
    )

    // ---- Enregistrement ------------------------------------------------------

    @Test
    fun `REGISTERED ouvre la session`() {
        val decision = registrationDecision(RegistrationState.REGISTERED)
        assertEquals(RegistrationStep.OPEN_SESSION, decision.step)
        assertEquals("lunettes enregistrées", decision.message)
    }

    @Test
    fun `AVAILABLE demande l enregistrement`() {
        val decision = registrationDecision(RegistrationState.AVAILABLE)
        assertEquals(RegistrationStep.REQUEST_REGISTRATION, decision.step)
        assertEquals("en attente d'enregistrement", decision.message)
    }

    @Test
    fun `un enregistrement en cours ne declenche aucune action`() {
        val decision = registrationDecision(RegistrationState.REGISTERING)
        assertEquals(RegistrationStep.LOG_ONLY, decision.step)
        assertEquals("enregistrement en cours", decision.message)
    }

    @Test
    fun `un desenregistrement en cours ne declenche aucune action`() {
        val decision = registrationDecision(RegistrationState.UNREGISTERING)
        assertEquals(RegistrationStep.LOG_ONLY, decision.step)
        assertEquals("désenregistrement en cours", decision.message)
    }

    @Test
    fun `sans lunettes disponibles on ne tente rien`() {
        val decision = registrationDecision(RegistrationState.UNAVAILABLE)
        assertEquals(RegistrationStep.LOG_ONLY, decision.step)
        assertEquals("aucune paire de lunettes disponible", decision.message)
    }

    /**
     * Garde de couverture : chaque état du SDK a un message, et deux états
     * seulement agissent (ouvrir la session, demander l'enregistrement). Un état
     * ajouté à l'enum casse la compilation du `when` ; ce test verrouille en plus
     * le fait qu'il n'y a qu'un seul chemin actif par action.
     */
    @Test
    fun `tous les etats du SDK sont couverts et deux seulement agissent`() {
        val decisions = RegistrationState.entries.associateWith(::registrationDecision)
        decisions.forEach { (state, decision) ->
            assertTrue("message vide pour $state", decision.message.isNotBlank())
        }
        assertEquals(
            "un seul état ouvre la session",
            1,
            decisions.count { it.value.step == RegistrationStep.OPEN_SESSION },
        )
        assertEquals(
            "un seul état demande l'enregistrement",
            1,
            decisions.count { it.value.step == RegistrationStep.REQUEST_REGISTRATION },
        )
    }

    // ---- Étranglement du flux ----------------------------------------------

    @Test
    fun `la premiere frame part sans attendre`() {
        // lastSentAtMs = 0 : l'horloge a déjà dépassé la fenêtre depuis longtemps.
        assertEquals(0L, throttleDelayMs(lastSentAtMs = 0L, nowMs = 100_000L))
    }

    @Test
    fun `deux frames au meme instant gardent toute la fenetre`() {
        assertEquals(
            FRAME_THROTTLE_MS,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L),
        )
    }

    @Test
    fun `une frame juste avant la fin de fenetre attend le reste`() {
        assertEquals(
            1L,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L + FRAME_THROTTLE_MS - 1),
        )
    }

    @Test
    fun `une frame apres la fenetre n attend plus`() {
        assertEquals(
            0L,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L + FRAME_THROTTLE_MS),
        )
        assertEquals(
            0L,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L + 10 * FRAME_THROTTLE_MS),
        )
    }

    @Test
    fun `une horloge qui recule ne rapproche pas l envoi`() {
        // nowMs < lastSentAtMs : l'attente dépasse la fenêtre, elle n'est pas bornée
        // vers le haut (mieux vaut attendre que d'envoyer deux frames d'un coup).
        assertEquals(FRAME_THROTTLE_MS + 1_000L, throttleDelayMs(lastSentAtMs = 11_000L, nowMs = 10_000L))
    }

    @Test
    fun `la fenetre par defaut est celle du LLM`() {
        assertEquals(1_500L, FRAME_THROTTLE_MS)
        assertEquals(FRAME_THROTTLE_MS, throttleDelayMs(lastSentAtMs = 5L, nowMs = 5L))
    }

    // ---- Erreurs de flux ---------------------------------------------------

    @Test
    fun `l erreur critique du flux arrete le cycle`() {
        assertEquals(
            StreamErrorDisposition.STOP_CYCLE,
            streamErrorDisposition(StreamError.CRITICAL_STREAM_ERROR),
        )
    }

    @Test
    fun `les erreurs transitoires n arretent pas le cycle`() {
        val transitoires = StreamError.entries.filter { it != StreamError.CRITICAL_STREAM_ERROR }
        assertTrue("des erreurs transitoires doivent exister", transitoires.isNotEmpty())
        transitoires.forEach { error ->
            assertEquals("$error ne doit pas arrêter le cycle", StreamErrorDisposition.LOG_ONLY, streamErrorDisposition(error))
        }
    }

    @Test
    fun `une seule erreur de flux est fatale`() {
        assertEquals(
            "seule CRITICAL_STREAM_ERROR coupe le cycle",
            1,
            StreamError.entries.count { streamErrorDisposition(it) == StreamErrorDisposition.STOP_CYCLE },
        )
    }

    // ---- Verdict sur une frame brute ---------------------------------------

    @Test
    fun `une frame brute exploitable est encodee`() {
        assertEquals(FrameVerdict.ENCODE, frameVerdict(false, false, 640, 480))
        // Cas dégénéré mais valide : 1x1 ne doit pas être jeté pour ses dimensions.
        assertEquals(FrameVerdict.ENCODE, frameVerdict(false, false, 1, 1))
    }

    @Test
    fun `une frame compressee est ecartee avant tout decodage`() {
        assertEquals(FrameVerdict.DROP_COMPRESSED, frameVerdict(true, false, 640, 480))
    }

    @Test
    fun `la compression prime sur le drapeau de configuration`() {
        assertEquals(FrameVerdict.DROP_COMPRESSED, frameVerdict(true, true, 640, 480))
    }

    @Test
    fun `une frame de configuration codec est ecartee`() {
        assertEquals(FrameVerdict.DROP_CODEC_CONFIG, frameVerdict(false, true, 640, 480))
    }

    @Test
    fun `des dimensions nulles ou negatives ecartent la frame`() {
        assertEquals(FrameVerdict.DROP_DIMENSIONS, frameVerdict(false, false, 0, 480))
        assertEquals(FrameVerdict.DROP_DIMENSIONS, frameVerdict(false, false, 640, 0))
        assertEquals(FrameVerdict.DROP_DIMENSIONS, frameVerdict(false, false, -2, -2))
    }
}