package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un `Settings` part dans des journaux, des traces d'exception et des messages de
 * test. Une clé d'API imprimée une fois reste lisible dans logcat aussi longtemps
 * que le téléphone vit : elle ne s'efface qu'en changeant d'appareil. Ces cas
 * verrouillent le masquage et la règle « aucune clé n'a de valeur par défaut ».
 */
class SettingsSecretsTest {

    private val cleLlm = "sk-llm-0123456789abcdef"
    private val cleTts = "sk_tts_0123456789abcdef"
    private val cleAsr = "sk-asr-0123456789abcdef"

    private val rempli = Settings.Default.copy(
        llmApiKey = cleLlm,
        ttsApiKey = cleTts,
        asrApiKey = cleAsr,
    )

    @Test
    fun `le toString ne contient aucune des trois cles`() {
        val texte = rempli.toString()

        assertFalse("clé LLM dans le toString", texte.contains(cleLlm))
        assertFalse("clé voix dans le toString", texte.contains(cleTts))
        assertFalse("clé dictée dans le toString", texte.contains(cleAsr))
    }

    @Test
    fun `le toString ne laisse pas non plus passer un fragment de cle`() {
        val texte = rempli.toString()

        assertFalse("4 derniers caractères de la clé LLM", texte.contains(cleLlm.takeLast(4)))
        assertFalse("préfixe de la clé LLM", texte.contains(cleLlm.take(6)))
        assertEquals(3, Regex(Regex.escape(MASKED_SECRET)).findAll(texte).count())
    }

    @Test
    fun `le toString reste utile au diagnostic pour les champs non secrets`() {
        val reglages = rempli.copy(
            llmModel = "modele-de-test",
            samBaseUrl = "https://sam.test/live",
            speechLanguage = dev.sam3vision.core.speech.SpeechLanguage.Fr,
        )
        val texte = reglages.toString()

        assertTrue(texte.contains("Settings("))
        assertTrue("le modèle doit rester lisible", texte.contains("modele-de-test"))
        assertTrue("l'url doit rester lisible", texte.contains("https://sam.test/live"))
        assertTrue("la langue doit rester lisible", texte.contains("Fr"))
        assertTrue("les booléens doivent rester lisibles", texte.contains("liveSamEnabled"))
    }

    @Test
    fun `une cle vide s affiche vide, pas masquee`() {
        val texte = Settings.Default.toString()

        assertTrue("rien à masquer", texte.contains("llmApiKey=,"))
        assertFalse("un réglage vide ne doit pas mentir sur une clé posée", texte.contains(MASKED_SECRET))
    }

    @Test
    fun `le masquage ne touche pas la valeur portee par l objet`() {
        val texte = rempli.toString()

        assertEquals(cleLlm, rempli.llmApiKey)
        assertEquals(cleLlm, rempli.copy().llmApiKey)
        assertEquals(rempli, rempli.copy())
        assertTrue(texte.isNotEmpty())
    }

    @Test
    fun `un message d exception construit avec les reglages ne fuit pas`() {
        val erreur = IllegalStateException("échec d'enregistrement des réglages : $rempli")

        assertFalse(erreur.message.orEmpty().contains(cleLlm))
        assertFalse(erreur.message.orEmpty().contains(cleTts))
        assertFalse(erreur.message.orEmpty().contains(cleAsr))
        assertTrue(erreur.message.orEmpty().contains("échec d'enregistrement"))
    }

    @Test
    fun `aucune cle n a de defaut dans Settings Default`() {
        val defaut = Settings.Default

        assertEquals("", defaut.llmApiKey)
        assertEquals("", defaut.ttsApiKey)
        assertEquals("", defaut.asrApiKey)
        assertFalse(defaut.llmConfigured)
        assertFalse(defaut.ttsConfigured)
        assertFalse(defaut.asrConfigured)
    }

    @Test
    fun `un fichier herite ne fabrique jamais de cle, donc la cle du build peut s appliquer`() {
        // Règle produit « la clé saisie dans l'app gagne sur la clé du build » : côté
        // core, elle tient parce que le codec ne remplit *jamais* une clé par défaut.
        // Voir aussi app/SettingsSeed.kt (implémentation de la règle, hors de ce module).
        val herite = SettingsCodec.decode("llmBaseUrl=https://api.test/v1\nllmModel=m\n")

        assertEquals("", herite.llmApiKey)
        assertEquals("", herite.ttsApiKey)
        assertEquals("", herite.asrApiKey)
        assertFalse(herite.llmConfigured)
    }

    @Test
    fun `une cle saisie survit a l aller-retour du codec et n est pas ecrasee`() {
        val saisi = Settings.Default.copy(llmApiKey = cleLlm, ttsApiKey = cleTts, asrApiKey = cleAsr)

        val relu = SettingsCodec.decode(SettingsCodec.encode(saisi))

        assertEquals(cleLlm, relu.llmApiKey)
        assertEquals(cleTts, relu.ttsApiKey)
        assertEquals(cleAsr, relu.asrApiKey)
        assertTrue(relu.llmConfigured)
    }
}