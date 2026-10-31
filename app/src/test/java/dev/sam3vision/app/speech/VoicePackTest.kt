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
        assertEquals("vp_cross_green_fr.mp3",
            VoicePack.fileNameFor("cross_green", SpeechLanguage.Fr))
        assertEquals("vp_cross_red_en.mp3",
            VoicePack.fileNameFor("cross_red", SpeechLanguage.En))
    }

    @Test
    fun `le dossier porte les 8 premiers de la voix`() {
        assertEquals("voicepack-voix-lau", VoicePack.dirNameFor("voix-laure-12345678"))
        assertEquals("voicepack-defaut", VoicePack.dirNameFor("   "))
    }

    @Test
    fun `le clip embarqué s installe quand la voix colle`() {
        val voix = pack(embarque = { "MP3".toByteArray() })

        val clip = voix.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals("MP3", clip?.readText())
        assertTrue(clip?.path?.contains("voicepack-voix-lau") == true)
    }

    @Test
    fun `le cache relit sans toucher l embarqué`() {
        var lectures = 0
        val voix = pack(embarque = { lectures++; "MP3".toByteArray() })
        val premier = voix.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")

        val second = voix.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals(premier?.path, second?.path)
        assertEquals(1, lectures)
    }

    @Test
    fun `voix changée le pack est ignoré`() {
        var lectures = 0
        val voix = pack(embarque = { lectures++; "MP3".toByteArray() })

        val clip = voix.clipFor("cross_green", SpeechLanguage.Fr, "autre-voix-00000000")

        assertNull(clip)
        assertEquals(0, lectures)
    }

    @Test
    fun `clip périmé réinstallé`() {
        var lectures = 0
        val voix = pack(embarque = { lectures++; "NEUF".toByteArray() })
        val vieux = voix.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")
        vieux?.setLastModified(0L)
        val perime = pack(embarque = { lectures++; "NEUF".toByteArray() },
            horloge = { VoicePack.CLIP_TTL_MS + 1 })

        val clip = perime.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals(vieux?.path, clip?.path)
        assertEquals(2, lectures)
    }

    @Test
    fun `put met en cache la synthèse runtime`() {
        val voix = pack()

        val clip = voix.put("cross_red", SpeechLanguage.En, "voix-laure-12345678", "SYNTH".toByteArray())

        assertEquals("SYNTH", clip?.readText())
        assertEquals(clip?.path,
            voix.clipFor("cross_red", SpeechLanguage.En, "voix-laure-12345678")?.path)
    }

    @Test
    fun `put refuse le vide`() {
        val voix = pack()

        assertNull(voix.put("cross_red", SpeechLanguage.En, "v", null))
        assertNull(voix.put("cross_red", SpeechLanguage.En, "v", ByteArray(0)))
    }

    @Test
    fun `le cache runtime survit au changement de pack`() {
        val voix = pack(voixPack = "ancienne-voix-11111111")
        voix.put("cross_green", SpeechLanguage.Fr, "nouvelle-voix-22222222", "SYNTH".toByteArray())

        val clip = voix.clipFor("cross_green", SpeechLanguage.Fr, "nouvelle-voix-22222222")

        assertEquals("SYNTH", clip?.readText())
    }

    @Test
    fun `clip perime servi sans embarque, mieux que rien`() {
        val voix = pack(embarque = { "VIEUX".toByteArray() })
        val installe = voix.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")
        installe?.setLastModified(0L)
        val sansEmbarque = pack(embarque = { null }, horloge = { VoicePack.CLIP_TTL_MS + 1 })

        val clip = sansEmbarque.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals("VIEUX", clip?.readText())
    }

    @Test
    fun `voix du pack tolere casse et blancs`() {
        val voix = pack(voixPack = "  VOIX-LAURE-12345678  ", embarque = { "MP3".toByteArray() })

        val clip = voix.clipFor("cross_green", SpeechLanguage.Fr, "voix-laure-12345678")

        assertEquals("MP3", clip?.readText())
    }

    @Test
    fun `les langues ne se mélangent pas`() {
        val voix = pack()
        voix.put("cross_green", SpeechLanguage.Fr, "v", "FR".toByteArray())
        voix.put("cross_green", SpeechLanguage.En, "v", "EN".toByteArray())

        assertEquals("FR", voix.clipFor("cross_green", SpeechLanguage.Fr, "v")?.readText())
        assertEquals("EN", voix.clipFor("cross_green", SpeechLanguage.En, "v")?.readText())
    }
}
