package dev.sam3vision.core.settings

import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.speech.SpeechLanguage
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde de parité : les deux implémentations de [SettingsStore] doivent couvrir
 * **tous** les champs de [Settings]. Un champ ajouté sans être persisté retombe
 * silencieusement sur son défaut à chaque redémarrage — c'est exactement le bug
 * trouvé sur `distanceOverlayEnabled` et `cameraFovDeg` côté prefs chiffrées.
 */
class SettingsStoreParityTest {

    /** Tous les champs à une valeur non par défaut (et dans les bornes) : rien à perdre. */
    private val complet = Settings(
        llmBaseUrl = "https://api.test/v1",
        llmApiKey = "sk-complet-llm",
        llmModel = "modele-complet",
        samBaseUrl = "https://sam.test/live",
        ttsApiKey = "cle-complete-tts",
        ttsVoiceId = "voix-complete",
        ttsSpeed = 1.2f,
        ttsAutoplay = false,
        asrBaseUrl = "https://asr.test/v1",
        asrApiKey = "cle-complete-asr",
        asrModel = "modele-asr-complet",
        liveLlmApiKey = "cle-narration-complete",
        ttsAnnouncePositions = false,
        ttsGuidanceStraight = false,
        introEnabled = false,
        introPlayed = true,
        distanceOverlayEnabled = false,
        liveSamEnabled = false,
        boxPredictionEnabled = true,
        localOverlayEnabled = false,
        continuousCadenceEnabled = true,
        debugRouteMapEnabled = false,
        navigationGuidanceEnabled = false,
        outdoorMode = false,
        cameraFovDeg = 63.5f,
        caregiverRole = CaregiverRole.Caregiver,
        caregiverInvite = "https://suivi-parent.vercel.app/?share=marie",
        googleMapsApiKey = "cle-google-persistee",
        maxObjects = ObjectLimits.MIN_ON_SCREEN + 1,
        samRegion = SamRegion.Us,
        speechLanguage = SpeechLanguage.Fr,
        routeBaseUrl = "https://route.test",
    )

    @Test
    fun `le codec restitue tous les champs sans en perdre un`() {
        val relu = SettingsCodec.decode(SettingsCodec.encode(complet))

        assertEquals(complet, relu)
        assertEquals("https://api.test/v1", relu.llmBaseUrl)
        assertEquals("sk-complet-llm", relu.llmApiKey)
        assertEquals("cle-complete-tts", relu.ttsApiKey)
        assertEquals("cle-complete-asr", relu.asrApiKey)
        assertEquals(63.5f, relu.cameraFovDeg, 0.001f)
        assertEquals(false, relu.distanceOverlayEnabled)
        assertEquals(false, relu.navigationGuidanceEnabled)
        assertEquals(false, relu.outdoorMode)
        assertEquals(true, relu.boxPredictionEnabled)
        assertEquals(true, relu.continuousCadenceEnabled)
        assertEquals(SamRegion.Us, relu.samRegion)
        assertEquals(SpeechLanguage.Fr, relu.speechLanguage)
        assertEquals("https://route.test", relu.routeBaseUrl)
    }

    @Test
    fun `le codec ecrit une ligne par champ de Settings`() {
        val lignes = SettingsCodec.encode(Settings.Default).lines().filter { it.isNotBlank() }

        assertEquals(nombreDeChamps(), lignes.size)
        assertTrue(lignes.all { it.contains('=') })
    }

    @Test
    fun `le store chiffre a une cle de prefs par champ de Settings`() {
        val cles = EncryptedSettingsStore::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") }

        assertEquals(
            "un champ de Settings non persisté retombe sur son défaut au redémarrage",
            nombreDeChamps(),
            cles.size,
        )
    }

    @Test
    fun `les deux stores ne partagent aucune valeur de cle de prefs`() {
        val cles = EncryptedSettingsStore::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") }
            .map { it.get(null) as String }

        assertEquals("deux champs sur la même clé s'écrasent", cles.size, cles.toSet().size)
        assertNotEquals(0, cles.size)
    }

    private fun nombreDeChamps(): Int =
        Settings::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) }
}
