package dev.sam3vision.core.segment

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Déclencheur voix TwinLite : stabilité, nouveauté, délai — et STOP urgent.
 * L'annonceur est construit en positionnel (cadence, délai).
 */
class TwinLiteAnnouncerTest {

    /** Conseil cohérent : le tiers du cap est plein, les autres vides. */
    private fun conseil(direction: TwinLiteDirection): TwinLiteAdvice = when (direction) {
        TwinLiteDirection.LEFT -> TwinLiteAdvice(direction, 1f, 0f, 0f)
        TwinLiteDirection.STRAIGHT -> TwinLiteAdvice(direction, 0f, 1f, 0f)
        TwinLiteDirection.RIGHT -> TwinLiteAdvice(direction, 0f, 0f, 1f)
        TwinLiteDirection.STOP -> TwinLiteAdvice(direction, 0f, 0f, 0f)
    }

    @Test
    fun `une direction isolee ne parle pas`() {
        val annonceur = TwinLiteAnnouncer(2, 4_000L)

        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `une direction stable parle une fois puis se tait`() {
        val annonceur = TwinLiteAnnouncer(2, 4_000L)

        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr))
        val signal = annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr)

        assertEquals(TwinLiteDirection.LEFT, signal?.direction)
        assertFalse(signal?.urgent ?: true)
        assertEquals(TwinLiteText.phrase(TwinLiteDirection.LEFT, SpeechLanguage.Fr), signal?.phrase)
        // Délai écoulé mais même cap : nouveauté seule fait parler.
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 5_000L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 6_000L, SpeechLanguage.Fr))
    }

    @Test
    fun `un changement de cap casse la serie`() {
        val annonceur = TwinLiteAnnouncer(3, 60_000L)

        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.RIGHT), 1_000L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 1_500L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 2_000L, SpeechLanguage.Fr))
    }

    @Test
    fun `une nouvelle direction attend le delai`() {
        val annonceur = TwinLiteAnnouncer(2, 4_000L)

        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr)
        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr)
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.RIGHT), 1_000L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.RIGHT), 1_500L, SpeechLanguage.Fr))

        val signal = annonceur.onAdvice(conseil(TwinLiteDirection.RIGHT), 4_600L, SpeechLanguage.Fr)

        assertEquals(TwinLiteDirection.RIGHT, signal?.direction)
        assertFalse(signal?.urgent ?: true)
    }

    @Test
    fun `stop parle urgent sans attendre le delai`() {
        val annonceur = TwinLiteAnnouncer(2, 60_000L)

        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr)
        val precedent = annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr)

        assertFalse(precedent?.urgent ?: true)
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.STOP), 1_000L, SpeechLanguage.Fr))
        val signal = annonceur.onAdvice(conseil(TwinLiteDirection.STOP), 1_500L, SpeechLanguage.Fr)

        assertEquals(TwinLiteDirection.STOP, signal?.direction)
        assertTrue(signal?.urgent ?: false)
        assertEquals(TwinLiteText.phrase(TwinLiteDirection.STOP, SpeechLanguage.Fr), signal?.phrase)
    }

    @Test
    fun `null casse la serie`() {
        val annonceur = TwinLiteAnnouncer(3, 60_000L)

        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(null, 1_000L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 1_500L, SpeechLanguage.Fr))
        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 2_000L, SpeechLanguage.Fr))

        val signal = annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 2_500L, SpeechLanguage.Fr)

        assertEquals(TwinLiteDirection.LEFT, signal?.direction)
    }

    @Test
    fun `reset oublie tout`() {
        val annonceur = TwinLiteAnnouncer(2, 4_000L)

        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr)
        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr)
        annonceur.reset()

        assertNull(annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 1_000L, SpeechLanguage.Fr))
        val signal = annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 1_500L, SpeechLanguage.Fr)

        assertEquals(TwinLiteDirection.LEFT, signal?.direction)
    }

    @Test
    fun `la phrase suit la langue de la voix`() {
        val annonceur = TwinLiteAnnouncer(2, 4_000L)

        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 0L, SpeechLanguage.Fr)
        val francais = annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 500L, SpeechLanguage.Fr)

        assertEquals(TwinLiteText.phrase(TwinLiteDirection.LEFT, SpeechLanguage.Fr), francais?.phrase)
        annonceur.reset()
        annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 1_000L, SpeechLanguage.En)
        val anglais = annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), 1_500L, SpeechLanguage.En)

        assertEquals(TwinLiteText.phrase(TwinLiteDirection.LEFT, SpeechLanguage.En), anglais?.phrase)
    }

    @Test
    fun `les quatre cles sont distinctes`() {
        val cles = TwinLiteDirection.entries.map { TwinLiteText.key(it) }.toSet()

        assertEquals(4, cles.size)
    }

    @Test
    fun `chaque direction a sa phrase dans les deux langues`() {
        for (direction in TwinLiteDirection.entries) {
            for (langue in SpeechLanguage.entries) {
                assertTrue(TwinLiteText.phrase(direction, langue).isNotBlank())
            }
        }
    }

    @Test
    fun `par defaut une direction stable finit par parler`() {
        val annonceur = TwinLiteAnnouncer()

        val signaux = (0..5).map { i ->
            annonceur.onAdvice(conseil(TwinLiteDirection.LEFT), i * 500L, SpeechLanguage.Fr)
        }

        assertTrue(signaux.any { it?.direction == TwinLiteDirection.LEFT })
        assertTrue(signaux.filterNotNull().all { !it.urgent })
    }

    @Test
    fun `par defaut un stop stable parle urgent`() {
        val annonceur = TwinLiteAnnouncer()

        val signaux = (0..5).map { i ->
            annonceur.onAdvice(conseil(TwinLiteDirection.STOP), i * 500L, SpeechLanguage.Fr)
        }

        assertTrue(signaux.any { it?.direction == TwinLiteDirection.STOP && it.urgent })
    }
}
