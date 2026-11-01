package dev.sam3vision.app

import dev.sam3vision.core.settings.MemorySettingsStore
import dev.sam3vision.core.settings.SamRegion
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.resolveSamEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        liveLlmApiKey = "cle-narration-du-build",
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
        assertEquals("cle-narration-du-build", seed.liveLlmApiKey)
        assertTrue("la narration du Direct doit pouvoir parler", seed.liveLlmConfigured)
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
    fun `une voix restee sur Sarah migre vers la voix du build`() {
        val sarah = Settings.Default.copy(ttsVoiceId = Settings.SARAH_VOICE_ID)

        assertEquals("voix-du-build", seeded(sarah, build).ttsVoiceId)
    }

    @Test
    fun `une voix restee sur Sarah migre vers Laure sans voix de build`() {
        val sarah = Settings.Default.copy(ttsVoiceId = Settings.SARAH_VOICE_ID)
        val sansVoix = build.copy(ttsVoiceId = "")

        assertEquals(Settings.LAURE_VOICE_ID, seeded(sarah, sansVoix).ttsVoiceId)
    }

    @Test
    fun `une voix saisie a la main n est jamais migree`() {
        val choisie = Settings.Default.copy(ttsVoiceId = "ma-voix")

        assertEquals("ma-voix", seeded(choisie, build).ttsVoiceId)
    }

    @Test
    fun `une base URL saisie a la main n est jamais migree`() {
        val choisi = ancienne.copy(llmBaseUrl = "https://mon-propre-endpoint/v1", llmApiKey = "ma-cle")

        val seed = seeded(choisi, build)

        assertEquals("https://mon-propre-endpoint/v1", seed.llmBaseUrl)
        assertEquals("ma-cle", seed.llmApiKey)
    }

    @Test
    fun `une base sans scheme est reparee par la base du build`() {
        val cassee = Settings.Default.copy(llmBaseUrl = "inference.baseten.co/v1", llmApiKey = "ma-cle")

        val seed = seeded(cassee, build)

        assertEquals("une base sans https:// n'est pas un choix, le build reprend la main", "https://build.example/v1", seed.llmBaseUrl)
        assertEquals("ma-cle", seed.llmApiKey)
        assertTrue(seed.llmConfigured)
    }

    @Test
    fun `une base inutilisable et un build vide retombent sur le defaut du code`() {
        val vide = BuildDefaults("", "", "", "", "", "")
        val cassee = Settings.Default.copy(llmBaseUrl = "inference.baseten.co/v1")
        val blanche = Settings.Default.copy(llmBaseUrl = "  ")

        assertEquals(Settings.DEFAULT_LLM_BASE_URL, seeded(cassee, vide).llmBaseUrl)
        assertEquals(Settings.DEFAULT_LLM_BASE_URL, seeded(blanche, vide).llmBaseUrl)
    }

    @Test
    fun `le defaut reste Meta meme apres seed`() {
        val seed = seeded(Settings.Default, build)

        assertEquals("meta-models/muse-spark-open-1026", seed.llmModel)
        assertEquals(Settings.DEFAULT_LLM_MODEL, seed.llmModel)
        assertEquals("https://inference.baseten.co/v1", seed.llmBaseUrl)
    }

    @Test
    fun `la migration ne re-ecrit jamais un modele epingle`() {
        val pinned = Settings.Default.copy(llmBaseUrl = "https://api.inferhub.dev/v1")

        val seed = migrateLegacyLlm(pinned, build)

        assertEquals(Settings.DEFAULT_LLM_MODEL, seed.llmModel)
        assertEquals("https://api.inferhub.dev/v1", seed.llmBaseUrl)
    }

    @Test
    fun `l ancien site de suivi cede la place au relais de l app`() {
        val seed = seedShareRelay(Settings.Default.copy(parentSiteUrl = "https://suivi-parent.vercel.app/?share=ancien"))

        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, seed.parentSiteUrl)
    }

    @Test
    fun `la cle d ecriture nait avec l installation, sans etre demandee`() {
        val seed = seedShareRelay(Settings.Default.copy(shareWriteKey = ""), newKey = { "cle-generee" })

        assertEquals("cle-generee", seed.shareWriteKey)
    }

    @Test
    fun `chaque installation recoit son propre identifiant de flux`() {
        val premier = seedShareRelay(Settings.Default, newKey = { "cle-1" }, newId = { "flux-a1b2c3d4e5f6" })
        val second = seedShareRelay(Settings.Default, newKey = { "cle-2" }, newId = { "flux-9876543210ab" })

        assertEquals("flux-a1b2c3d4e5f6", premier.shareId)
        assertEquals("flux-9876543210ab", second.shareId)
        assertNotEquals(premier.shareId, second.shareId)
    }

    @Test
    fun `une installation neuve recoit son identifiant sans rien saisir`() {
        val seed = seedShareRelay(Settings.Default.copy(shareId = ""), newId = { "flux-neuf" })

        assertEquals("flux-neuf", seed.shareId)
    }

    @Test
    fun `l ancien defaut commun cede la place a un identifiant unique`() {
        val ancien = Settings.Default.copy(shareId = Settings.LEGACY_SHARE_ID)

        val seed = seedShareRelay(ancien, newId = { "flux-unique" })

        assertEquals("flux-unique", seed.shareId)
    }

    @Test
    fun `un identifiant choisi a la main n est jamais remplace`() {
        val choisi = Settings.Default.copy(shareId = "maman")

        val seed = seedShareRelay(choisi, newKey = { "cle" }, newId = { "flux-genere" })

        assertEquals("maman", seed.shareId)
    }

    @Test
    fun `une base ou une cle choisie a la main n est jamais ecrasee`() {
        val choisi = Settings.Default.copy(parentSiteUrl = "https://relais.exemple/", shareWriteKey = "la-mienne")

        val seed = seedShareRelay(choisi, newKey = { "autre" })

        assertEquals("https://relais.exemple/", seed.parentSiteUrl)
        assertEquals("la-mienne", seed.shareWriteKey)
    }

    @Test
    fun `un relais migre ne fabrique pas une nouvelle cle a chaque demarrage`() {
        val premiere = seedShareRelay(Settings.Default.copy(shareWriteKey = ""), newKey = { "clé-1" })
        val seconde = seedShareRelay(premiere, newKey = { "clé-2" })

        assertEquals("clé-1", seconde.shareWriteKey)
    }

    @Test
    fun `l identite de partage est scellee une fois pour toutes dans le store`() = runTest {
        val store = MemorySettingsStore(Settings.Default)

        val premiere = loadedSettings(store)
        val seconde = loadedSettings(store)

        assertEquals(premiere.shareId, seconde.shareId)
        assertEquals(premiere.shareWriteKey, seconde.shareWriteKey)
        assertEquals(premiere.shareId, store.load().shareId)
        assertNotEquals(Settings.LEGACY_SHARE_ID, premiere.shareId)
    }

    @Test
    fun `les cles du build sont scellees dans le store des qu elles comblent un vide`() = runTest {
        // Téléphone installé avec un build sans clés (identité déjà scellée, clés
        // vides), puis mis à jour vers un build qui embarque les clés : la graine ne
        // doit pas rester en mémoire, sinon la mise à jour suivante vers un build
        // sans clés rend l'app muette (01/11/2026 : texte affiché, silence total).
        val installe = Settings.Default.copy(
            shareId = "aa11bb22cc33",
            shareWriteKey = "cle-de-partage",
            ttsApiKey = "",
            asrApiKey = "",
            llmApiKey = "",
            liveLlmApiKey = "",
        )
        val store = MemorySettingsStore(installe)

        loadedSettings(store, build)

        val scelle = store.load()
        assertEquals("cle-voix-du-build", scelle.ttsApiKey)
        assertEquals("cle-dictee-du-build", scelle.asrApiKey)
        assertEquals("cle-llm-du-build", scelle.llmApiKey)
        assertEquals("cle-narration-du-build", scelle.liveLlmApiKey)
    }

    @Test
    fun `une mise a jour vers un build sans cles garde la voix scellee`() = runTest {
        val parle = Settings.Default.copy(
            shareId = "aa11bb22cc33",
            shareWriteKey = "cle-de-partage",
            ttsApiKey = "cle-voix-du-build",
        )
        val store = MemorySettingsStore(parle)
        val vide = BuildDefaults("", "", "", "", "", "")

        val apres = loadedSettings(store, vide)

        assertEquals("cle-voix-du-build", apres.ttsApiKey)
        assertEquals("cle-voix-du-build", store.load().ttsApiKey)
    }

    @Test
    fun `une URL SAM manuelle sans scheme retombe sur le preset de la region`() {
        val cassee = Settings.Default.copy(samBaseUrl = "baptiste-faisy--sam3-eu-a100.web.modal.run")

        val seed = seeded(cassee, build)

        assertEquals("", seed.samBaseUrl)
        assertEquals(SamRegion.Eu.presetBaseUrl, seed.resolveSamEndpoint())
    }

    @Test
    fun `une URL SAM manuelle utilisable est conservee`() {
        val manuelle = Settings.Default.copy(samBaseUrl = "https://mon-sam/v1")

        assertEquals("https://mon-sam/v1", seeded(manuelle, build).samBaseUrl)
    }

    @Test
    fun `une base itineraire sans scheme est reparee par le build, sinon Maps`() {
        val cassee = Settings.Default.copy(routeBaseUrl = "valhalla.exemple/route")
        val avecRoute = build.copy(routeBaseUrl = "https://route.build/v1")
        val sansRoute = build.copy(routeBaseUrl = "")

        assertEquals("https://route.build/v1", seeded(cassee, avecRoute).routeBaseUrl)
        assertEquals("le vide rend le guidage a Maps", "", seeded(cassee, sansRoute).routeBaseUrl)
    }

    @Test
    fun `une base dictee sans scheme retombe sur le defaut Meta, le vide garde Scribe`() {
        val cassee = Settings.Default.copy(asrBaseUrl = "api.meta.ai/v1")
        val vide = Settings.Default.copy(asrBaseUrl = "")

        assertEquals(Settings.DEFAULT_ASR_BASE_URL, seeded(cassee, build).asrBaseUrl)
        assertEquals("le vide garde le repli Scribe", "", seeded(vide, build).asrBaseUrl)
    }

    @Test
    fun `un modele LLM vide est repare par le build, sinon l epingle`() {
        val vide = Settings.Default.copy(llmApiKey = "ma-cle", llmModel = "")
        val sansModele = build.copy(llmModel = "")

        assertEquals("modele-du-build", seeded(vide, build).llmModel)
        assertEquals(Settings.DEFAULT_LLM_MODEL, seeded(vide, sansModele).llmModel)
    }

    @Test
    fun `un modele LLM saisi a la main est conserve`() {
        val choisi = Settings.Default.copy(llmApiKey = "ma-cle", llmModel = "mon-modele")

        assertEquals("mon-modele", seeded(choisi, build).llmModel)
    }

    @Test
    fun `un modele dictee vide retombe sur le defaut Meta`() {
        val vide = Settings.Default.copy(asrModel = "")
        val choisi = Settings.Default.copy(asrModel = "mon-modele-dictee")

        assertEquals(Settings.DEFAULT_ASR_MODEL, seeded(vide, build).asrModel)
        assertEquals("mon-modele-dictee", seeded(choisi, build).asrModel)
    }
}
