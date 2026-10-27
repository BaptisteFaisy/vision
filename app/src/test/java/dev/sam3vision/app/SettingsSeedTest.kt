package dev.sam3vision.app

import dev.sam3vision.core.settings.SamRegion
import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une clé embarquée au build doit suffire à faire parler l'app sur un téléphone neuf
 * (c'est la demande du 2026-10-26 : personne ne doit ressaisir de clé après avoir
 * installé l'APK), sans jamais écraser une clé déjà saisie dans Réglages.
 */
class SettingsSeedTest {

    private val build = BuildDefaults(
        llmBaseUrl = "https://build.example/v1",
        llmApiKey = "cle-llm-du-build",
        llmModel = "modele-du-build",
        ttsApiKey = "cle-voix-du-build",
        ttsVoiceId = "voix-du-build",
        asrApiKey = "cle-dictee-du-build",
    )

    @Test
    fun `installation neuve herite de toutes les cles du build`() {
        val neuf = Settings.Default.copy(llmBaseUrl = "", llmModel = "", ttsVoiceId = "")

        val seed = seeded(neuf, build)

        assertEquals("cle-llm-du-build", seed.llmApiKey)
        assertEquals("https://build.example/v1", seed.llmBaseUrl)
        assertEquals("modele-du-build", seed.llmModel)
        assertEquals("cle-voix-du-build", seed.ttsApiKey)
        assertEquals("voix-du-build", seed.ttsVoiceId)
        assertEquals("cle-dictee-du-build", seed.asrApiKey)
        assertTrue("l'app doit être utilisable sans rien saisir", seed.llmConfigured)
        assertTrue(seed.ttsConfigured)
        assertTrue(seed.asrConfigured)
    }

    @Test
    fun `une cle saisie sur le telephone gagne sur celle du build`() {
        val saisi = Settings.Default.copy(
            llmApiKey = "ma-cle",
            ttsApiKey = "ma-voix",
            asrApiKey = "ma-dictee",
        )

        val seed = seeded(saisi, build)

        assertEquals("ma-cle", seed.llmApiKey)
        assertEquals("ma-voix", seed.ttsApiKey)
        assertEquals("ma-dictee", seed.asrApiKey)
    }

    @Test
    fun `un build sans cle laisse les champs vides, donc la saisie`() {
        val vide = BuildDefaults("", "", "", "", "", "")

        val seed = seeded(Settings.Default.copy(llmApiKey = "", ttsApiKey = "", asrApiKey = ""), vide)

        assertEquals("", seed.llmApiKey)
        assertEquals("", seed.ttsApiKey)
        assertEquals("", seed.asrApiKey)
        assertEquals(Settings.DEFAULT_TTS_VOICE_ID, seed.ttsVoiceId)
    }

    @Test
    fun `un preset SAM est efface au profit de la region des reglages`() {
        val preset = Settings.Default.copy(samBaseUrl = SamRegion.Eu.presetBaseUrl)
        assertTrue("le preset doit être reconnu", SamRegion.isPreset(preset.samBaseUrl))

        val seed = seeded(preset, build)

        assertTrue("le preset ne doit pas figer l'Europe", seed.samBaseUrl.isEmpty())
    }

    /** App d'avant le 2026-10-27 : réglages d'usine InferHub + clé déjà saisie (ou héritée). */
    private val ancienne = Settings.Default.copy(
        llmBaseUrl = "https://api.inferhub.dev/v1",
        llmModel = "ali/qwen3.8-omni-flash",
        llmApiKey = "sk-ancienne-cle-inferhub",
    )

    @Test
    fun `un reglage reste sur l ancien defaut migre vers le defaut du build`() {
        val seed = seeded(ancienne, build)

        assertEquals("https://build.example/v1", seed.llmBaseUrl)
        assertEquals("modele-du-build", seed.llmModel)
        assertEquals("la clé de l'ancien service ne doit pas partir chez le nouveau", "cle-llm-du-build", seed.llmApiKey)
        assertTrue(seed.llmConfigured)
    }

    @Test
    fun `une base URL saisie a la main n est jamais migree`() {
        val choisi = ancienne.copy(llmBaseUrl = "https://mon-propre-endpoint/v1", llmApiKey = "ma-cle")

        val seed = seeded(choisi, build)

        assertEquals("https://mon-propre-endpoint/v1", seed.llmBaseUrl)
        assertEquals("ma-cle", seed.llmApiKey)
    }
}
