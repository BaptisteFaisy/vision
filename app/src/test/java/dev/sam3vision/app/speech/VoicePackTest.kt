package dev.sam3vision.app.speech

import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pack voix : noms, cache par voix, installation embarquée, péremption.
 * Tout est injecté (dossier, lecteurs) : 100 % JVM, sans Android.
 */
class VoicePackTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private fun pack(
        voixPack: String? = "voix-laure-12345678",
        embarque: (String) -> ByteArray? = { null },
        horloge: () -> Long = { 1_000_000L },
    ) = VoicePack(dossier.root, { voixPack }, embarque, horloge)

    @Test
    fun `le nom de fichier suit vp id langue`() {
        assertEquals("vp_tl_left_fr.mp3",
            VoicePack.fileNameFor("tl_left", SpeechLanguage.Fr))
        assertEquals("vp_tl_stop_en.mp3",
            VoicePack.fileNameFor("tl_stop", SpeechLanguage.En))
    }

    @Test
    fun `le dossier porte les 8 premiers de la voix`() {
        assertEquals("voicepack-voix-lau", VoicePack.dirNameFor("voix-laure-12345678"))
        assertEquals("voicepack-defaut", VoicePack.dirNameFor("   "))
    }

    @Test
    fun `le clip embarqué s installe quand la voix colle`() {
        val voix = pack(embarque = { "MP3".toByteArray() })

        val clip = voix.clipFor("tl_left", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals("MP3", clip?.readText())
        assertTrue(clip?.path?.contains("voicepack-voix-lau") == true)
    }

    @Test
    fun `le cache relit sans toucher l embarqué`() {
        var lectures = 0
        val voix = pack(embarque = { lectures++; "MP3".toByteArray() })
        val premier = voix.clipFor("tl_left", SpeechLanguage.Fr, "voix-laure-12345678")

        val second = voix.clipFor("tl_left", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals(premier?.path, second?.path)
        assertEquals(1, lectures)
    }

    @Test
    fun `voix changée le pack est ignoré`() {
        var lectures = 0
        val voix = pack(embarque = { lectures++; "MP3".toByteArray() })

        val clip = voix.clipFor("tl_left", SpeechLanguage.Fr, "autre-voix-00000000")

        assertNull(clip)
        assertEquals(0, lectures)
    }

    @Test
    fun `clip périmé réinstallé`() {
        var lectures = 0
        val voix = pack(embarque = { lectures++; "NEUF".toByteArray() })
        val vieux = voix.clipFor("tl_left", SpeechLanguage.Fr, "voix-laure-12345678")
        vieux?.setLastModified(0L)
        val perime = pack(embarque = { lectures++; "NEUF".toByteArray() },
            horloge = { VoicePack.CLIP_TTL_MS + 1 })

        val clip = perime.clipFor("tl_left", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals(vieux?.path, clip?.path)
        assertEquals(2, lectures)
    }

    @Test
    fun `put met en cache la synthèse runtime`() {
        val voix = pack()

        val clip = voix.put("tl_stop", SpeechLanguage.En, "voix-laure-12345678", "SYNTH".toByteArray())

        assertEquals("SYNTH", clip?.readText())
        assertEquals(clip?.path,
            voix.clipFor("tl_stop", SpeechLanguage.En, "voix-laure-12345678")?.path)
    }

    @Test
    fun `put refuse le vide`() {
        val voix = pack()

        assertNull(voix.put("tl_stop", SpeechLanguage.En, "v", null))
        assertNull(voix.put("tl_stop", SpeechLanguage.En, "v", ByteArray(0)))
    }

    @Test
    fun `le cache runtime survit au changement de pack`() {
        val voix = pack(voixPack = "ancienne-voix-11111111")
        voix.put("tl_left", SpeechLanguage.Fr, "nouvelle-voix-22222222", "SYNTH".toByteArray())

        val clip = voix.clipFor("tl_left", SpeechLanguage.Fr, "nouvelle-voix-22222222")

        assertEquals("SYNTH", clip?.readText())
    }

    @Test
    fun `les langues ne se mélangent pas`() {
        val voix = pack()
        voix.put("tl_left", SpeechLanguage.Fr, "v", "FR".toByteArray())
        voix.put("tl_left", SpeechLanguage.En, "v", "EN".toByteArray())

        assertEquals("FR", voix.clipFor("tl_left", SpeechLanguage.Fr, "v")?.readText())
        assertEquals("EN", voix.clipFor("tl_left", SpeechLanguage.En, "v")?.readText())
    }
}
