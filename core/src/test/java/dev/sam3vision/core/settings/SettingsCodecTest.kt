package dev.sam3vision.core.settings

import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `le plafond d'objets survit a un aller-retour`() {
        val saved = Settings.Default.copy(maxObjects = 9)
        assertEquals(9, SettingsCodec.decode(SettingsCodec.encode(saved)).maxObjects)
    }

    @Test
    fun `un ancien fichier sans plafond reprend le defaut`() {
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("maxObjects=") }
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, SettingsCodec.decode(legacy.joinToString("\n")).maxObjects)
    }

    @Test
    fun `un plafond aberrant est borne a la lecture`() {
        val defaultLine = "maxObjects=${ObjectLimits.DEFAULT_ON_SCREEN}"
        val broken = SettingsCodec.encode(Settings.Default).replace(defaultLine, "maxObjects=999")
        assertEquals(ObjectLimits.MAX_ON_SCREEN, SettingsCodec.decode(broken).maxObjects)
        val text = SettingsCodec.encode(Settings.Default).replace(defaultLine, "maxObjects=abc")
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, SettingsCodec.decode(text).maxObjects)
    }

    @Test
    fun `le mode vision seule survit a un aller-retour`() {
        // Le codec est la sérialisation de PrivateFileSettingsStore : défaut true
        // (SAM appelé pendant le direct), puis false relu tel quel après bascule.
        assertEquals(true, SettingsCodec.decode("").liveSamEnabled)
        assertEquals(true, SettingsCodec.decode(SettingsCodec.encode(Settings.Default)).liveSamEnabled)

        val vision = Settings.Default.copy(liveSamEnabled = false)
        assertEquals(false, SettingsCodec.decode(SettingsCodec.encode(vision)).liveSamEnabled)
    }

    @Test
    fun `la langue francaise stockee migre vers l'anglais a la lecture`() {
        val french = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr)
        assertEquals(SpeechLanguage.DEFAULT, SettingsCodec.decode(SettingsCodec.encode(french)).speechLanguage)
        // Un fichier écrit avant la bascule garde la langue par défaut (anglais).
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("speechLanguage=") }
        assertEquals(
            SpeechLanguage.DEFAULT,
            SettingsCodec.decode(legacy.joinToString("\n")).speechLanguage,
        )
    }

    @Test
    fun `le flag de prediction des boites est eteint par defaut et survit a un aller-retour`() {
        // Off par défaut : le branchement de la prédiction (T8) attend une mesure.
        assertEquals(false, Settings.Default.boxPredictionEnabled)
        assertEquals(false, SettingsCodec.decode("").boxPredictionEnabled)

        val on = Settings.Default.copy(boxPredictionEnabled = true)
        assertEquals(true, SettingsCodec.decode(SettingsCodec.encode(on)).boxPredictionEnabled)

        // Un fichier écrit avant le flag : éteint, jamais de prédiction héritée.
        val legacy = SettingsCodec.encode(Settings.Default).lines()
            .filterNot { it.startsWith("boxPredictionEnabled=") }
        assertEquals(false, SettingsCodec.decode(legacy.joinToString("\n")).boxPredictionEnabled)
    }

    @Test
    fun `le lien du suivi parent survit a un aller-retour`() {
        val custom = Settings.Default.copy(parentSiteUrl = "https://exemple.invalid/suivi/")
        assertEquals(
            "https://exemple.invalid/suivi/",
            SettingsCodec.decode(SettingsCodec.encode(custom)).parentSiteUrl,
        )
        // Un fichier écrit avant ce champ reprend le site de prod par défaut.
        val legacy = SettingsCodec.encode(Settings.Default).lines()
            .filterNot { it.startsWith("parentSiteUrl=") }
        assertEquals(
            Settings.DEFAULT_PARENT_SITE_URL,
            SettingsCodec.decode(legacy.joinToString("\n")).parentSiteUrl,
        )
        // Une valeur vide (ancien défaut) retombe aussi sur le site de prod.
        val blank = SettingsCodec.encode(Settings.Default)
            .replace("parentSiteUrl=${Settings.DEFAULT_PARENT_SITE_URL}", "parentSiteUrl=")
        assertEquals(
            Settings.DEFAULT_PARENT_SITE_URL,
            SettingsCodec.decode(blank).parentSiteUrl,
        )
    }

    @Test
    fun `le partage survit a un aller-retour`() {
        val custom = Settings.Default.copy(shareEnabled = false, shareId = "mia", shareWriteKey = "k")
        val round = SettingsCodec.decode(SettingsCodec.encode(custom))
        assertEquals(false, round.shareEnabled)
        assertEquals("mia", round.shareId)
        assertEquals("k", round.shareWriteKey)
        // Un fichier écrit avant ces champs reprend les défauts (partage actif).
        // Vide : l'identifiant propre au téléphone est attribué au chargement
        // suivant par `seedShareRelay`, jamais repris d'un ancien « leo » commun.
        val legacy = SettingsCodec.encode(Settings.Default).lines()
            .filterNot { it.startsWith("shareEnabled=") || it.startsWith("shareId=") }
        val back = SettingsCodec.decode(legacy.joinToString("\n"))
        assertEquals(Settings.Default.shareEnabled, back.shareEnabled)
        assertEquals("", back.shareId)
    }

    @Test
    fun `le role caregiver survit a un aller-retour et reste aveugle par defaut`() {
        // Défaut « Blind person » : une installation neuve ouvre iSee, pas la page suivi.
        assertEquals(CaregiverRole.Blind, Settings.Default.caregiverRole)
        assertEquals(CaregiverRole.Blind, SettingsCodec.decode("").caregiverRole)

        val caregiver = Settings.Default.copy(caregiverRole = CaregiverRole.Caregiver)
        assertEquals(
            CaregiverRole.Caregiver,
            SettingsCodec.decode(SettingsCodec.encode(caregiver)).caregiverRole,
        )
        // Un fichier écrit avant ce champ reprend le rôle aveugle, jamais le suivi.
        val legacy = SettingsCodec.encode(Settings.Default).lines()
            .filterNot { it.startsWith("caregiverRole=") }
        assertEquals(CaregiverRole.Blind, SettingsCodec.decode(legacy.joinToString("\n")).caregiverRole)
    }

    @Test
    fun `les distances et le fov survivent a un aller-retour`() {
        val custom = Settings.Default.copy(distanceOverlayEnabled = false, cameraFovDeg = 71.5f)
        val roundTrip = SettingsCodec.decode(SettingsCodec.encode(custom))
        assertFalse(roundTrip.distanceOverlayEnabled)
        assertEquals(71.5f, roundTrip.cameraFovDeg, 0.001f)
        // Un fichier écrit avant ces clés reprend les défauts.
        assertTrue(SettingsCodec.decode("").distanceOverlayEnabled)
        assertEquals(0f, SettingsCodec.decode("").cameraFovDeg, 0f)
    }

    @Test
    fun `la cle de narration survit a un aller-retour`() {
        val keyed = Settings.Default.copy(liveLlmApiKey = "cle-narration")
        assertEquals("cle-narration", SettingsCodec.decode(SettingsCodec.encode(keyed)).liveLlmApiKey)
        // Un fichier écrit avant le second modèle : narration muette, pas de crash.
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("liveLlmApiKey=") }
        assertEquals("", SettingsCodec.decode(legacy.joinToString("\n")).liveLlmApiKey)
    }

    @Test
    fun `l'acces objectsOnScreen borne toujours le reglage`() {
        assertEquals(ObjectLimits.MAX_ON_SCREEN, Settings.Default.copy(maxObjects = 50).objectsOnScreen)
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, Settings.Default.objectsOnScreen)
    }

    @Test
    fun `le montant adaptatif est allume par defaut, les keyframes eteints, aller-retour preserve`() {
        // Montant adaptatif allumé par défaut (audit réel 31/10), keyframes éteints.
        assertEquals(true, Settings.Default.adaptiveUploadEnabled)
        assertEquals(false, Settings.Default.keyframeSchedulerEnabled)
        assertEquals(true, SettingsCodec.decode("").adaptiveUploadEnabled)
        assertEquals(false, SettingsCodec.decode("").keyframeSchedulerEnabled)

        val on = Settings.Default.copy(keyframeSchedulerEnabled = true)
        val round = SettingsCodec.decode(SettingsCodec.encode(on))
        assertEquals(true, round.adaptiveUploadEnabled)
        assertEquals(true, round.keyframeSchedulerEnabled)

        // Un fichier écrit avant les flags : montant allumé (nouveau défaut), keyframes éteints.
        val legacy = SettingsCodec.encode(Settings.Default).lines()
            .filterNot { it.startsWith("adaptiveUploadEnabled=") || it.startsWith("keyframeSchedulerEnabled=") }
        assertEquals(true, SettingsCodec.decode(legacy.joinToString("\n")).adaptiveUploadEnabled)
        assertEquals(false, SettingsCodec.decode(legacy.joinToString("\n")).keyframeSchedulerEnabled)
    }

    @Test
    fun `l'endpoint d'itineraire survit a un aller-retour`() {
        val custom = Settings.Default.copy(routeBaseUrl = "https://route.test")
        assertEquals("https://route.test", SettingsCodec.decode(SettingsCodec.encode(custom)).routeBaseUrl)
        assertEquals(true, custom.routeConfigured)
        // Un fichier écrit avant ce champ : Valhalla désactivé, Maps reste le guidage.
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("routeBaseUrl=") }
        assertEquals("", SettingsCodec.decode(legacy.joinToString("\n")).routeBaseUrl)
        assertEquals(false, Settings.Default.routeConfigured)
    }
}
