package dev.sam3vision.app.isee

import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.segment.TwinLiteCue
import dev.sam3vision.core.segment.TwinLiteDirection
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Voix TwinLite ElevenLabs : clip du pack d'abord, synthèse bornée ensuite,
 * silence + remplissage fond si trop lent. Joueur et synthèse injectés.
 */
class TwinLiteVoiceTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private val jouees = mutableListOf<File>()
    private val journaux = mutableListOf<String>()
    private var syntheses = 0

    private fun voix(
        octets: ByteArray? = "SYNTH".toByteArray(),
        pack: VoicePack? = null,
    ): TwinLiteVoice {
        val fauxPack = pack ?: VoicePack(dossier.root, { "voix-pack" }, { null })
        return TwinLiteVoice(
            play = { jouees += it; true },
            synth = { syntheses++; octets },
            pack = fauxPack,
            scope = CoroutineScope(Dispatchers.Unconfined),
            voiceId = { "voix-pack" },
            log = { journaux += it },
        )
    }

    private fun signal(direction: TwinLiteDirection) =
        TwinLiteCue("consigne.", direction == TwinLiteDirection.STOP, direction)

    @Test
    fun `le clip du pack joue sans synthèse`() = runBlocking {
        val fauxPack = VoicePack(dossier.root, { "voix-pack" }, { "CLIP".toByteArray() })

        val dit = voix(pack = fauxPack).say(signal(TwinLiteDirection.LEFT), SpeechLanguage.Fr)

        assertTrue(dit)
        assertEquals(1, jouees.size)
        assertEquals(0, syntheses)
        assertTrue(journaux.any { it.contains("clip") })
    }

    @Test
    fun `sans clip la synthèse joue et remplit le cache`() = runBlocking {
        val parleur = voix()

        val dit = parleur.say(signal(TwinLiteDirection.RIGHT), SpeechLanguage.Fr)

        assertTrue(dit)
        assertEquals(1, jouees.size)
        assertEquals("SYNTH", jouees.single().readText())
        assertTrue(journaux.any { it.contains("synth") })
        val parleur2 = voix()
        jouees.clear()

        assertTrue(parleur2.say(signal(TwinLiteDirection.RIGHT), SpeechLanguage.Fr))
        assertEquals(1, syntheses)
    }

    @Test
    fun `synthèse en panne silence et remplissage fond`() = runBlocking {
        val parleur = voix(octets = null)

        val dit = parleur.say(signal(TwinLiteDirection.STRAIGHT), SpeechLanguage.Fr)

        assertFalse(dit)
        assertTrue(jouees.isEmpty())
        assertEquals(2, syntheses)
    }

    @Test
    fun `chaque direction a son clip`() {
        assertEquals("tl_left", TwinLiteVoice.idFor(TwinLiteDirection.LEFT))
        assertEquals("tl_straight", TwinLiteVoice.idFor(TwinLiteDirection.STRAIGHT))
        assertEquals("tl_right", TwinLiteVoice.idFor(TwinLiteDirection.RIGHT))
        assertEquals("tl_stop", TwinLiteVoice.idFor(TwinLiteDirection.STOP))
    }

    @Test
    fun `le journal cite la phrase et l urgence`() = runBlocking {
        voix().say(signal(TwinLiteDirection.STOP), SpeechLanguage.Fr)

        assertTrue(journaux.any { it.contains("consigne.") && it.contains("urgent=true") })
    }
}
